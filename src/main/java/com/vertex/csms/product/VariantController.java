package com.vertex.csms.product;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.vertex.csms.common.ApiException;
import com.vertex.csms.security.CurrentUser;

import jakarta.persistence.EntityManager;

@RestController
@RequestMapping("/api/v1/variants")
public class VariantController {

    private final VariantRepository variants;
    private final EntityManager em;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public VariantController(VariantRepository variants, EntityManager em, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.variants = variants;
        this.em = em;
        this.jdbc = jdbc;
    }

    /** One plain SQL query, straight into the API shape (no entity loading): cheap even on a small server. */
    static final String SEARCH_SQL = """
            SELECT v.variant_id, p.product_id, p.name, p.brand, v.sku, v.barcode, v.size, v.colour, v.cost_price,
                   v.selling_price, v.mrp, v.stock_qty, v.reorder_level, p.tax_class, p.hsn_code, p.is_returnable,
                   c.name AS category
            FROM variant v JOIN product p ON p.product_id = v.product_id JOIN category c ON c.category_id = p.category_id
            WHERE p.is_active = TRUE
              AND (p.name ILIKE ? OR v.sku ILIKE ? OR (p.name || ' ' || v.size || ' ' || v.colour) ILIKE ? OR v.barcode = ?)
            ORDER BY p.name, v.variant_id
            LIMIT 30""";

    public List<VariantDto> searchRows(String q, boolean showCost) {
        String term = q == null ? "" : q.trim();
        String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query(SEARCH_SQL, (rs, i) -> {
            int qty = rs.getInt("stock_qty"), reorder = rs.getInt("reorder_level");
            return new VariantDto(rs.getLong("variant_id"), rs.getLong("product_id"), rs.getString("name"),
                    rs.getString("brand"), rs.getString("sku"), rs.getString("barcode"), rs.getString("size"),
                    rs.getString("colour"), showCost ? rs.getBigDecimal("cost_price") : null,
                    rs.getBigDecimal("selling_price"), rs.getBigDecimal("mrp"), qty, reorder,
                    qty <= 0 ? "OUT_OF_STOCK" : qty <= reorder ? "LOW_STOCK" : "OK",
                    TaxClass.valueOf(rs.getString("tax_class")), rs.getString("hsn_code"),
                    rs.getBoolean("is_returnable"), rs.getString("category"));
        }, like, like, like, term);
    }

    /** Barcode scan: GET /api/v1/variants?barcode=2000000000015 (also accepts ?sku=). */
    @GetMapping
    @Transactional(readOnly = true)
    public VariantDto byCode(@RequestParam(required = false) String barcode, @RequestParam(required = false) String sku) {
        String code = barcode != null ? barcode.trim() : (sku != null ? sku.trim() : "");
        Variant v = variants.findByBarcode(code).or(() -> variants.findBySku(code))
                .filter(x -> x.getProduct().isActive())
                .orElseThrow(() -> ApiException.notFound("ERR-PRD-004",
                        "No item found for \"" + code + "\". Check the barcode or search by name."));
        return VariantDto.of(v, CurrentUser.isOwner());
    }

    /** Search variants by product name, SKU, size or colour (for the POS and stock-in screens). */
    @GetMapping("/search")
    @Transactional(readOnly = true)
    public List<VariantDto> search(@RequestParam String q) {
        return searchRows(q, CurrentUser.isOwner());
    }

    /** Low-stock list: stock_qty <= reorder_level (week-1 query). */
    @GetMapping("/low-stock")
    @Transactional(readOnly = true)
    public List<VariantDto> lowStock() {
        List<Variant> found = em.createQuery("""
                select v from Variant v join fetch v.product p join fetch p.category
                where p.isActive = true and v.stockQty <= v.reorderLevel
                order by v.stockQty, p.name""", Variant.class).getResultList();
        boolean showCost = CurrentUser.isOwner();
        return found.stream().map(v -> VariantDto.of(v, showCost)).toList();
    }
}
