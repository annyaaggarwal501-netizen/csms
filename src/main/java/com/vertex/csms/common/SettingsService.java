package com.vertex.csms.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shop settings stored in the shop_setting table: GST on/off, shop details, business rules (REQ-MNT-4).
 * Read on every bill, so the values are kept in memory for one minute; a change made in the app applies at once.
 * The values in application.properties are only used when a setting is missing.
 */
@Service
public class SettingsService {

    private static final long CACHE_MS = 60_000;

    private final JdbcTemplate jdbc;
    private final ShopProperties shopDefaults;
    private final RulesProperties rulesDefaults;
    private volatile Map<String, String> cache;
    private volatile long loadedAt;

    public SettingsService(JdbcTemplate jdbc, ShopProperties shopDefaults, RulesProperties rulesDefaults) {
        this.jdbc = jdbc;
        this.shopDefaults = shopDefaults;
        this.rulesDefaults = rulesDefaults;
    }

    private Map<String, String> all() {
        Map<String, String> m = cache;
        if (m == null || Instant.now().toEpochMilli() - loadedAt > CACHE_MS) {
            Map<String, String> fresh = new HashMap<>();
            jdbc.query("SELECT setting_key, setting_value FROM shop_setting",
                    rs -> { fresh.put(rs.getString(1), rs.getString(2)); });
            cache = m = fresh;
            loadedAt = Instant.now().toEpochMilli();
        }
        return m;
    }

    private String get(String key, String fallback) {
        String v = all().get(key);
        return v == null ? fallback : v;
    }

    /** true = tax invoice with GST; false = Bill of Supply, no tax (small unregistered shop). */
    public boolean isGstRegistered() {
        return Boolean.parseBoolean(get("gst_registered", "false"));
    }

    public ShopProperties shop() {
        return new ShopProperties(
                get("shop_name", shopDefaults.name()),
                get("shop_address", shopDefaults.address()),
                get("shop_gstin", shopDefaults.gstin() == null ? "" : shopDefaults.gstin()),
                get("shop_phone", shopDefaults.phone()),
                get("shop_location_url", shopDefaults.locationUrl()),
                get("receipt_footer", shopDefaults.receiptFooter() == null ? "" : shopDefaults.receiptFooter()));
    }

    public RulesProperties rules() {
        return new RulesProperties(
                new BigDecimal(get("staff_discount_limit_percent", rulesDefaults.staffDiscountLimitPercent().toPlainString())),
                Integer.parseInt(get("return_window_days", String.valueOf(rulesDefaults.returnWindowDays()))),
                Integer.parseInt(get("credit_note_validity_days", String.valueOf(rulesDefaults.creditNoteValidityDays()))),
                Integer.parseInt(get("points_per_100", String.valueOf(rulesDefaults.pointsPer100()))));
    }

    /** Everything the Settings screen shows, in one map. */
    public Map<String, Object> view() {
        ShopProperties s = shop();
        RulesProperties r = rules();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gstRegistered", isGstRegistered());
        out.put("shopName", s.name());
        out.put("shopAddress", s.address());
        out.put("shopPhone", s.phone());
        out.put("shopGstin", s.gstin());
        out.put("shopLocationUrl", s.locationUrl());
        out.put("receiptFooter", s.receiptFooter());
        out.put("staffDiscountLimitPercent", r.staffDiscountLimitPercent());
        out.put("returnWindowDays", r.returnWindowDays());
        out.put("creditNoteValidityDays", r.creditNoteValidityDays());
        out.put("pointsPer100", r.pointsPer100());
        return out;
    }

    public record SettingsUpdate(Boolean gstRegistered, String shopName, String shopAddress, String shopPhone,
                                 String shopGstin, String shopLocationUrl, String receiptFooter,
                                 BigDecimal staffDiscountLimitPercent, Integer returnWindowDays,
                                 Integer creditNoteValidityDays, Integer pointsPer100) {}

