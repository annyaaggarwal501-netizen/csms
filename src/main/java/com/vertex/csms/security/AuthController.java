package com.vertex.csms.security;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.vertex.csms.common.ApiException;
import com.vertex.csms.common.AuditService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final SecurityContextRepository contextRepository;
    private final AuditService audit;

    public AuthController(AppUserRepository users, PasswordEncoder encoder, SecurityContextRepository contextRepository,
                          AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.contextRepository = contextRepository;
        this.audit = audit;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    // noRollbackFor: the failed-attempt counter must be saved even though we throw an error.
    @PostMapping("/login")
    @Transactional(noRollbackFor = ApiException.class)
    public UserPrincipal login(@RequestBody @jakarta.validation.Valid LoginRequest req,
                               HttpServletRequest request, HttpServletResponse response) {
        String username = req.username().trim().toLowerCase();
        AppUser user = users.findByUsername(username).filter(AppUser::isActive).orElse(null);
        if (user == null) {
            audit.logAs(null, username, "LOGIN_FAILED", "user", null, "Unknown or inactive username");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ERR-ACC-001", "Invalid username or password.");
        }
        if (user.isLocked()) {
            audit.logAs(user.getId(), username, "LOGIN_BLOCKED", "user", user.getId(), "Account is locked");
            throw new ApiException(HttpStatus.LOCKED, "ERR-ACC-002",
                    "Account locked after " + MAX_FAILED_ATTEMPTS + " failed attempts. Ask the Owner to unlock it.");
        }
        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            user.setFailedAttempts(user.getFailedAttempts() + 1);
            if (user.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
                user.setLocked(true);
                audit.logAs(user.getId(), username, "ACCOUNT_LOCKED", "user", user.getId(), "5 wrong passwords");
                throw new ApiException(HttpStatus.LOCKED, "ERR-ACC-002",
                        "Account locked after " + MAX_FAILED_ATTEMPTS + " failed attempts. Ask the Owner to unlock it.");
            }
            audit.logAs(user.getId(), username, "LOGIN_FAILED", "user", user.getId(), "Wrong password");
            int left = MAX_FAILED_ATTEMPTS - user.getFailedAttempts();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ERR-ACC-001",
                    "Invalid username or password. " + left + " attempt(s) left before the account locks.");
        }
        user.setFailedAttempts(0);
        user.setLastLoginAt(LocalDateTime.now());
        UserPrincipal principal = principalOf(user);
        startSession(principal, request, response);
        audit.logAs(user.getId(), username, "LOGIN", "user", user.getId(), null);
        return principal;
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public UserPrincipal me() {
        return CurrentUser.get();
    }

    public record ChangePassword(String currentPassword, String newPassword) {}

    /** Anyone changes their own password (needed after a temporary password). */
    @PostMapping("/change-password")
    @Transactional
    public UserPrincipal changePassword(@RequestBody ChangePassword req, HttpServletRequest request,
                                        HttpServletResponse response) {
        AppUser user = users.findById(CurrentUser.id()).orElseThrow();
        if (req.currentPassword() == null || !encoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("ERR-ACC-001", "Your current password is not correct.", "currentPassword");
        }
        PasswordRules.checkPassword(req.newPassword(), "newPassword");
        if (encoder.matches(req.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("ERR-ACC-005", "The new password must be different from the old one.", "newPassword");
        }
        user.setPasswordHash(encoder.encode(req.newPassword()));
        user.setMustChangePassword(false);
        UserPrincipal principal = principalOf(user);
        startSession(principal, request, response);
        audit.log("PASSWORD_CHANGED", "user", user.getId(), null);
        return principal;
    }

    public record ChangePin(String currentPassword, String newPin) {}

    /** Owner changes the Owner PIN (used to approve big discounts, late returns, voids by staff). */
    @PostMapping("/change-pin")
    @Transactional
    public void changePin(@RequestBody ChangePin req) {
        if (!CurrentUser.isOwner()) {
            throw ApiException.forbidden("ERR-ACC-003", "Only the Owner has a PIN.");
        }
        AppUser user = users.findById(CurrentUser.id()).orElseThrow();
        if (req.currentPassword() == null || !encoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("ERR-ACC-001", "Your password is not correct.", "currentPassword");
        }
        PasswordRules.checkPin(req.newPin());
        user.setOwnerPinHash(encoder.encode(req.newPin()));
        audit.log("PIN_CHANGED", "user", user.getId(), null);
    }

    static UserPrincipal principalOf(AppUser u) {
        return new UserPrincipal(u.getId(), u.getUsername(), u.getFullName(), u.getRole(), u.isMustChangePassword());
    }

    private void startSession(UserPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        // New session id on login (protects against session fixation).
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
