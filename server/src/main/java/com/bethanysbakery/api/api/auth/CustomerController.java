package com.bethanysbakery.api.api.auth;

import com.bethanysbakery.api.auth.customer.CustomerPrincipal;
import com.bethanysbakery.api.auth.customer.CustomerProfileService;
import com.bethanysbakery.api.auth.otp.OtpService;
import com.bethanysbakery.api.common.web.ClientIps;
import com.bethanysbakery.api.config.BakeryProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class CustomerController {
    private final CustomerProfileService service;
    private final BakeryProperties.Auth config;

    public CustomerController(CustomerProfileService service, BakeryProperties properties) {
        this.service = service;
        this.config = properties.auth();
    }

    @GetMapping
    public AuthApi.Customer get(@AuthenticationPrincipal CustomerPrincipal principal) {
        return service.toApi(service.get(principal.customerId()));
    }

    @PatchMapping
    public AuthApi.Customer update(@AuthenticationPrincipal CustomerPrincipal principal,
            @Valid @RequestBody AuthApi.CustomerProfileUpdate request) {
        return service.toApi(service.updateName(principal.customerId(), request.name()));
    }

    @PostMapping("/contact-verification/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AuthApi.OtpRequestResult requestContactVerification(@AuthenticationPrincipal CustomerPrincipal principal,
            @Valid @RequestBody AuthApi.ContactVerificationRequest request, HttpServletRequest httpRequest) {
        OtpService.OtpRequest result = service.requestContactVerification(
            principal.customerId(), request.channel(), request.destination(), ClientIps.from(httpRequest)
        );
        return new AuthApi.OtpRequestResult(
            result.challenge().getId(), result.channel(), result.destinationMasked(),
            result.challenge().getExpiresAt(), config.otpResendSeconds()
        );
    }

    @PostMapping("/contact-verification/confirm")
    public AuthApi.Customer confirmContactVerification(@AuthenticationPrincipal CustomerPrincipal principal,
            @Valid @RequestBody AuthApi.OtpVerifyRequest request, HttpServletRequest httpRequest) {
        return service.toApi(service.confirmContactVerification(
            principal.customerId(), principal.sessionId(), request.challengeId(), request.code(),
            ClientIps.from(httpRequest)
        ));
    }
}
