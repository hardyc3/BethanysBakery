package com.bethanysbakery.api.api.auth;

import com.bethanysbakery.api.domain.auth.OtpChannel;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthApi {
    private AuthApi() {}

    public record CsrfToken(String token) {}

    public record OtpRequest(@NotNull OtpChannel channel, @NotBlank String destination) {}

    public record OtpRequestResult(UUID challengeId, OtpChannel channel, String destinationMasked,
            Instant expiresAt, int resendAfterSeconds) {}

    public record OtpVerifyRequest(@NotNull UUID challengeId,
            @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be 6 digits") String code) {}

    public record Customer(UUID id, String name, String email, String phone, boolean emailVerified,
            boolean phoneVerified, boolean smsConsent, Instant createdAt) {}

    public record CustomerSession(Customer customer) {}

    public record CustomerProfileUpdate(@Size(max = 120) String name) {}

    public record ContactVerificationRequest(@NotNull OtpChannel channel, @NotBlank String destination) {}

    public record PairDeviceRequest(@NotBlank String pairingCode, @NotBlank @Size(max = 80) String deviceName,
            @NotBlank String publicKeyPem, @NotBlank String csrPem, String appVersion) {}

    public record PairDeviceResponse(UUID deviceId, String apiKey, String clientCertificatePem,
            String caCertificatePem, Instant certificateExpiresAt) {}

    public record AdminLoginRequest(@NotBlank @Email String email, @NotBlank String password, String totpCode,
            String recoveryCode, String appVersion) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record AdminSession(String accessToken, String refreshToken, long expiresIn, AdminProfile admin) {}

    public record AdminProfile(UUID id, String email, String name) {}

    public record AdminDevice(UUID id, String name, String platform, String status, Instant pairedAt,
            Instant lastSeenAt, Instant revokedAt, String appVersion) {}

    public record AuditEventView(UUID id, String actorType, UUID actorId, String action, String entityType,
            UUID entityId, Object before, Object after, String requestId, Instant createdAt) {}

    public record AdminAppInfo(String latestVersion, String minVersion, String downloadUrl, String releaseNotes) {}
}
