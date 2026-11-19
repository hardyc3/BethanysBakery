package com.bethanysbakery.api.api.auth;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.RateLimiter;
import com.bethanysbakery.api.auth.admin.AdminAuthService;
import com.bethanysbakery.api.auth.admin.AdminPrincipal;
import com.bethanysbakery.api.auth.session.RequestAuthenticationFilter;
import com.bethanysbakery.api.common.web.ClientIps;
import com.bethanysbakery.api.domain.auth.AdminDevice;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1")
public class AdminAuthController {
    private static final int LOGIN_LIMIT = 10;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);

    private final AdminAuthService service;
    private final RateLimiter rateLimiter;

    public AdminAuthController(AdminAuthService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/auth/login")
    public AuthApi.AdminSession login(@Valid @RequestBody AuthApi.AdminLoginRequest request,
            @RequestAttribute(RequestAuthenticationFilter.DEVICE_ATTRIBUTE) AdminDevice device,
            HttpServletRequest httpRequest) {
        String ip = ClientIps.from(httpRequest);
        RateLimiter.Result limit = rateLimiter.tryAcquire("admin-login-ip:" + ip, LOGIN_LIMIT, LOGIN_WINDOW);
        if (!limit.allowed()) {
            throw AuthException.rateLimited(limit.retryAfterSeconds());
        }
        return service.login(request, device.getId(), httpRequest.getHeader("User-Agent"), ip);
    }

    @PostMapping("/auth/refresh")
    public AuthApi.AdminSession refresh(@Valid @RequestBody AuthApi.RefreshRequest request,
            @RequestAttribute(RequestAuthenticationFilter.DEVICE_ATTRIBUTE) AdminDevice device,
            HttpServletRequest httpRequest) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw AuthException.unauthorized("Refresh token missing");
        }
        return service.refresh(request.refreshToken(), device.getId(), httpRequest.getHeader("User-Agent"),
            ClientIps.from(httpRequest));
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AdminPrincipal principal) {
        service.logout(principal);
    }

    @GetMapping("/me")
    public AuthApi.AdminProfile me(@AuthenticationPrincipal AdminPrincipal principal) {
        return service.profile(principal);
    }
}
