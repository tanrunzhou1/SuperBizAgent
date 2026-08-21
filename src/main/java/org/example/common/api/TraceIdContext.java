package org.example.common.api;

import org.slf4j.MDC;

import java.util.UUID;

public final class TraceIdContext {
    public static final String TRACE_ID = "traceId";

    private TraceIdContext() {
    }

    public static String getOrCreate() {
        String traceId = MDC.get(TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            MDC.put(TRACE_ID, traceId);
        }
        return traceId;
    }
}
