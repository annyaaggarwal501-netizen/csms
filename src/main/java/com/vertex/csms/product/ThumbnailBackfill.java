package com.vertex.csms.product;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * At start-up, makes the small 320 px copy for any JPG/PNG photo that has none yet (for example photos loaded
 * straight into the database, or uploaded before V6 added thumbnails). Runs one photo at a time; usually finds nothing.
 */
@Component
public class ThumbnailBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailBackfill.class);
    private final JdbcTemplate jdbc;
    private final ProductImageService images;

    public ThumbnailBackfill(JdbcTemplate jdbc, ProductImageService images) {
        this.jdbc = jdbc;
        this.images = images;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Long> ids = jdbc.queryForList("""
                SELECT product_id FROM product_image
                WHERE thumb IS NULL AND content_type IN ('image/jpeg', 'image/png') ORDER BY product_id""", Long.class);
        int made = 0;
        for (Long id : ids) {
            byte[] data = jdbc.queryForObject("SELECT data FROM product_image WHERE product_id = ?", byte[].class, id);
            byte[] thumb = ProductImageService.thumbnail(data);
            if (thumb != null) {
                jdbc.update("UPDATE product_image SET thumb = ? WHERE product_id = ? AND thumb IS NULL", thumb, id);
                made++;
            }
        }
        if (made > 0) images.forgetAll();
        if (made > 0) log.info("Made small copies (thumbnails) for {} product photos.", made);
    }
}
