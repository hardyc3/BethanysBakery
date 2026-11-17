package com.bethanysbakery.api.auth.customer;

import com.bethanysbakery.api.api.auth.AuthApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.auth.otp.OtpService;
import com.bethanysbakery.api.auth.session.CookieService;
import com.bethanysbakery.api.auth.session.SessionService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.auth.OtpChannel;
import com.bethanysbakery.api.domain.auth.OtpPurpose;
import com.bethanysbakery.api.domain.auth.SubjectType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerAuthService {
    private final CustomerRepository customers;
    private final OtpService otpService;
    private final SessionService sessions;
    private final CookieService cookies;
    private final AuditService audit;
    private final BakeryProperties.Auth config;

    public CustomerAuthService(CustomerRepository customers, OtpService otpService, SessionService sessions,
            CookieService cookies, AuditService audit, BakeryProperties properties) {
        this.customers = customers;
        this.otpService = otpService;
        this.sessions = sessions;
        this.cookies = cookies;
        this.audit = audit;
        this.config = properties.auth();
    }

    @Transactional
    public AuthApi.OtpRequestResult requestOtp(AuthApi.OtpRequest request, String ip) {
        OtpService.OtpRequest result = otpService.requestLogin(request.channel(), request.destination(), ip);
        return new AuthApi.OtpRequestResult(
            result.challenge().getId(), result.channel(), result.destinationMasked(),
            result.challenge().getExpiresAt(), config.otpResendSeconds()
        );
    }

    @Transactional(noRollbackFor = AuthException.class)
    public AuthApi.CustomerSession verifyOtp(AuthApi.OtpVerifyRequest request, String ip, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        OtpService.VerifiedOtp verified = otpService.verify(request.challengeId(), request.code(), OtpPurpose.LOGIN, ip);
        OtpChannel channel = verified.challenge().getChannel();
        Customer customer = findCustomer(channel, verified.destination()).orElse(null);
        Instant now = Instant.now();
        if (customer == null) {
            customer = new Customer(Uuids.v7(), null, null, null, CustomerStatus.ACTIVE, now);
            if (channel == OtpChannel.EMAIL) {
                customer.setEmail(verified.destination());
            } else {
                customer.setPhoneE164(verified.destination());
            }
        }
        if (customer.getStatus() == CustomerStatus.DISABLED) {
            throw AuthException.unauthorized("Invalid code");
        }
        if (channel == OtpChannel.EMAIL) {
            customer.setEmail(verified.destination());
            customer.markEmailVerified(now);
        } else {
            customer.setPhoneE164(verified.destination());
            customer.markPhoneVerified(now);
        }
        customer.setLastLoginAt(now);
        customers.save(customer);

        SessionService.SessionTokens tokens = sessions.create(
            SubjectType.CUSTOMER, customer.getId(), null, httpRequest.getHeader("User-Agent"), ip
        );
        cookies.setCustomerCookies(response, tokens);
        audit.record(ActorType.CUSTOMER, customer.getId(), "CUSTOMER_LOGIN", "CUSTOMER", customer.getId(), null,
            Map.of("channel", channel.name()));
        return new AuthApi.CustomerSession(CustomerMapper.toApi(customer));
    }

    @Transactional(noRollbackFor = AuthException.class)
    public AuthApi.CustomerSession refresh(String rawRefreshToken, HttpServletRequest request,
            HttpServletResponse response, String ip) {
        SessionService.SessionTokens tokens = sessions.rotate(
            SubjectType.CUSTOMER, rawRefreshToken, null, request.getHeader("User-Agent"), ip
        );
        Customer customer = customers.findById(tokens.subjectId())
            .orElseThrow(() -> AuthException.unauthorized("Invalid refresh token"));
        if (customer.getStatus() == CustomerStatus.DISABLED) {
            sessions.revokeFamily(tokens.familyId());
            throw AuthException.unauthorized("Account disabled");
        }
        cookies.setCustomerCookies(response, tokens);
        return new AuthApi.CustomerSession(CustomerMapper.toApi(customer));
    }

    @Transactional
    public void logout(CustomerPrincipal principal, HttpServletResponse response) {
        sessions.revokeFamily(principal.sessionId());
        cookies.clearCustomerCookies(response);
    }

    private Optional<Customer> findCustomer(OtpChannel channel, String destination) {
        return channel == OtpChannel.EMAIL ? customers.findByEmail(destination) : customers.findByPhoneE164(destination);
    }
}
