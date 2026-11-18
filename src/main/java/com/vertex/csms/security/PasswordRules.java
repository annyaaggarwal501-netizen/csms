package com.vertex.csms.security;

import com.vertex.csms.common.ApiException;

/** Password and PIN rules (REQ-ACC-3, REQ-ACC-5). */
public final class PasswordRules {

    private PasswordRules() {}

    public static void checkPassword(String pw, String field) {
        if (pw == null || pw.length() < 8 || pw.length() > 72 || !pw.matches(".*[A-Za-z].*") || !pw.matches(".*\\d.*")) {
            throw ApiException.badRequest("ERR-ACC-005",
                    "Password must be at least 8 characters with at least one letter and one number.", field);
        }
    }

    public static void checkPin(String pin) {
        if (pin == null || !pin.matches("\\d{4,6}")) {
            throw ApiException.badRequest("ERR-ACC-005", "Owner PIN must be 4 to 6 digits.", "newPin");
        }
    }

    public static void checkUsername(String u) {
        if (u == null || !u.matches("^[a-z0-9._]{3,30}$")) {
            throw ApiException.badRequest("ERR-VAL-001",
                    "Username: 3-30 small letters, numbers, dot or underscore (e.g. ravi.k).", "username");
        }
    }
}
