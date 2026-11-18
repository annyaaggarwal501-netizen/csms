package com.vertex.csms.common;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settings;
    private final AuditService audit;

    public SettingsController(SettingsService settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    /** Shop details, rules and GST mode (every logged-in user; nothing secret). */
    @GetMapping
    public Map<String, Object> get() {
        return settings.view();
    }

    @PutMapping
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> update(@RequestBody SettingsService.SettingsUpdate req) {
        Map<String, Object> before = settings.view();
        Map<String, Object> after = settings.update(req);
        StringBuilder diff = new StringBuilder();
        after.forEach((k, v) -> {
            if (!String.valueOf(v).equals(String.valueOf(before.get(k)))) {
                diff.append(k).append(": ").append(before.get(k)).append(" -> ").append(v).append("; ");
            }
        });
        if (!diff.isEmpty()) audit.log("SETTINGS_CHANGED", "settings", null, diff.toString());
        return after;
    }

    public record Gst(boolean gstRegistered) {}

    @PutMapping("/gst")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> setGst(@RequestBody Gst req) {
        return update(new SettingsService.SettingsUpdate(req.gstRegistered(), null, null, null, null, null, null,
                null, null, null, null));
    }
}
