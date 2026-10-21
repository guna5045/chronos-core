package com.chronoscore.coordinator.api.advice;

import com.chronoscore.common.exceptions.InvalidTaskTransitionException;
import com.chronoscore.common.exceptions.InvalidWorkflowTransitionException;
import com.chronoscore.common.exceptions.TenantConcurrencyLimitExceededException;
import com.chronoscore.common.exceptions.TenantQueueLimitExceededException;
import com.chronoscore.common.exceptions.TenantRateLimitExceededException;
import com.chronoscore.common.exceptions.WorkflowNotFoundException;
import com.chronoscore.common.exceptions.WorkflowValidationException;
import com.chronoscore.coordinator.api.dto.response.ApiErrorResponse;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized exception handler mapping domain and framework exceptions
 * into uniform, structured {@link ApiErrorResponse} models.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleTaskNotFound(TaskNotFoundException ex, HttpServletRequest request) {
        log.warn("Task not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(HttpStatus.NOT_FOUND.value(), "Not Found", "TASK_NOT_FOUND", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkflowNotFound(WorkflowNotFoundException ex, HttpServletRequest request) {
        log.warn("Workflow not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(HttpStatus.NOT_FOUND.value(), "Not Found", "WORKFLOW_NOT_FOUND", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.add(String.format("Field '%s': %s", fieldError.getField(), fieldError.getDefaultMessage()));
        }
        log.warn("Input validation failed on {}: {}", request.getRequestURI(), details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", "VALIDATION_FAILED", "Request validation failed", request.getRequestURI(), details));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        log.warn("Handler method validation failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", "VALIDATION_FAILED", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(WorkflowValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkflowValidation(WorkflowValidationException ex, HttpServletRequest request) {
        log.warn("DAG validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", "INVALID_DAG", ex.getMessage(), request.getRequestURI(), List.of(ex.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Invalid argument on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", "INVALID_ARGUMENT", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidTaskTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidTaskTransition(InvalidTaskTransitionException ex, HttpServletRequest request) {
        log.warn("Invalid task state transition: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(HttpStatus.CONFLICT.value(), "Conflict", "INVALID_STATE_TRANSITION", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidWorkflowTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidWorkflowTransition(InvalidWorkflowTransitionException ex, HttpServletRequest request) {
        log.warn("Invalid workflow transition: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(HttpStatus.CONFLICT.value(), "Conflict", "INVALID_STATE_TRANSITION", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(DuplicateTaskException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateTask(DuplicateTaskException ex, HttpServletRequest request) {
        log.warn("Duplicate task collision: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(HttpStatus.CONFLICT.value(), "Conflict", "DUPLICATE_TASK", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.warn("Illegal state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(HttpStatus.CONFLICT.value(), "Conflict", "INVALID_STATE", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(TenantQueueLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleQueueLimit(TenantQueueLimitExceededException ex, HttpServletRequest request) {
        log.warn("Tenant queue limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", "TENANT_QUEUE_LIMIT_EXCEEDED", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(TenantConcurrencyLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleConcurrencyLimit(TenantConcurrencyLimitExceededException ex, HttpServletRequest request) {
        log.warn("Tenant concurrency limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", "TENANT_CONCURRENCY_LIMIT_EXCEEDED", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(TenantRateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimit(TenantRateLimitExceededException ex, HttpServletRequest request) {
        log.warn("Tenant rate limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", "RATE_LIMITED", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of(HttpStatus.FORBIDDEN.value(), "Forbidden", "ACCESS_DENIED", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        log.warn("Authentication failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of(HttpStatus.UNAUTHORIZED.value(), "Unauthorized", "UNAUTHENTICATED", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(org.springframework.web.server.ResponseStatusException ex, HttpServletRequest request) {
        log.warn("Response status exception on {}: {}", request.getRequestURI(), ex.getReason());
        int statusCode = ex.getStatusCode().value();
        String reason = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        String code = (statusCode == 404) ? "NOT_FOUND" : "REQUEST_FAILED";
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiErrorResponse.of(statusCode, ex.getStatusCode().toString(), code, reason, request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled internal server error on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error", "INTERNAL_ERROR", "An unexpected error occurred", request.getRequestURI()));
    }
}
