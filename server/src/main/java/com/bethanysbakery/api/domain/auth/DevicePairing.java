package com.bethanysbakery.api.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_pairing")
public class DevicePairing {
    @Id
    private UUID id;

    @Column(name = "code_hash", nullable = false, unique = true)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DevicePairing() {}

    public DevicePairing(UUID id, String codeHash, Instant expiresAt, String createdBy, Instant createdAt) {
        this.id = id;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getCodeHash() { return codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isUsed() { return usedAt != null; }
    public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }
    public void markUsed(Instant now) { this.usedAt = now; }
}
