package com.bethanysbakery.api.security;

import com.bethanysbakery.api.auth.session.SecurityProblemWriter;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.config.EdgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces Cloudflare country and Origin checks on the customer API before auth runs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class EdgeRequestFilter extends OncePerRequestFilter {

    private final EdgeProperties edge;
    private final BakeryProperties bakery;
    private final ObjectMapper mapper;

    public EdgeRequestFilter(EdgeProperties edge, BakeryProperties bakery, ObjectMapper mapper) {
        this.edge = edge;
        this.bakery = bakery;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean customerApi = path.startsWith("/api/v1/") && !path.startsWith("/api/v1/webhooks/");
        if (edge.cloudflareProxy()) {
            request.setAttribute("bakery.edge.trusted", Boolean.TRUE);
            if (customerApi) {
                String country = request.getHeader("CF-IPCountry");
                if (country == null || !"US".equalsIgnoreCase(country)) {
                    SecurityProblemWriter.write(request, response, mapper, HttpStatus.FORBIDDEN,
                        "COUNTRY_BLOCKED", "Request origin is not allowed");
                    return;
                }
            }
        }
        if (customerApi) {
            String origin = request.getHeader("Origin");
            if (origin != null && !origin.equals(bakery.auth().appPublicBaseUrl())) {
                SecurityProblemWriter.write(request, response, mapper, HttpStatus.FORBIDDEN,
                    "FORBIDDEN", "Request origin is not allowed");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
