package com.vertex.csms.common;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class ShopController {

    private final SettingsService settings;
    private final AuditService audit;
    private final com.vertex.csms.security.DemoLogins demoLogins;

    public ShopController(SettingsService settings, AuditService audit, com.vertex.csms.security.DemoLogins demoLogins) {
        this.settings = settings;
        this.audit = audit;
        this.demoLogins = demoLogins;
    }

    /** Shop name/address for the login page and the public e-bill page. */
    @GetMapping("/api/v1/public/shop")
    public ShopProperties shop() {
        return settings.shop();
    }

    @org.springframework.beans.factory.annotation.Value("${csms.demo-mode:true}")
    private boolean demoMode;

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url:}")
    private String datasourceUrl;
    private boolean localDatabase;

    /** True when the app uses a database on this computer (the browser tests refuse to run otherwise). */
    @jakarta.annotation.PostConstruct
    void detectLocalDatabase() {
        localDatabase = datasourceUrl != null && datasourceUrl.matches("^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)[:/].*");
    }

    /** For the login page: shop name, and whether to show the demo logins (never in a real shop). */
    @GetMapping("/api/v1/public/login-info")
    public Map<String, Object> loginInfo() {
        return Map.of("shopName", settings.shop().name(), "demoMode", demoMode, "localDatabase", localDatabase,
                "demoLogins", demoMode ? demoLogins.working() : List.of());
    }

    /** Activity log for the Owner (REQ-ACC-6). */
    @GetMapping("/api/v1/audit")
    @PreAuthorize("hasRole('OWNER')")
    public List<Map<String, Object>> auditLog(@RequestParam(required = false) String action,
                                              @RequestParam(defaultValue = "200") int limit) {
        return audit.recent(action, Math.min(Math.max(limit, 1), 1000));
    }
}
