package com.telecom.bccs.integration.model;

/**
 * OAuth2-introspection-style response. Returned to the gateway when it falls back to remote
 * validation. {@code active=false} means the token is invalid/expired/revoked.
 */
public record TokenValidationResult(
        boolean active,
        String sub,
        String client_id,
        Long exp,
        String error
) {
    public static TokenValidationResult inactive(String error) {
        return new TokenValidationResult(false, null, null, null, error);
    }

    public static TokenValidationResult active(String sub, String clientId, Long exp) {
        return new TokenValidationResult(true, sub, clientId, exp, null);
    }
}
