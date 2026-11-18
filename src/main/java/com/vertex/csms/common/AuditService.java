package com.vertex.csms.common;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.vertex.csms.security.UserPrincipal;

/**
 * Activity log (REQ-ACC-6): who did what and when. Rows are only ever added, never changed.
 * Written in its own transaction so that, for example, a failed login is recorded even though the login fails.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, String entity, Long entityId, String details) {
        Long userId = null;
        String username = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            userId = p.id();
            username = p.username();
        }
        logAs(userId, username, action, entity, entityId, details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAs(Long userId, String username, String action, String entity, Long entityId, String details) {
        String ip = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            String fwd = a.getRequest().getHeader("X-Forwarded-For");
            ip = fwd != null ? fwd.split(",")[0].trim() : a.getRequest().getRemoteAddr();
        }
        String d = details == null ? null : (details.length() > 500 ? details.substring(0, 500) : details);
        jdbc.update("INSERT INTO audit_log (user_id, username, action, entity, entity_id, details, ip) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, username, action, entity, entityId, d, ip);
    }

    public List<Map<String, Object>> recent(String action, int limit) {
        if (action != null && !action.isBlank()) {
            return jdbc.queryForList("SELECT * FROM audit_log WHERE action = ? ORDER BY audit_id DESC LIMIT ?", action, limit);
        }
        return jdbc.queryForList("SELECT * FROM audit_log ORDER BY audit_id DESC LIMIT ?", limit);
    }
}
