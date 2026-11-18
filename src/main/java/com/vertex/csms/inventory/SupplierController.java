package com.vertex.csms.inventory;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.vertex.csms.common.ApiException;
import com.vertex.csms.common.AuditService;

/** Suppliers, Owner only (SecurityConfig: /api/v1/suppliers/**). */
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SupplierController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record SupplierRequest(String name, String phone, String gstin) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT s.supplier_id AS id, s.name, s.phone, s.gstin,
                       (SELECT COUNT(*) FROM purchase p WHERE p.supplier_id = s.supplier_id) AS purchases,
                       (SELECT MAX(p.bill_date) FROM purchase p WHERE p.supplier_id = s.supplier_id) AS last_purchase
                FROM supplier s ORDER BY s.name""");
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody SupplierRequest r) {
        Object[] v = check(r);
        Long id = jdbc.queryForObject("INSERT INTO supplier (name, phone, gstin) VALUES (?, ?, ?) RETURNING supplier_id",
                Long.class, v);
        audit.log("SUPPLIER_CREATED", "supplier", id, (String) v[0]);
        return one(id);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable Long id, @RequestBody SupplierRequest r) {
        one(id);
        Object[] v = check(r);
        jdbc.update("UPDATE supplier SET name = ?, phone = ?, gstin = ? WHERE supplier_id = ?", v[0], v[1], v[2], id);
        audit.log("SUPPLIER_UPDATED", "supplier", id, (String) v[0]);
        return one(id);
    }

    private Map<String, Object> one(Long id) {
        return list().stream().filter(s -> ((Number) s.get("id")).longValue() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("ERR-VAL-001", "Supplier not found."));
    }

    private static Object[] check(SupplierRequest r) {
        String name = r.name() == null ? "" : r.name().trim();
        if (name.length() < 2 || name.length() > 80) {
            throw ApiException.badRequest("ERR-VAL-001", "Supplier name must be 2-80 characters.", "name");
        }
        String phone = r.phone() == null ? "" : r.phone().replaceAll("[^0-9]", "");
        if (!phone.isEmpty() && !phone.matches("\\d{10}")) {
            throw ApiException.badRequest("ERR-VAL-001", "Phone must be 10 digits.", "phone");
        }
        String gstin = r.gstin() == null ? "" : r.gstin().trim().toUpperCase();
        if (!gstin.isEmpty() && !gstin.matches("^[0-9]{2}[A-Z0-9]{13}$")) {
            throw ApiException.badRequest("ERR-VAL-001", "GSTIN must be 15 characters.", "gstin");
        }
        return new Object[]{name, phone.isEmpty() ? null : phone, gstin.isEmpty() ? null : gstin};
    }
}
