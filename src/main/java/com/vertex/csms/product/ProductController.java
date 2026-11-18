package com.vertex.csms.product;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;



import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class ProductController {

    private final ProductService service;
    private final ProductImportService importer;
    private final ProductImageService images;
    private final JdbcTemplate jdbc;

    public ProductController(ProductService service, ProductImportService importer, ProductImageService images,
                             JdbcTemplate jdbc) {
        this.service = service;
        this.importer = importer;
        this.images = images;
        this.jdbc = jdbc;
    }

    /** Shown for a product that has no photo yet (instead of a broken-image icon). */
    private static final byte[] NO_PHOTO = ("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 400 400\">"
            + "<rect width=\"400\" height=\"400\" fill=\"#eef0f2\"/>"
            + "<path d=\"M130 150h40l15-25h30l15 25h40v130H130z\" fill=\"none\" stroke=\"#9aa1a9\" stroke-width=\"10\" "
            + "stroke-linejoin=\"round\"/><circle cx=\"200\" cy=\"212\" r=\"35\" fill=\"none\" stroke=\"#9aa1a9\" "
            + "stroke-width=\"10\"/><text x=\"200\" y=\"330\" text-anchor=\"middle\" font-family=\"Arial\" "
            + "font-size=\"26\" fill=\"#646b73\">No photo yet</text></svg>").getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /**
     * The product's photo. The browser keeps a copy and only asks "has it changed?" (ETag), so pictures
     * load fast on every screen but a new upload shows up straight away. A product without a photo gets
     * a neutral "No photo yet" picture.
     */
    @GetMapping("/products/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable long id, @RequestParam(required = false) String size,
                                        WebRequest request) {
        boolean small = "thumb".equals(size);
        ProductImageService.Image img = images.get(id, small)
                .orElse(new ProductImageService.Image("image/svg+xml", NO_PHOTO, null, true));
        String etag = "\"" + id + (small ? "-t-" : "-") + (img.updatedAt() == null ? "none" : img.updatedAt().toString()) + "\"";
        if (request.checkNotModified(etag)) {
            return null;   // 304 Not Modified: the browser uses its saved copy
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.contentType()))
                .cacheControl(CacheControl.noCache().cachePrivate())
                .eTag(etag)
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .body(img.data());
    }

    /** Upload or replace the product photo (Owner only): JPG / PNG / WebP, max 2 MB (ERR-PRD-006). */
    @PutMapping(value = "/products/{id}/image", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('OWNER')")
    public ProductImageService.ImageInfo uploadImage(@PathVariable long id,
                                                     @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return images.save(id, file);
    }

    @DeleteMapping("/products/{id}/image")
    @PreAuthorize("hasRole('OWNER')")
    public void deleteImage(@PathVariable long id) {
        images.delete(id);
    }

    /** Bulk import, step 1: check every row and show VALID / WARNING / INVALID. Nothing is saved. */
    @PostMapping(value = "/products/import/preview", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('OWNER')")
    public ProductImportService.Preview importPreview(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return importer.preview(file);
    }

    /** Bulk import, step 2: save the VALID and WARNING rows of the same file in one transaction. */
    @PostMapping(value = "/products/import/commit", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('OWNER')")
    public ProductImportService.CommitResult importCommit(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return importer.commit(file);
    }

    /** Product list and search (REQ-PRD-6): name, brand, SKU or barcode. Owner and Staff. */
    @GetMapping("/products")
    public List<Map<String, Object>> list(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) Long categoryId,
                                          @RequestParam(defaultValue = "false") boolean includeInactive) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.product_id, p.name, p.brand, p.hsn_code, p.tax_class, p.gender, p.is_returnable,
                       p.is_active, c.name AS category, pc.name AS parent_category,
                       COUNT(v.variant_id) AS variant_count, COALESCE(SUM(v.stock_qty), 0) AS total_stock,
                       MIN(v.selling_price) AS min_price, MAX(v.selling_price) AS max_price,
                       COUNT(v.variant_id) FILTER (WHERE v.stock_qty <= v.reorder_level) AS low_variants,
                       pi.updated_at AS image_updated_at, pi.is_placeholder AS image_is_placeholder
                FROM product p
                JOIN category c ON c.category_id = p.category_id
                LEFT JOIN category pc ON pc.category_id = c.parent_id
                LEFT JOIN variant v ON v.product_id = p.product_id
                LEFT JOIN product_image pi ON pi.product_id = p.product_id
                WHERE 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (!includeInactive) {
            sql.append(" AND p.is_active = TRUE");
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            sql.append(" AND (p.name ILIKE ? OR p.brand ILIKE ? OR c.name ILIKE ? OR EXISTS (SELECT 1 FROM variant v2 "
                    + "WHERE v2.product_id = p.product_id AND (v2.sku ILIKE ? OR v2.barcode = ?)))");
            args.addAll(List.of(like, like, like, like, q.trim()));
        }
        if (categoryId != null) {
            sql.append(" AND (c.category_id = ? OR c.parent_id = ?)");
            args.add(categoryId);
            args.add(categoryId);
        }
        sql.append(" GROUP BY p.product_id, p.name, p.brand, p.hsn_code, p.tax_class, p.gender, p.is_returnable,"
                + " p.is_active, c.name, pc.name, pi.updated_at, pi.is_placeholder ORDER BY p.name LIMIT 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/products/{id}")
    public ProductService.ProductDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/products")
    @PreAuthorize("hasRole('OWNER')")
    public ProductService.ProductDetail create(@RequestBody @Valid ProductRequest req) {
        return service.create(req);
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ProductService.ProductDetail update(@PathVariable Long id, @RequestBody @Valid ProductRequest req) {
        return service.update(id, req);
    }


}
