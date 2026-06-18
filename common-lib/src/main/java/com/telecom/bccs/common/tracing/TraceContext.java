package com.telecom.bccs.common.tracing;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Helper to parse / generate W3C trace context values.
 *
 * <p>traceparent format: {@code 00-<32 hex traceId>-<16 hex spanId>-<2 hex flags>}.
 * When an incoming traceparent is absent or malformed a fresh UUID v4 based trace id
 * is generated so no request is ever logged without correlation.
 */
public final class TraceContext {

    private static final Pattern TRACEPARENT = Pattern.compile(
            "^[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");

    private TraceContext() {
    }

    /** Extracts the 32-hex trace id from a traceparent header, or null if invalid. */
    public static String extractTraceId(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        var matcher = TRACEPARENT.matcher(traceparent.trim().toLowerCase());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** Extracts the 16-hex span id from a traceparent header, or null if invalid. */
    public static String extractSpanId(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        var matcher = TRACEPARENT.matcher(traceparent.trim().toLowerCase());
        return matcher.matches() ? matcher.group(2) : null;
    }

    /** 32 hex chars derived from a UUID v4 (no dashes). */
    public static String newTraceId() {
        UUID u = UUID.randomUUID();
        return digits(u.getMostSignificantBits()) + digits(u.getLeastSignificantBits());
    }

    /** 16 hex chars derived from a UUID v4. */
    public static String newSpanId() {
        return digits(UUID.randomUUID().getMostSignificantBits());
    }

    /** Rebuilds a valid traceparent header from a trace id / span id pair. */
    public static String buildTraceparent(String traceId, String spanId) {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private static String digits(long value) {
        return String.format("%016x", value);
    }
}
