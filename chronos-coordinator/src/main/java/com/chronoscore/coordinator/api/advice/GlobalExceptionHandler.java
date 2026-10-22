package com.chronoscore.coordinator.api.advice;

import com.chronoscore.common.exceptions.InvalidTaskTransitionException;
import com.chronoscore.common.exceptions.InvalidWorkflowTransitionException;
import com.chronoscore.common.exceptions.TenantConcurrencyLimitExceededException;
import com.chronoscore.common.exceptions.TenantQueueLimitExceededException;
import com.chronoscore.common.exceptions.TenantRateLimitExceededException;
import com.chronoscore.common.exceptions.WorkflowNotFoundException;
import com.chronoscore.common.exceptions.WorkflowValidationException;
import com.chronoscore.coordinator.api.dto.response.ApiErrorResponse;
import com.chronoscore.coordinator.api.filter.TraceAndCorrelationFilter;
import com.chronoscore.coordinator.persistence.exception.DuplicateTaskException;
import com.chronoscore.coordinator.persistence.exception.TaskNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Centralized exception handler mapping domain and framework exceptions
 * into uniform, structured {@link ApiErrorResponse} models with correlation and trace tracking.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private String resolveRequestId(HttpServletRequest request) {
        Object attr = request.getAttribute(TraceAndCorrelationFilter.ATTR_REQUEST_ID);
        if (attr != null) {
            return attr.toString();
        }
        String header = request.getHeader(TraceAndCorrelationFilter.HEADER_REQUEST_ID);
        return (header != null && !header.isBlank()) ? header.trim() : "req-unknown";
    }

    private String resolveTraceId(HttpServletRequest request) {
        Object attr = request.getAttribute(TraceAndCorrelationFilter.ATTR_TRACE_ID);
        if (attr != null) {
            return attr.toString();
        }
        String header = request.getHeader(TraceAndCorrelationFilter.HEADER_TRACE_ID);
        return (header != null && !header.isBlank()) ? header.trim() : null;
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(HttpStatus status, String code, String message, HttpServletRequest request, List<String> details) {
        String reqId = resolveRequestId(request);
        String traceId = resolveTraceId(request);
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(status.value(), status.getReasonPhrase(), code, message, request.getRequestURI(), reqId, traceId, details));
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(HttpStatus status, String code, String message, HttpServletRequest request) {
        return buildResponse(status, code, message, request, Collections.emptyList());
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleTaskNotFound(TaskNotFoundException ex, HttpServletRequest request) {
        log.warn("Task not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkflowNotFound(WorkflowNotFoundException ex, HttpServletRequest request) {
        log.warn("Workflow not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "WORKFLOW_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.add(String.format("Field '%s': %s", fieldError.getField(), fieldError.getDefaultMessage()));
        }
        log.warn("Input validation failed on {}: {}", request.getRequestURI(), details);
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, details);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        log.warn("Handler method validation failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request);
    }

    @ExceptionHandler(WorkflowValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkflowValidation(WorkflowValidationException ex, HttpServletRequest request) {
        log.warn("DAG validation failed: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_DAG", ex.getMessage(), request, List.of(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Invalid argument on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidTaskTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidTaskTransition(InvalidTaskTransitionException ex, HttpServletRequest request) {
        log.warn("Invalid task state transition: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidWorkflowTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidWorkflowTransition(InvalidWorkflowTransitionException ex, HttpServletRequest request) {
        log.warn("Invalid workflow transition: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateTaskException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateTask(DuplicateTaskException ex, HttpServletRequest request) {
        log.warn("Duplicate task collision: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "DUPLICATE_TASK", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.warn("Illegal state: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "INVALID_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(TenantQueueLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleQueueLimit(TenantQueueLimitExceededException ex, HttpServletRequest request) {
        log.warn("Tenant queue limit exceeded: {}", ex.getMessage());
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, "TENANT_QUEUE_LIMIT_EXCEEDED", ex.getMessage(), request);
    }

    @ExceptionHandler(TenantConcurrencyLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleConcurrencyLimit(TenantConcurrencyLimitExceededException ex, HttpServletRequest request) {
        log.warn("Tenant concurrency limit exceeded: {}", ex.getMessage());
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, "TENANT_CONCURRENCY_LIMIT_EXCEEDED", ex.getMessage(), request);
    }

    @ExceptionHandler(TenantRateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimit(TenantRateLimitExceededException ex, HttpServletRequest request) {
        log.warn("Tenant rate limit exceeded: {}", ex.getMessage());
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        log.warn("Authentication failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", ex.getMessage(), request);
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(org.springframework.web.server.ResponseStatusException ex, HttpServletRequest request) {
        log.warn("Response status exception on {}: {}", request.getRequestURI(), ex.getReason());
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        String code = (status == HttpStatus.NOT_FOUND) ? "NOT_FOUND" : "REQUEST_FAILED";
        return buildResponse(status, code, reason, request);
    }

    @ExceptionHandler(com.chronoscore.ai.policy.StaleActionStateException.class)
    public ResponseEntity<ApiErrorResponse> handleStaleActionState(com.chronoscore.ai.policy.StaleActionStateException ex, HttpServletRequest request) {
        log.warn("Stale AI action state on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "STALE_ACTION_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(com.chronoscore.ai.policy.AiActionExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleAiActionExpired(com.chronoscore.ai.policy.AiActionExpiredException ex, HttpServletRequest request) {
        log.warn("Expired AI action on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.GONE, "AI_ACTION_EXPIRED", ex.getMessage(), request);
    }

    @ExceptionHandler(com.chronoscore.ai.provider.AiProviderException.class)
    public ResponseEntity<ApiErrorResponse> handleAiProviderException(com.chronoscore.ai.provider.AiProviderException ex, HttpServletRequest request) {
        log.warn("AI Provider failure on {}: {}", request.getRequestURI(), ex.getMessage());
        int statusCode = (ex.getStatusCode() >= 400 && ex.getStatusCode() < 600) ? ex.getStatusCode() : HttpStatus.BAD_GATEWAY.value();
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) status = HttpStatus.BAD_GATEWAY;
        return buildResponse(status, "AI_PROVIDER_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("Resource not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", "Endpoint not found: " + request.getRequestURI(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled internal server error on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
    }
}
