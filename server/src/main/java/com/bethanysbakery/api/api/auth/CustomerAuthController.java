package com.bethanysbakery.api.api.auth;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.customer.CustomerAuthService;
import com.bethanysbakery.api.auth.customer.CustomerPrincipal;
import com.bethanysbakery.api.auth.session.CookieService;
import com.bethanysbakery.api.common.web.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class CustomerAuthController {
    private final CustomerAuthService service;
    private final CookieService cookies;

    public CustomerAuthController(CustomerAuthService service, CookieService cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @PostMapping("/otp/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AuthApi.OtpRequestResult requestOtp(@Valid @RequestBody AuthApi.OtpRequest request,
            HttpServletRequest httpRequest) {
        return service.requestOtp(request, ClientIps.from(httpRequest));
    }

    @PostMapping("/otp/verify")
    public AuthApi.CustomerSession verifyOtp(@Valid @RequestBody AuthApi.OtpVerifyRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return service.verifyOtp(request, ClientIps.from(httpRequest), httpRequest, httpResponse);
    }

    @PostMapping("/refresh")
    public AuthApi.CustomerSession refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String refreshToken = cookies.read(httpRequest, CookieService.REFRESH_COOKIE);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw AuthException.unauthorized("Refresh token missing");
        }
        return service.refresh(refreshToken, httpRequest, httpResponse, ClientIps.from(httpRequest));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal CustomerPrincipal principal, HttpServletResponse httpResponse) {
        service.logout(principal, httpResponse);
    }
}
