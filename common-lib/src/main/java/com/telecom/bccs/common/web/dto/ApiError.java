package com.telecom.bccs.common.web.dto;

import java.time.Instant;

/**
 * Uniform error payload returned to 3rd-party callers. The traceId lets a partner
 * quote a single id to support so the exact request can be found in Kibana.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String traceId
) {
    public static ApiError of(int status, String error, String message, String path, String traceId) {
        return new ApiError(Instant.now(), status, error, message, path, traceId);
    }
}
