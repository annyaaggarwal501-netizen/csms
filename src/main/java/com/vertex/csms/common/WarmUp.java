package com.vertex.csms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vertex.csms.product.ProductImageService;
import com.vertex.csms.product.VariantController;

/**
 * Right after start-up, runs the everyday screens' work once in the background (search, product list, a photo,
 * JSON), so the first real search does not pay the "first time" cost. On the small free server that first search
 * took about 12 seconds; afterwards it is well under a second. Read-only: nothing is saved.
 */
@Component
public class WarmUp {

    private static final Logger log = LoggerFactory.getLogger(WarmUp.class);

    private final VariantController variants;
    private final ProductImageService images;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SettingsService settings;

    public WarmUp(VariantController variants, ProductImageService images, JdbcTemplate jdbc, ObjectMapper json,
                  SettingsService settings) {
        this.variants = variants;
        this.images = images;
        this.jdbc = jdbc;
        this.json = json;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                for (String q : new String[] {"a", "kurti", "sar", "2000000000015"}) {
                    json.writeValueAsString(variants.searchRows(q, true));
                }
                json.writeValueAsString(jdbc.queryForList("SELECT product_id FROM product WHERE is_active = TRUE ORDER BY name LIMIT 200"));
                Long any = jdbc.query("SELECT product_id FROM product_image ORDER BY product_id LIMIT 1",
                        rs -> rs.next() ? rs.getLong(1) : null);
                if (any != null) images.get(any, true);
                settings.view();
                log.info("Warm-up done in {} ms: first searches will be fast.", System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.warn("Warm-up skipped: {}", e.getMessage());
            }
        }, "warm-up");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
