package com.vertex.csms.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vertex.csms.common.ErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A user with a temporary password (new account or reset by the Owner) can only change the password.
 * Every other API call is refused until then, on the server - not just hidden on the screen.
 */
@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED = Set.of("/api/v1/auth/me", "/api/v1/auth/logout",
            "/api/v1/auth/change-password", "/api/v1/settings");

    private final ObjectMapper mapper;

    public MustChangePasswordFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String path = req.getRequestURI();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p && p.mustChangePassword()
                && path.startsWith("/api/") && !path.startsWith("/api/v1/public/") && !ALLOWED.contains(path)) {
            res.setStatus(403);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getOutputStream(), ErrorResponse.of("ERR-ACC-006",
                    "Please set your own password first.", null));
            return;
        }
        chain.doFilter(req, res);
    }
}
