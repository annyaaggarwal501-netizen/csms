package com.vertex.csms.security;

import java.io.Serializable;

/** What we keep in the HTTP session after login. */
public record UserPrincipal(Long id, String username, String fullName, Role role, boolean mustChangePassword)
        implements Serializable {

    public boolean isOwner() {
        return role == Role.OWNER;
    }
}
