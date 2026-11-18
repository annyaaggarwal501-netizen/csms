package com.vertex.csms.product;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.vertex.csms.common.ApiException;
import com.vertex.csms.common.AuditService;

/** Category tree, two levels (e.g. Women > Kurtis), managed by the Owner (REQ-PRD-7). */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CategoryController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record CategoryRequest(String name, Long parentId, Boolean active) {}

    /** Active categories for dropdowns (label "Women > Kurtis"); ?all=true also lists inactive ones with counts. */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean all) {
        return jdbc.queryForList("""
                SELECT c.category_id, c.name, c.parent_id, p.name AS parent_name, c.is_active,
                       CASE WHEN p.name IS NULL THEN c.name ELSE CONCAT(p.name, ' > ', c.name) END AS label,
                       (SELECT COUNT(*) FROM product pr WHERE pr.category_id = c.category_id) AS products
                FROM category c LEFT JOIN category p ON p.category_id = c.parent_id
                WHERE ? OR c.is_active = TRUE ORDER BY label""", all);
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public Map<String, Object> create(@RequestBody CategoryRequest r) {
        String name = check(r);
        Long id = jdbc.queryForObject("INSERT INTO category (name, parent_id, is_active) VALUES (?, ?, TRUE) RETURNING category_id",
                Long.class, name, r.parentId());
        audit.log("CATEGORY_CREATED", "category", id, name);
        return one(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public Map<String, Object> update(@PathVariable Long id, @RequestBody CategoryRequest r) {
        one(id);
        String name = check(r);
        if (id.equals(r.parentId())) {
            throw ApiException.badRequest("ERR-VAL-001", "A category cannot be inside itself.", "parentId");
        }
        jdbc.update("UPDATE category SET name = ?, parent_id = ?, is_active = ? WHERE category_id = ?",
                name, r.parentId(), r.active() == null || r.active(), id);
        audit.log("CATEGORY_UPDATED", "category", id, name + (Boolean.FALSE.equals(r.active()) ? " (hidden)" : ""));
        return one(id);
    }

    private Map<String, Object> one(Long id) {
        return list(true).stream().filter(c -> ((Number) c.get("category_id")).longValue() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("ERR-VAL-001", "Category not found."));
    }

    private String check(CategoryRequest r) {
        String name = r.name() == null ? "" : r.name().trim();
        if (name.length() < 2 || name.length() > 50) {
            throw ApiException.badRequest("ERR-VAL-001", "Category name must be 2-50 characters.", "name");
        }
        if (r.parentId() != null) {
            Long grand = jdbc.query("SELECT parent_id FROM category WHERE category_id = ?",
                    rs -> rs.next() ? (Long) rs.getObject(1, Long.class) : -1L, r.parentId());
            if (grand != null && grand == -1L) {
                throw ApiException.badRequest("ERR-VAL-001", "Parent category not found.", "parentId");
            }
            if (grand != null) {
                throw ApiException.badRequest("ERR-VAL-001", "Only two levels are allowed (e.g. Women > Kurtis).", "parentId");
            }
        }
        Integer dup = jdbc.queryForObject("""
                SELECT COUNT(*) FROM category WHERE LOWER(name) = LOWER(?) AND parent_id IS NOT DISTINCT FROM ?""",
                Integer.class, name, r.parentId());
        if (dup != null && dup > 0 && r.active() == null) {
            throw ApiException.conflict("ERR-VAL-001", "This category already exists here.", "name");
        }
        return name;
    }
}
