package com.vertex.csms.security;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.vertex.csms.common.ApiException;
import com.vertex.csms.common.AuditService;

/** Owner only (SecurityConfig: /api/v1/users/**): staff accounts, temporary passwords, unlock (REQ-ACC-1/4). */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final DemoLogins demoLogins;

    public UserController(AppUserRepository users, PasswordEncoder encoder, AuditService audit, DemoLogins demoLogins) {
        this.users = users;
        this.demoLogins = demoLogins;
        this.encoder = encoder;
        this.audit = audit;
    }

    public record UserRow(Long id, String username, String fullName, Role role, boolean active, boolean locked,
                          int failedAttempts, boolean mustChangePassword, LocalDateTime lastLoginAt) {
        static UserRow of(AppUser u) {
            return new UserRow(u.getId(), u.getUsername(), u.getFullName(), u.getRole(), u.isActive(), u.isLocked(),
                    u.getFailedAttempts(), u.isMustChangePassword(), u.getLastLoginAt());
        }
    }

    public record UserRequest(String username, String fullName, Role role, String password, Boolean active) {}

    @GetMapping
    public List<UserRow> list() {
        return users.findAllByOrderByIdAsc().stream().map(UserRow::of).toList();
    }

    /** New account with a temporary password; the person must choose their own at first login. */
    @PostMapping
    @Transactional
    public UserRow create(@RequestBody UserRequest req) {
        String username = req.username() == null ? "" : req.username().trim().toLowerCase();
        PasswordRules.checkUsername(username);
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("ERR-VAL-001", "Username " + username + " is already taken.", "username");
        }
        String name = checkName(req.fullName());
        PasswordRules.checkPassword(req.password(), "password");
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setFullName(name);
        u.setRole(req.role() == null ? Role.SALES_STAFF : req.role());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setActive(true);
        u.setMustChangePassword(true);
        users.save(u);
        audit.log("USER_CREATED", "user", u.getId(), username + " (" + u.getRole() + ")");
        demoLogins.refreshLater();
        return UserRow.of(u);
    }

    @PutMapping("/{id}")
    @Transactional
    public UserRow update(@PathVariable Long id, @RequestBody UserRequest req) {
        AppUser u = find(id);
        StringBuilder changes = new StringBuilder();
        if (req.fullName() != null) {
            u.setFullName(checkName(req.fullName()));
            changes.append("name; ");
        }
        if (req.role() != null && req.role() != u.getRole()) {
            if (u.getRole() == Role.OWNER) keepOneOwner(u);
            u.setRole(req.role());
            changes.append("role -> ").append(req.role()).append("; ");
        }
        if (req.active() != null && req.active() != u.isActive()) {
            if (!req.active()) {
                if (u.getId().equals(CurrentUser.id())) {
                    throw ApiException.badRequest("ERR-VAL-001", "You cannot deactivate your own account.", "active");
                }
                if (u.getRole() == Role.OWNER) keepOneOwner(u);
            }
            u.setActive(req.active());
            changes.append(req.active() ? "activated; " : "deactivated; ");
        }
        audit.log("USER_UPDATED", "user", u.getId(), u.getUsername() + ": " + changes);
        demoLogins.refreshLater();
        return UserRow.of(u);
    }

    public record ResetRequest(String password) {}

    /** Owner gives a temporary password (forgotten password); the account is also unlocked. */
    @PostMapping("/{id}/reset-password")
    @Transactional
    public UserRow resetPassword(@PathVariable Long id, @RequestBody ResetRequest req) {
        AppUser u = find(id);
        PasswordRules.checkPassword(req.password(), "password");
        u.setPasswordHash(encoder.encode(req.password()));
        u.setMustChangePassword(true);
        u.setLocked(false);
        u.setFailedAttempts(0);
        audit.log("PASSWORD_RESET", "user", u.getId(), u.getUsername());
        demoLogins.refreshLater();
        return UserRow.of(u);
    }

    @PostMapping("/{id}/unlock")
    @Transactional
    public UserRow unlock(@PathVariable Long id) {
        AppUser u = find(id);
        u.setLocked(false);
        u.setFailedAttempts(0);
        audit.log("USER_UNLOCKED", "user", u.getId(), u.getUsername());
        return UserRow.of(u);
    }

    private AppUser find(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("ERR-ACC-001", "User not found."));
    }

    private void keepOneOwner(AppUser u) {
        long owners = users.findByRoleAndIsActiveTrue(Role.OWNER).stream().filter(o -> !o.getId().equals(u.getId())).count();
        if (owners == 0) {
            throw ApiException.badRequest("ERR-VAL-001", "The shop needs at least one active Owner account.", "role");
        }
    }

    private static String checkName(String n) {
        String name = n == null ? "" : n.trim().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 80) {
            throw ApiException.badRequest("ERR-VAL-001", "Full name must be 2-80 characters.", "fullName");
        }
        return name;
    }
}
