package com.bethanysbakery.api.common.error;

import com.bethanysbakery.api.auth.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<Problem.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(this::toFieldError)
            .toList();
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed",
            "VALIDATION_ERROR", request, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Problem> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<Problem.FieldError> errors = ex.getConstraintViolations().stream()
            .map(v -> new Problem.FieldError(v.getPropertyPath().toString(), v.getMessage()))
            .toList();
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed",
            "VALIDATION_ERROR", request, errors);
    }

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<Problem> handleRequestValidation(RequestValidationException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed",
            "VALIDATION_ERROR", request, ex.errors());
    }

    @ExceptionHandler(PublishValidationException.class)
    ResponseEntity<Problem> handlePublishValidation(PublishValidationException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Publish validation failed", "Menu cannot be published",
            "PUBLISH_VALIDATION_FAILED", request, ex.errors());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Problem> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large", "Upload exceeds the 10 MB limit",
            "PAYLOAD_TOO_LARGE", request, null);
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Problem> handleMalformedMultipart(MultipartException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "Malformed multipart request",
            "VALIDATION_ERROR", request, null);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<Problem> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed",
            "VALIDATION_ERROR", request,
            List.of(new Problem.FieldError(ex.getRequestPartName(), "is required")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", "Malformed request body",
            "VALIDATION_ERROR", request, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Problem> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed",
            "VALIDATION_ERROR", request,
            List.of(new Problem.FieldError(ex.getName(), "has an invalid value")));
    }

    @ExceptionHandler({NoResourceFoundException.class})
    ResponseEntity<Problem> handleNotFound(Exception ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", "Resource not found",
            "NOT_FOUND", request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Problem> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", "HTTP method not supported",
            "METHOD_NOT_ALLOWED", request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Problem> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", "Content type not supported",
            "UNSUPPORTED_MEDIA_TYPE", request, null);
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Problem> handleAuth(AuthException ex, HttpServletRequest request) {
        Problem body = problemBody(ex.status(), ex.status().getReasonPhrase(), ex.getMessage(), ex.code(), request, null);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (ex.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return noStoreForPublicReads(builder, request).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Problem> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String code = status == HttpStatus.TOO_MANY_REQUESTS ? "RATE_LIMITED" : "INTERNAL_ERROR";
        String title = status == HttpStatus.TOO_MANY_REQUESTS ? "Too many requests" : "Error";
        String detail = status == HttpStatus.TOO_MANY_REQUESTS ? "Rate limit exceeded" : "Request failed";
        if (status.is4xxClientError() && status != HttpStatus.TOO_MANY_REQUESTS) {
            code = switch (status) {
                case NOT_FOUND -> "NOT_FOUND";
                case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
                case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
                case BAD_REQUEST -> "VALIDATION_ERROR";
                default -> "VALIDATION_ERROR";
            };
            title = status.getReasonPhrase();
            detail = ex.getReason() != null ? "Request failed" : status.getReasonPhrase();
        }
        return problem(status, title, detail, code, request, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "An unexpected error occurred",
            "INTERNAL_ERROR", request, null);
    }

    private Problem.FieldError toFieldError(FieldError fe) {
        String message = fe.getDefaultMessage() == null ? "Invalid value" : fe.getDefaultMessage();
        return new Problem.FieldError(fe.getField(), message);
    }

    private ResponseEntity<Problem> problem(HttpStatus status, String title, String detail, String code,
            HttpServletRequest request, List<Problem.FieldError> errors) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        return noStoreForPublicReads(builder, request)
            .body(problemBody(status, title, detail, code, request, errors));
    }

    /** Availability must never be cached by a shared cache, including error responses on public reads. */
    private ResponseEntity.BodyBuilder noStoreForPublicReads(ResponseEntity.BodyBuilder builder,
            HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/v1/menu-dates") || uri.startsWith("/api/v1/offerings")) {
            builder.header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        }
        return builder;
    }

    private Problem problemBody(HttpStatus status, String title, String detail, String code,
            HttpServletRequest request, List<Problem.FieldError> errors) {
        String requestId = MDC.get("requestId");
        return new Problem(
            "about:blank",
            title,
            status.value(),
            detail,
            request.getRequestURI(),
            code,
            requestId,
            errors == null || errors.isEmpty() ? null : errors
        );
    }
}
