package com.chronoscore.coordinator.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Uniform API Error Response model for all Chronos Core REST endpoints.
 * <p>
 * Ensures zero leakage of internal stack traces, SQL syntax, or Redis internals.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String requestId,
        String traceId,
        List<String> details
) {

    public ApiErrorResponse(int status, String error, String code, String message, String path) {
        this(Instant.now(), status, error, code, message, path, null, null, Collections.emptyList());
    }

    public ApiErrorResponse(int status, String error, String code, String message, String path, List<String> details) {
        this(Instant.now(), status, error, code, message, path, null, null, details != null ? details : Collections.emptyList());
    }

    public ApiErrorResponse(int status, String error, String code, String message, String path, String requestId, String traceId, List<String> details) {
        this(Instant.now(), status, error, code, message, path, requestId, traceId, details != null ? details : Collections.emptyList());
    }

    public static ApiErrorResponse of(int status, String error, String code, String message, String path) {
        return new ApiErrorResponse(status, error, code, message, path);
    }

    public static ApiErrorResponse of(int status, String error, String code, String message, String path, List<String> details) {
        return new ApiErrorResponse(status, error, code, message, path, details);
    }

    public static ApiErrorResponse of(int status, String error, String code, String message, String path, String requestId, String traceId, List<String> details) {
        return new ApiErrorResponse(status, error, code, message, path, requestId, traceId, details);
    }
}
