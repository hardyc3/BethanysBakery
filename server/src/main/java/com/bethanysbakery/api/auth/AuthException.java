package com.bethanysbakery.api.auth;

import org.springframework.http.HttpStatus;

public class AuthException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Long retryAfterSeconds;

    public AuthException(HttpStatus status, String code, String detail) {
        this(status, code, detail, null);
    }

    public AuthException(HttpStatus status, String code, String detail, Long retryAfterSeconds) {
        super(detail);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }

    public static AuthException unauthorized(String detail) {
        return new AuthException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", detail);
    }

    public static AuthException badRequest(String code, String detail) {
        return new AuthException(HttpStatus.BAD_REQUEST, code, detail);
    }

    public static AuthException rateLimited(long retryAfterSeconds) {
        return new AuthException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Rate limit exceeded", retryAfterSeconds);
    }

    public static AuthException notConfigured(String detail) {
        return new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", detail);
    }
}
