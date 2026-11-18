package com.vertex.csms.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.vertex.csms.common.ApiException;
import com.vertex.csms.common.AuditService;
import com.vertex.csms.product.Variant;
import com.vertex.csms.product.VariantRepository;
import com.vertex.csms.security.CurrentUser;

/**
 * Stock screen: current stock per item, movement history (REQ-REP-3) and adjustments (REQ-INV-3).
 * Staff see quantities only; cost and stock value are for the Owner (REQ-ACC-1).
 */
@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    public static final List<String> REASONS = List.of("Damaged", "Lost", "Counting correction", "Sample", "Other");

    private final JdbcTemplate jdbc;
    private final VariantRepository variants;
    private final StockService stock;
    private final AuditService audit;

    public StockController(JdbcTemplate jdbc, VariantRepository variants, StockService stock, AuditService audit) {
        this.jdbc = jdbc;
        this.variants = variants;
        this.stock = stock;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "all") String filter,
                                          @RequestParam(required = false) Long categoryId) {
        boolean owner = CurrentUser.isOwner();
        StringBuilder sql = new StringBuilder("""
                SELECT v.variant_id, v.product_id, p.name, v.size, v.colour, v.sku, v.barcode, v.stock_qty,
                       v.reorder_level, v.selling_price, v.mrp, c.name AS category""");
        if (owner) sql.append(", v.cost_price, v.cost_price * v.stock_qty AS stock_value");
        sql.append("""
                 FROM variant v JOIN product p ON p.product_id = v.product_id
                JOIN category c ON c.category_id = p.category_id
                WHERE p.is_active = TRUE""");
        List<Object> args = new ArrayList<>();
        if ("low".equals(filter)) sql.append(" AND v.stock_qty <= v.reorder_level AND v.stock_qty > 0");
        if ("out".equals(filter)) sql.append(" AND v.stock_qty = 0");
        if ("low+out".equals(filter)) sql.append(" AND v.stock_qty <= v.reorder_level");
        if (q != null && !q.isBlank()) {
            sql.append(" AND (p.name ILIKE ? OR v.sku ILIKE ? OR v.barcode = ? OR v.colour ILIKE ?)");
            String like = "%" + q.trim() + "%";
            args.addAll(List.of(like, like, q.trim(), like));
        }
        if (categoryId != null) {
            sql.append(" AND (c.category_id = ? OR c.parent_id = ?)");
            args.add(categoryId);
            args.add(categoryId);
        }
        sql.append(" ORDER BY p.name, v.size, v.colour LIMIT 1000");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** Totals for the top of the stock screen (value only for the Owner). */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> s = jdbc.queryForMap("""
                SELECT COUNT(*) AS variants, COALESCE(SUM(v.stock_qty), 0) AS pieces,
                       COUNT(*) FILTER (WHERE v.stock_qty = 0) AS out_of_stock,
                       COUNT(*) FILTER (WHERE v.stock_qty > 0 AND v.stock_qty <= v.reorder_level) AS low_stock,
                       COALESCE(SUM(v.cost_price * v.stock_qty), 0) AS value_at_cost,
                       COALESCE(SUM(v.selling_price * v.stock_qty), 0) AS value_at_price
                FROM variant v JOIN product p ON p.product_id = v.product_id WHERE p.is_active = TRUE""");
        if (!CurrentUser.isOwner()) {
            s.remove("value_at_cost");
            s.remove("value_at_price");
        }
        return s;
    }

    @GetMapping("/{variantId}/movements")
    public List<Map<String, Object>> movements(@PathVariable Long variantId) {
        return jdbc.queryForList("""
                SELECT m.movement_id, m.type, m.qty_change, m.ref_type, m.ref_id, m.reason, m.created_at,
                       u.full_name AS user_name,
                       CASE WHEN m.ref_type = 'SALE' THEN (SELECT invoice_no FROM sale WHERE sale_id = m.ref_id)
                            WHEN m.ref_type = 'PURCHASE' THEN (SELECT bill_no FROM purchase WHERE purchase_id = m.ref_id)
                       END AS ref_label,
                       SUM(m.qty_change) OVER (ORDER BY m.movement_id) AS balance
                FROM stock_movement m JOIN app_user u ON u.user_id = m.user_id
                WHERE m.variant_id = ? ORDER BY m.movement_id DESC LIMIT 300""", variantId);
    }

    public record Adjustment(Long variantId, Integer qtyChange, String reason, String note) {}

    /** Owner corrects stock: damaged, lost, counting difference, sample (REQ-INV-3). Never below 0 (ERR-INV-002). */
    @PostMapping("/adjustments")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public Map<String, Object> adjust(@RequestBody Adjustment a) {
        if (a.variantId() == null || a.qtyChange() == null || a.qtyChange() == 0) {
            throw ApiException.badRequest("ERR-VAL-001", "Enter how many pieces to add (+) or remove (-).", "qtyChange");
        }
        if (Math.abs(a.qtyChange()) > 9999) {
            throw ApiException.badRequest("ERR-VAL-001", "At most 9,999 pieces in one adjustment.", "qtyChange");
        }
        if (a.reason() == null || !REASONS.contains(a.reason())) {
            throw ApiException.badRequest("ERR-INV-003", "Choose a reason for the adjustment.", "reason");
        }
        String note = a.note() == null ? "" : a.note().trim();
        if ("Other".equals(a.reason()) && note.length() < 10) {
            throw ApiException.badRequest("ERR-INV-003", "For \"Other\", explain the reason in at least 10 characters.", "note");
        }
        List<Variant> locked = variants.lockByIds(List.of(a.variantId()));
        if (locked.isEmpty()) {
            throw ApiException.notFound("ERR-PRD-004", "Item not found.");
        }
        Variant v = locked.get(0);
        int before = v.getStockQty();
        String reason = note.isEmpty() ? a.reason() : a.reason() + ": " + note;
        stock.change(v, MovementType.ADJUSTMENT, a.qtyChange(), "ADJUSTMENT", null,
                reason.length() > 100 ? reason.substring(0, 100) : reason, CurrentUser.id());
        audit.log("STOCK_ADJUSTED", "variant", v.getId(), v.getSku() + " " + before + " -> " + v.getStockQty() + " (" + reason + ")");
        return Map.of("variantId", v.getId(), "stockBefore", before, "stockAfter", v.getStockQty());
    }

    /** Data for barcode labels: chosen variants (1 label each) or every piece of one purchase. */
    @GetMapping("/labels")
    public List<Map<String, Object>> labels(@RequestParam(required = false) List<Long> ids,
                                            @RequestParam(required = false) Long purchaseId) {
        String cols = """
                SELECT v.variant_id, p.product_id, p.name, v.size, v.colour, v.sku, v.barcode, v.selling_price, v.mrp, v.stock_qty""";
        if (purchaseId != null) {
            return jdbc.queryForList(cols + """
                    , pl.quantity AS qty FROM purchase_line pl JOIN variant v ON v.variant_id = pl.variant_id
                    JOIN product p ON p.product_id = v.product_id WHERE pl.purchase_id = ? ORDER BY pl.pline_id""", purchaseId);
        }
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.size() > 500) throw ApiException.badRequest("ERR-VAL-001", "At most 500 items at a time.", "ids");
        String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.queryForList(cols + ", 1 AS qty FROM variant v JOIN product p ON p.product_id = v.product_id"
                + " WHERE v.variant_id IN (" + in + ") ORDER BY p.name, v.size, v.colour", ids.toArray());
    }

    @GetMapping("/reasons")
    public List<String> reasons() {
        return REASONS;
    }
}
