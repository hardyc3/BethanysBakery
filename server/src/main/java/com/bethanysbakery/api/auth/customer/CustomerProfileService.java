package com.bethanysbakery.api.auth.customer;

import com.bethanysbakery.api.api.auth.AuthApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.auth.otp.OtpService;
import com.bethanysbakery.api.auth.session.SessionService;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.auth.OtpChannel;
import com.bethanysbakery.api.domain.auth.OtpPurpose;
import com.bethanysbakery.api.domain.auth.SubjectType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerProfileService {
    private final CustomerRepository customers;
    private final OtpService otpService;
    private final SessionService sessions;
    private final AuditService audit;

    public CustomerProfileService(CustomerRepository customers, OtpService otpService, SessionService sessions,
            AuditService audit) {
        this.customers = customers;
        this.otpService = otpService;
        this.sessions = sessions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Customer get(UUID customerId) {
        return customers.findById(customerId)
            .orElseThrow(() -> AuthException.unauthorized("Customer not found"));
    }

    @Transactional
    public Customer updateName(UUID customerId, String name) {
        if (name == null || name.isBlank()) {
            throw AuthException.badRequest("VALIDATION_ERROR", "name is required");
        }
        Customer customer = get(customerId);
        String previous = customer.getName();
        customer.setName(name.trim());
        customers.save(customer);
        audit.record(ActorType.CUSTOMER, customerId, "CUSTOMER_PROFILE_UPDATED", "CUSTOMER", customerId,
            Map.of("name", previous == null ? "" : previous), Map.of("name", customer.getName()));
        return customer;
    }

    @Transactional
    public OtpService.OtpRequest requestContactVerification(UUID customerId, OtpChannel channel, String destination,
            String ip) {
        get(customerId);
        return otpService.requestContactVerification(channel, destination, customerId, ip);
    }

    @Transactional(noRollbackFor = AuthException.class)
    public Customer confirmContactVerification(UUID customerId, UUID sessionFamilyId, UUID challengeId, String code,
            String ip) {
        OtpService.VerifiedOtp verified = otpService.verify(challengeId, code, OtpPurpose.CONTACT_VERIFY, ip);
        if (verified.customerId() == null || !verified.customerId().equals(customerId)) {
            throw AuthException.badRequest("OTP_INVALID", "Invalid or expired code");
        }
        Customer customer = get(customerId);
        Instant now = Instant.now();
        if (verified.challenge().getChannel() == OtpChannel.EMAIL) {
            if (customers.existsByEmailAndIdNotAndStatus(verified.destination(), customerId, CustomerStatus.ACTIVE)) {
                throw AuthException.badRequest("CONTACT_IN_USE", "Destination already in use");
            }
            customer.setEmail(verified.destination());
            customer.markEmailVerified(now);
        } else {
            if (customers.existsByPhoneE164AndIdNotAndStatus(verified.destination(), customerId, CustomerStatus.ACTIVE)) {
                throw AuthException.badRequest("CONTACT_IN_USE", "Destination already in use");
            }
            customer.setPhoneE164(verified.destination());
            customer.markPhoneVerified(now);
        }
        customers.save(customer);
        audit.record(ActorType.CUSTOMER, customerId, "CUSTOMER_CONTACT_CHANGED", "CUSTOMER", customerId, null,
            Map.of("channel", verified.challenge().getChannel().name(), "verified", true));
        sessions.revokeOtherFamilies(SubjectType.CUSTOMER, customerId, sessionFamilyId);
        return customer;
    }

    public AuthApi.Customer toApi(Customer customer) {
        return CustomerMapper.toApi(customer);
    }
}
