package com.bethanysbakery.api.auth.session;

import com.bethanysbakery.api.common.error.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

public final class SecurityProblemWriter {
    private SecurityProblemWriter() {}

    public static void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper mapper,
            HttpStatus status, String code, String detail) throws IOException {
        write(request, response, mapper, status, code, detail, null);
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper mapper,
            HttpStatus status, String code, String detail, Long retryAfterSeconds) throws IOException {
        Problem body = new Problem(
            "about:blank",
            status.getReasonPhrase(),
            status.value(),
            detail,
            request.getRequestURI(),
            code,
            MDC.get("requestId"),
            null
        );
        response.setStatus(status.value());
        if (retryAfterSeconds != null) {
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), body);
    }
}
