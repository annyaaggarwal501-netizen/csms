package com.vertex.csms.product;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.vertex.csms.common.ApiException;
import com.vertex.csms.security.CurrentUser;

/**
 * Product photos, stored in the product_image table (V5) so every computer that uses the shared
 * database sees the same picture. One picture per product.
 * Uploads must be JPG, PNG or WebP and at most 2 MB (ERR-PRD-006, SRS appendix). The file type is
 * checked from the file's first bytes, not from its name, so a renamed file cannot slip through.
 */
@Service
public class ProductImageService {

    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private final JdbcTemplate jdbc;

    public ProductImageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Image(String contentType, byte[] data, LocalDateTime updatedAt, boolean placeholder) {}

    public record ImageInfo(LocalDateTime updatedAt, boolean placeholder) {}

    public static final int THUMB_PX = 320;

    /** The full photo, or with small = true the ~320 px thumbnail (falls back to the full photo if there is none). */
    public Optional<Image> get(long productId, boolean small) {
        String column = small ? "COALESCE(thumb, data)" : "data";
        String type = small ? "CASE WHEN thumb IS NULL THEN content_type ELSE 'image/jpeg' END" : "content_type";
        List<Image> found = jdbc.query(
                "SELECT " + type + " AS content_type, " + column + " AS data, updated_at, is_placeholder "
                        + "FROM product_image WHERE product_id = ?",
                (rs, i) -> new Image(rs.getString("content_type"), rs.getBytes("data"),
                        rs.getObject("updated_at", LocalDateTime.class), rs.getBoolean("is_placeholder")),
                productId);
        return found.stream().findFirst();
    }

    public Optional<ImageInfo> info(long productId) {
        List<ImageInfo> found = jdbc.query(
                "SELECT updated_at, is_placeholder FROM product_image WHERE product_id = ?",
                (rs, i) -> new ImageInfo(rs.getObject("updated_at", LocalDateTime.class), rs.getBoolean("is_placeholder")),
                productId);
        return found.stream().findFirst();
    }

    /** Save (or replace) the photo of a product. */
    @Transactional
    public ImageInfo save(long productId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("ERR-PRD-006", "Choose a photo to upload.", "file");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("ERR-PRD-006", "The photo is larger than 2 MB. Use a smaller photo.", "file");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("ERR-PRD-006", "The photo could not be read.", "file");
        }
        String type = detectType(data);
        if (type == null) {
            throw ApiException.badRequest("ERR-PRD-006", "Only JPG, PNG or WebP photos can be uploaded.", "file");
        }
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE product_id = ?", Integer.class, productId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("ERR-PRD-004", "Product not found.");
        }
        LocalDateTime now = LocalDateTime.now();
        byte[] thumb = thumbnail(data);
        jdbc.update("""
                INSERT INTO product_image (product_id, content_type, data, thumb, is_placeholder, updated_by, updated_at)
                VALUES (?, ?, ?, ?, FALSE, ?, ?)
                ON CONFLICT (product_id) DO UPDATE SET content_type = EXCLUDED.content_type, data = EXCLUDED.data,
                    thumb = EXCLUDED.thumb, is_placeholder = FALSE, updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at""",
                productId, type, data, thumb, CurrentUser.id(), now);
        return new ImageInfo(now, false);
    }

    @Transactional
    public void delete(long productId) {
        jdbc.update("DELETE FROM product_image WHERE product_id = ?", productId);
    }

    /**
     * A small JPEG copy, longest side 320 px, for lists and the POS. Java can read JPG and PNG; for WebP
     * (or a photo already smaller than that) it returns null and the full photo is used.
     */
    static byte[] thumbnail(byte[] data) {
        try {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (src == null || Math.max(src.getWidth(), src.getHeight()) <= THUMB_PX) {
                return null;
            }
            double scale = (double) THUMB_PX / Math.max(src.getWidth(), src.getHeight());
            int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);              // transparent PNG parts become white
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(out, "jpg", bos);
            return bos.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;                                   // never block an upload because of the small copy
        }
    }

    /** JPG, PNG or WebP from the file's first bytes ("magic numbers"); null for anything else. */
    static String detectType(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
