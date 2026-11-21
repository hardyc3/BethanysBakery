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
@Table(name = "admin_device")
public class AdminDevice {
    @Id
    private UUID id;

    @Column(name = "admin_user_id", nullable = false)
    private UUID adminUserId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DevicePlatform platform;

    @Column(name = "public_key_pem", nullable = false)
    private String publicKeyPem;

    @Column(name = "cert_fingerprint_sha256", nullable = false, unique = true)
    private String certFingerprintSha256;

    @Column(name = "api_key_hash", nullable = false, unique = true)
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdminDeviceStatus status;

    @Column(name = "paired_at", nullable = false)
    private Instant pairedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "app_version")
    private String appVersion;

    @Column(name = "fcm_token")
    private String fcmToken;

    @Column(name = "fcm_updated_at")
    private Instant fcmUpdatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AdminDevice() {}

    public AdminDevice(UUID id, UUID adminUserId, String name, DevicePlatform platform, String publicKeyPem,
            String certFingerprintSha256, String apiKeyHash, Instant now, String appVersion) {
        this.id = id;
        this.adminUserId = adminUserId;
        this.name = name;
        this.platform = platform;
        this.publicKeyPem = publicKeyPem;
        this.certFingerprintSha256 = certFingerprintSha256;
        this.apiKeyHash = apiKeyHash;
        this.status = AdminDeviceStatus.ACTIVE;
        this.pairedAt = now;
        this.appVersion = appVersion;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getAdminUserId() { return adminUserId; }
    public String getName() { return name; }
    public DevicePlatform getPlatform() { return platform; }
    public String getPublicKeyPem() { return publicKeyPem; }
    public String getCertFingerprintSha256() { return certFingerprintSha256; }
    public String getApiKeyHash() { return apiKeyHash; }
    public AdminDeviceStatus getStatus() { return status; }
    public Instant getPairedAt() { return pairedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getAppVersion() { return appVersion; }
    public String getFcmToken() { return fcmToken; }
    public Instant getFcmUpdatedAt() { return fcmUpdatedAt; }

    public void setPushToken(String token, Instant now) {
        this.fcmToken = token;
        this.fcmUpdatedAt = now;
        this.updatedAt = now;
    }

    public void clearPushToken(Instant now) {
        this.fcmToken = null;
        this.fcmUpdatedAt = now;
        this.updatedAt = now;
    }

    public void revoke(Instant now) {
        this.status = AdminDeviceStatus.REVOKED;
        this.revokedAt = now;
        this.fcmToken = null;
        this.fcmUpdatedAt = now;
        this.updatedAt = now;
    }

    public void seen(Instant now, String appVersion) {
        this.lastSeenAt = now;
        if (appVersion != null && !appVersion.isBlank()) this.appVersion = appVersion;
        this.updatedAt = now;
    }
}
