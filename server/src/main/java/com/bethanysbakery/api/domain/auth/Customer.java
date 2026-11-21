package com.bethanysbakery.api.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer")
public class Customer {
    @Id
    private UUID id;

    private String name;

    @Column(columnDefinition = "citext")
    private String email;

    @Column(name = "phone_e164")
    private String phoneE164;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @Column(name = "sms_consent_at")
    private Instant smsConsentAt;

    @Column(name = "sms_opt_out_at")
    private Instant smsOptOutAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerStatus status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {}

    public Customer(UUID id, String name, String email, String phoneE164, CustomerStatus status, Instant now) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.phoneE164 = phoneE164;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhoneE164() { return phoneE164; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public Instant getPhoneVerifiedAt() { return phoneVerifiedAt; }
    public Instant getSmsConsentAt() { return smsConsentAt; }
    public Instant getSmsOptOutAt() { return smsOptOutAt; }
    public CustomerStatus getStatus() { return status; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean isEmailVerified() { return emailVerifiedAt != null; }
    public boolean isPhoneVerified() { return phoneVerifiedAt != null; }
    public boolean hasSmsConsent() { return smsConsentAt != null && (smsOptOutAt == null || smsOptOutAt.isBefore(smsConsentAt)); }

    public void setName(String name) { this.name = name; touch(); }
    public void setEmail(String email) { this.email = email; touch(); }
    public void setPhoneE164(String phoneE164) { this.phoneE164 = phoneE164; touch(); }
    public void markEmailVerified(Instant now) { this.emailVerifiedAt = now; touch(); }
    public void markPhoneVerified(Instant now) { this.phoneVerifiedAt = now; touch(); }
    public void setLastLoginAt(Instant now) { this.lastLoginAt = now; touch(); }

    /** Explicit re-consent is a new consent: it also lifts any prior STOP. */
    public void grantSmsConsent(Instant now) {
        this.smsConsentAt = now;
        this.smsOptOutAt = null;
        touch();
    }

    public void revokeSmsConsent(Instant now) {
        this.smsConsentAt = null;
        touch();
    }

    public void markSmsOptOut(Instant now) {
        this.smsOptOutAt = now;
        touch();
    }

    public boolean clearSmsOptOut(Instant now) {
        if (this.smsConsentAt == null) {
            return false;
        }
        this.smsOptOutAt = null;
        touch();
        return true;
    }

    private void touch() { this.updatedAt = Instant.now(); }
}
