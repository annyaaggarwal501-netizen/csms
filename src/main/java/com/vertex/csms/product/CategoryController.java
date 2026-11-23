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
        String name = check(r, null);
        Long id = jdbc.queryForObject("INSERT INTO category (name, parent_id, is_active) VALUES (?, ?, TRUE) RETURNING category_id",
                Long.class, name, r.parentId());
        audit.log("CATEGORY_CREATED", "category", id, r.parentId() == null ? name : parentName(r.parentId()) + " > " + name);
        return one(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    @Transactional
    public Map<String, Object> update(@PathVariable Long id, @RequestBody CategoryRequest r) {
        one(id);
        String name = check(r, id);
        jdbc.update("UPDATE category SET name = ?, parent_id = ?, is_active = ? WHERE category_id = ?",
                name, r.parentId(), r.active() == null || r.active(), id);
        audit.log("CATEGORY_UPDATED", "category", id, name + (Boolean.FALSE.equals(r.active()) ? " (hidden)" : ""));
        return one(id);
    }

    private Map<String, Object> one(Long id) {
        return list(true).stream().filter(c -> ((Number) c.get("category_id")).longValue() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("ERR-VAL-001", "Category not found."));
    }

    private String parentName(Long parentId) {
        return jdbc.queryForObject("SELECT name FROM category WHERE category_id = ?", String.class, parentId);
    }

    /**
     * Checks a new or changed category. selfId is the category being edited (null when adding).
     * Rules: name 2-50 characters; at most two levels (Women > Kurtis); no two categories with the same name
     * in the same place.
     */
    private String check(CategoryRequest r, Long selfId) {
        String name = r.name() == null ? "" : r.name().trim().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 50) {
            throw ApiException.badRequest("ERR-VAL-001", "Category name must be 2-50 characters.", "name");
        }
        if (r.parentId() != null) {
            if (r.parentId().equals(selfId)) {
                throw ApiException.badRequest("ERR-VAL-001", "A category cannot be inside itself.", "parentId");
            }
            List<Map<String, Object>> parent = jdbc.queryForList(
                    "SELECT parent_id FROM category WHERE category_id = ?", r.parentId());
            if (parent.isEmpty()) {
                throw ApiException.badRequest("ERR-VAL-001", "The main category was not found. Refresh the page and try again.", "parentId");
            }
            if (parent.get(0).get("parent_id") != null) {
                throw ApiException.badRequest("ERR-VAL-001", "Only two levels are allowed (e.g. Women > Kurtis). "
                        + "Choose a main category.", "parentId");
            }
            if (selfId != null) {
                Integer children = jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE parent_id = ?", Integer.class, selfId);
                if (children != null && children > 0) {
                    throw ApiException.badRequest("ERR-VAL-001", "This category has its own sub-categories, so it must stay a "
                            + "main category.", "parentId");
                }
            }
        }
        Integer dup = jdbc.queryForObject("""
                SELECT COUNT(*) FROM category WHERE LOWER(name) = LOWER(?)
                  AND COALESCE(parent_id, 0) = COALESCE(CAST(? AS BIGINT), 0) AND category_id <> COALESCE(CAST(? AS BIGINT), 0)""",
                Integer.class, name, r.parentId(), selfId);
        if (dup != null && dup > 0) {
            throw ApiException.conflict("ERR-VAL-001", "\"" + name + "\" already exists"
                    + (r.parentId() == null ? " as a main category." : " in " + parentName(r.parentId()) + "."), "name");
        }
        return name;
    }
}
