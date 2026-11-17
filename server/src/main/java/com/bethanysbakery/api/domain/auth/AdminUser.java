package com.bethanysbakery.api.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "admin_user")
public class AdminUser {
    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "citext")
    private String email;

    @Column(nullable = false)
    private String name;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "totp_secret_encrypted", nullable = false)
    private String totpSecretEncrypted;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recovery_codes_hash", nullable = false, columnDefinition = "jsonb")
    private List<String> recoveryCodesHash = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdminUserStatus status;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminUser() {}

    public AdminUser(UUID id, String email, String name, String passwordHash, String totpSecretEncrypted,
            List<String> recoveryCodesHash, AdminUserStatus status, Instant now) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.totpSecretEncrypted = totpSecretEncrypted;
        this.totpEnabled = true;
        this.recoveryCodesHash = new ArrayList<>(recoveryCodesHash);
        this.status = status;
        this.failedLoginCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getPasswordHash() { return passwordHash; }
    public String getTotpSecretEncrypted() { return totpSecretEncrypted; }
    public boolean isTotpEnabled() { return totpEnabled; }
    public List<String> getRecoveryCodesHash() { return recoveryCodesHash; }
    public AdminUserStatus getStatus() { return status; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setRecoveryCodesHash(List<String> recoveryCodesHash) {
        this.recoveryCodesHash = new ArrayList<>(recoveryCodesHash);
        this.updatedAt = Instant.now();
    }
    public void setFailedLoginCount(int failedLoginCount) { this.failedLoginCount = failedLoginCount; this.updatedAt = Instant.now(); }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; this.updatedAt = Instant.now(); }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; this.updatedAt = Instant.now(); }
    public void recordSuccessfulLogin(Instant now) {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.lastLoginAt = now;
        this.updatedAt = now;
    }
}
