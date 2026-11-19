package com.bethanysbakery.api.security;

import com.bethanysbakery.api.auth.session.SecurityProblemWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Caps JSON request bodies at 1 MB before deserialization; multipart uploads keep the 10 MB cap. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class JsonBodySizeFilter extends OncePerRequestFilter {

    static final int MAX_BYTES = 1024 * 1024;

    private final ObjectMapper mapper;

    public JsonBodySizeFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/") && !path.startsWith("/admin/v1/")) {
            return true;
        }
        String contentType = request.getContentType();
        if (contentType == null) {
            return true;
        }
        try {
            String subtype = MediaType.parseMediaType(contentType).getSubtype().toLowerCase(Locale.ROOT);
            return !("json".equals(subtype) || subtype.endsWith("+json"));
        } catch (InvalidMediaTypeException ex) {
            return true;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ByteArrayOutputStream buffered = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = request.getInputStream().read(chunk)) != -1) {
            buffered.write(chunk, 0, read);
            if (buffered.size() > MAX_BYTES) {
                SecurityProblemWriter.write(request, response, mapper, HttpStatus.PAYLOAD_TOO_LARGE,
                    "PAYLOAD_TOO_LARGE", "JSON body exceeds the 1 MB limit");
                return;
            }
        }
        chain.doFilter(new BufferedRequest(request, buffered.toByteArray()), response);
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return source.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Async reads are not supported for JSON bodies");
                }
                @Override public int read() { return source.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = StandardCharsets.UTF_8;
            String encoding = getCharacterEncoding();
            if (encoding != null) {
                charset = Charset.forName(encoding);
            }
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }
    }
}