    /** Validates and saves the fields that are present (null = leave unchanged). */
    @Transactional
    public Map<String, Object> update(SettingsUpdate u) {
        Map<String, String> changes = new LinkedHashMap<>();
        if (u.gstRegistered() != null) changes.put("gst_registered", String.valueOf(u.gstRegistered()));
        if (u.shopName() != null) {
            String v = u.shopName().trim();
            if (v.length() < 2 || v.length() > 80) throw bad("Shop name must be 2-80 characters.", "shopName");
            changes.put("shop_name", v);
        }
        if (u.shopAddress() != null) {
            if (u.shopAddress().trim().length() > 150) throw bad("Address can be at most 150 characters.", "shopAddress");
            changes.put("shop_address", u.shopAddress().trim());
        }
        if (u.shopPhone() != null) {
            String v = u.shopPhone().replaceAll("[^0-9]", "");
            if (!v.isEmpty() && !v.matches("\\d{10}")) throw bad("Phone must be 10 digits.", "shopPhone");
            changes.put("shop_phone", v);
        }
        if (u.shopGstin() != null) {
            String v = u.shopGstin().trim().toUpperCase();
            if (!v.isEmpty() && !v.matches("^[0-9]{2}[A-Z0-9]{10}[0-9A-Z]{3}$")) throw bad("GSTIN must be 15 characters, e.g. 08ABCDE1234F1Z5.", "shopGstin");
            changes.put("shop_gstin", v);
        }
        if (u.shopLocationUrl() != null) {
            String v = u.shopLocationUrl().trim();
            if (!v.isEmpty() && (!v.matches("^https?://.+") || v.length() > 300)) throw bad("Location must be a web link starting with https://", "shopLocationUrl");
            changes.put("shop_location_url", v);
        }
        if (u.receiptFooter() != null) {
            if (u.receiptFooter().trim().length() > 300) throw bad("Bill footer can be at most 300 characters.", "receiptFooter");
            changes.put("receipt_footer", u.receiptFooter().trim());
        }
        if (u.staffDiscountLimitPercent() != null) {
            BigDecimal v = u.staffDiscountLimitPercent();
            if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) throw bad("Discount limit must be 0-100%.", "staffDiscountLimitPercent");
            changes.put("staff_discount_limit_percent", v.stripTrailingZeros().toPlainString());
        }
        if (u.returnWindowDays() != null) {
            if (u.returnWindowDays() < 0 || u.returnWindowDays() > 365) throw bad("Return window must be 0-365 days.", "returnWindowDays");
            changes.put("return_window_days", String.valueOf(u.returnWindowDays()));
        }
        if (u.creditNoteValidityDays() != null) {
            if (u.creditNoteValidityDays() < 1 || u.creditNoteValidityDays() > 730) throw bad("Credit note validity must be 1-730 days.", "creditNoteValidityDays");
            changes.put("credit_note_validity_days", String.valueOf(u.creditNoteValidityDays()));
        }
        if (u.pointsPer100() != null) {
            if (u.pointsPer100() < 0 || u.pointsPer100() > 100) throw bad("Points per Rs 100 must be 0-100.", "pointsPer100");
            changes.put("points_per_100", String.valueOf(u.pointsPer100()));
        }
        boolean gstOn = changes.containsKey("gst_registered") ? Boolean.parseBoolean(changes.get("gst_registered")) : isGstRegistered();
        String gstin = changes.getOrDefault("shop_gstin", shop().gstin());
        if (gstOn && (gstin == null || gstin.isBlank())) {
            throw bad("A GST-registered shop needs its GSTIN (printed on every tax invoice).", "shopGstin");
        }
        changes.forEach((k, v) -> jdbc.update("""
                INSERT INTO shop_setting (setting_key, setting_value, updated_at) VALUES (?, ?, NOW())
                ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = NOW()""", k, v));
        cache = null;
        return view();
    }

    @Transactional
    public void setGstRegistered(boolean registered) {
        jdbc.update("""
                INSERT INTO shop_setting (setting_key, setting_value, updated_at) VALUES ('gst_registered', ?, NOW())
                ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = NOW()""",
                String.valueOf(registered));
        cache = null;
    }

    private static ApiException bad(String msg, String field) {
        return ApiException.badRequest("ERR-VAL-001", msg, field);
    }
}
