package com.bethanysbakery.api.auth.session;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.admin.AdminPrincipal;
import com.bethanysbakery.api.auth.customer.CustomerPrincipal;
import com.bethanysbakery.api.auth.device.AdminDeviceService;
import com.bethanysbakery.api.domain.auth.AdminDevice;
import com.bethanysbakery.api.domain.auth.AdminUser;
import com.bethanysbakery.api.domain.auth.AdminUserRepository;
import com.bethanysbakery.api.domain.auth.AdminUserStatus;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.auth.SubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestAuthenticationFilter extends OncePerRequestFilter {
    public static final String DEVICE_ATTRIBUTE = "auth.adminDevice";

    private final JwtService jwtService;
    private final AdminDeviceService deviceService;
    private final CsrfService csrfService;
    private final CustomerRepository customers;
    private final AdminUserRepository admins;
    private final CookieService cookies;
    private final ObjectMapper mapper;

    public RequestAuthenticationFilter(JwtService jwtService, AdminDeviceService deviceService,
            CsrfService csrfService, CustomerRepository customers, AdminUserRepository admins,
            CookieService cookies, ObjectMapper mapper) {
        this.jwtService = jwtService;
        this.deviceService = deviceService;
        this.csrfService = csrfService;
        this.customers = customers;
        this.admins = admins;
        this.cookies = cookies;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        try {
            if (path.startsWith("/admin/v1/")) {
                if (!path.equals("/admin/v1/devices/pair")) {
                    authenticateAdmin(request);
                }
            } else {
                authenticateCustomer(request);
                if (requiresCsrf(request) && !csrfService.validate(request)) {
                    SecurityProblemWriter.write(request, response, mapper, HttpStatus.FORBIDDEN, "CSRF_INVALID",
                        "CSRF token missing or invalid");
                    return;
                }
            }
        } catch (AuthException ex) {
            SecurityProblemWriter.write(request, response, mapper, ex.status(), ex.code(), ex.getMessage());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateAdmin(HttpServletRequest request) {
        AdminDevice device = deviceService.authenticate(request);
        request.setAttribute(DEVICE_ATTRIBUTE, device);

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return;
        }
        boolean bearerOptional = request.getRequestURI().equals("/admin/v1/auth/login")
            || request.getRequestURI().equals("/admin/v1/auth/refresh")
            || request.getRequestURI().equals("/admin/v1/app-info");
        JwtService.AccessClaims claims;
        try {
            claims = jwtService.parse(authorization.substring(7), SubjectType.ADMIN);
        } catch (AuthException ex) {
            // These endpoints only need the device; a stale app token must not block them.
            if (bearerOptional) {
                return;
            }
            throw ex;
        }
        if (claims.deviceId() == null || !claims.deviceId().equals(device.getId())) {
            throw AuthException.unauthorized("Token is not valid for this device");
        }
        Optional<AdminUser> admin = admins.findById(claims.subjectId());
        if (admin.isEmpty() || admin.get().getStatus() != AdminUserStatus.ACTIVE) {
            throw AuthException.unauthorized("Invalid token");
        }
        AdminPrincipal principal = new AdminPrincipal(
            admin.get().getId(), admin.get().getEmail(), admin.get().getName(), device.getId(), claims.sessionId()
        );
        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void authenticateCustomer(HttpServletRequest request) {
        String accessToken = cookies.read(request, CookieService.ACCESS_COOKIE);
        if (accessToken == null) {
            return;
        }
        try {
            JwtService.AccessClaims claims = jwtService.parse(accessToken, SubjectType.CUSTOMER);
            Optional<Customer> customer = customers.findById(claims.subjectId());
            if (customer.isEmpty() || customer.get().getStatus() != CustomerStatus.ACTIVE) {
                return;
            }
            CustomerPrincipal principal = new CustomerPrincipal(claims.subjectId(), claims.sessionId());
            var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (AuthException ignored) {
            // Invalid or expired cookie: continue as anonymous. Authorization returns 401 if required.
        }
    }

    private static boolean requiresCsrf(HttpServletRequest request) {
        String method = request.getMethod();
        if (!List.of("POST", "PUT", "PATCH", "DELETE").contains(method)) {
            return false;
        }
        String path = request.getRequestURI();
        // Refresh is cookie-scoped to /api/v1/auth and SameSite=Lax, and the OTP endpoints are
        // pre-session by design; every other customer mutation must present the double-submit pair.
        return path.equals("/api/v1/auth/logout") || path.equals("/api/v1/me") || path.startsWith("/api/v1/me/");
    }
}
