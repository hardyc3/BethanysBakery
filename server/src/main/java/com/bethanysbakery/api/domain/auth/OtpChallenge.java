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
@Table(name = "otp_challenge")
public class OtpChallenge {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OtpChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OtpPurpose purpose;

    @Column(name = "destination_hash", nullable = false)
    private String destinationHash;

    @Column(name = "destination_encrypted", nullable = false)
    private String destinationEncrypted;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OtpChallenge() {}

    public OtpChallenge(UUID id, OtpChannel channel, OtpPurpose purpose, String destinationHash,
            String destinationEncrypted, String codeHash, int maxAttempts, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.channel = channel;
        this.purpose = purpose;
        this.destinationHash = destinationHash;
        this.destinationEncrypted = destinationEncrypted;
        this.codeHash = codeHash;
        this.attempts = 0;
        this.maxAttempts = maxAttempts;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public OtpChannel getChannel() { return channel; }
    public OtpPurpose getPurpose() { return purpose; }
    public String getDestinationHash() { return destinationHash; }
    public String getDestinationEncrypted() { return destinationEncrypted; }
    public String getCodeHash() { return codeHash; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isConsumed() { return consumedAt != null; }
    public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }
    public boolean isLocked() { return attempts >= maxAttempts; }
    public void incrementAttempts() { this.attempts++; }
    public void consume(Instant now) { this.consumedAt = now; }
}
