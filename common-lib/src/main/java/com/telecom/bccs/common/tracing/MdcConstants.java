package com.telecom.bccs.common.tracing;

/**
 * Canonical MDC / HTTP header / Kafka header keys used across every service so a
 * single request can be correlated end-to-end on Kibana (W3C Trace Context aware).
 */
public final class MdcConstants {

    private MdcConstants() {
    }

    /** W3C standard distributed trace header: version-traceid-spanid-flags. */
    public static final String W3C_TRACEPARENT = "traceparent";

    /** MDC keys (these become JSON fields in the log output). */
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_CLIENT_ID = "clientId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_SERVICE_NAME = "serviceName";

    /** Context headers injected by the gateway and propagated downstream. */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_SPAN_ID = "X-Span-Id";
    public static final String HEADER_CLIENT_ID = "X-Client-Id";
    public static final String HEADER_USER_ID = "X-User-Id";

    /** Kafka record headers (byte[] values) carrying the same context. */
    public static final String KAFKA_HEADER_TRACE_ID = "x-trace-id";
    public static final String KAFKA_HEADER_CLIENT_ID = "x-client-id";
}
