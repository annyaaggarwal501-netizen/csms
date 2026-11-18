package com.vertex.csms.billing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.vertex.csms.common.ApiException;
import com.vertex.csms.common.AuditService;

/**
 * Offers (REQ-OFR-1): percentage or flat discount on one product, one category (and its sub-categories)
 * or the whole shop, between two dates. The POS applies the best running offer automatically.
 */
@RestController
@RequestMapping("/api/v1/offers")
public class OfferController {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public OfferController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record OfferRequest(String name, String type, BigDecimal value, Long categoryId, Long productId,
                               LocalDateTime validFrom, LocalDateTime validTo, Boolean active) {}

    /** All offers with their status: RUNNING, UPCOMING, ENDED or OFF. Staff can see them too. */
    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT o.offer_id, o.name, o.type, o.value, o.category_id, o.product_id, o.valid_from, o.valid_to,
                       o.is_active, c.name AS category_name, p.name AS product_name,
                       CASE WHEN NOT o.is_active THEN 'OFF'
                            WHEN NOW() < o.valid_from THEN 'UPCOMING'
                            WHEN NOW() > o.valid_to THEN 'ENDED' ELSE 'RUNNING' END AS status,
                       (SELECT COUNT(*) FROM sale_line sl JOIN sale s ON s.sale_id = sl.sale_id
                         WHERE sl.offer_id = o.offer_id AND s.status <> 'CANCELLED') AS times_used
                FROM offer o
                LEFT JOIN category c ON c.category_id = o.category_id
                LEFT JOIN product p ON p.product_id = o.product_id
                ORDER BY (CASE WHEN o.is_active AND NOW() BETWEEN o.valid_from AND o.valid_to THEN 0 ELSE 1 END),
                         o.valid_to DESC""");
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public Map<String, Object> create(@RequestBody OfferRequest r) {
        check(r);
        Long id = jdbc.queryForObject("""
                INSERT INTO offer (name, type, value, category_id, product_id, valid_from, valid_to, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING offer_id""", Long.class,
                r.name().trim(), r.type(), r.value(), r.categoryId(), r.productId(), r.validFrom(), r.validTo(),
                r.active() == null || r.active());
        audit.log("OFFER_CREATED", "offer", id, r.name().trim() + " " + r.value() + (r.type().equals("PERCENT") ? "%" : " Rs"));
        return one(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public Map<String, Object> update(@PathVariable Long id, @RequestBody OfferRequest r) {
        one(id);
        check(r);
        jdbc.update("""
                UPDATE offer SET name = ?, type = ?, value = ?, category_id = ?, product_id = ?, valid_from = ?,
                       valid_to = ?, is_active = ? WHERE offer_id = ?""",
                r.name().trim(), r.type(), r.value(), r.categoryId(), r.productId(), r.validFrom(), r.validTo(),
                r.active() == null || r.active(), id);
        audit.log("OFFER_UPDATED", "offer", id, r.name().trim() + (Boolean.FALSE.equals(r.active()) ? " (switched off)" : ""));
        return one(id);
    }

    private Map<String, Object> one(Long id) {
        return list().stream().filter(o -> ((Number) o.get("offer_id")).longValue() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("ERR-VAL-001", "Offer not found."));
    }

    private void check(OfferRequest r) {
        if (r.name() == null || r.name().trim().length() < 3 || r.name().trim().length() > 80) {
            throw ApiException.badRequest("ERR-VAL-001", "Offer name must be 3-80 characters.", "name");
        }
        if (!"PERCENT".equals(r.type()) && !"FLAT".equals(r.type())) {
            throw ApiException.badRequest("ERR-VAL-001", "Choose percent off or flat amount off.", "type");
        }
        if (r.value() == null || r.value().signum() <= 0
                || ("PERCENT".equals(r.type()) && r.value().compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw ApiException.badRequest("ERR-VAL-001", "Discount must be more than 0 (and at most 90%).", "value");
        }
        if (r.categoryId() != null && r.productId() != null) {
            throw ApiException.badRequest("ERR-VAL-001", "Choose either a category or a product, not both.", "productId");
        }
        if (r.validFrom() == null || r.validTo() == null || !r.validTo().isAfter(r.validFrom())) {
            throw ApiException.badRequest("ERR-VAL-001", "The end date must be after the start date.", "validTo");
        }
    }
}
