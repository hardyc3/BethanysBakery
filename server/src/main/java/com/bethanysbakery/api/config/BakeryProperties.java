package com.bethanysbakery.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bakery")
public record BakeryProperties(
    @NotBlank(message = "BAKERY_NAME must not be blank") String name,
    @NotBlank(message = "BAKERY_TIMEZONE must not be blank") String timezone,
    @NotBlank(message = "BAKERY_PICKUP_ADDRESS must not be blank") String pickupAddress,
    @NotBlank(message = "BAKERY_MAPS_URL must not be blank") String mapsUrl,
    @NotBlank(message = "BAKERY_SUPPORT_EMAIL must not be blank")
        @Email(message = "BAKERY_SUPPORT_EMAIL must be valid") String supportEmail,
    @Min(value = 0, message = "ORDER_CUTOFF_HOURS must be >= 0") Integer orderCutoffHours,
    @Valid Auth auth,
    @Valid AdminApp adminApp,
    @Valid Venmo venmo
) {
    public record Venmo(@NotBlank String handle, @NotBlank String displayName) {
        public Venmo {
            if (handle == null || handle.isBlank()) handle = "bethanysbakery";
            if (displayName == null || displayName.isBlank()) displayName = "Bethany's Bakery";
        }
    }

    public record AdminApp(String latestVersion, String minVersion, String downloadUrl, String releaseNotes) {
        public AdminApp {
            if (latestVersion == null || latestVersion.isBlank()) latestVersion = "1.0.0";
            if (minVersion == null || minVersion.isBlank()) minVersion = "1.0.0";
            if (downloadUrl == null || downloadUrl.isBlank()) downloadUrl = "https://example.com/admin-app.apk";
        }
    }

    public record Auth(
        @NotBlank(message = "JWT_SECRET must be configured") String jwtSecret,
        @NotBlank(message = "APP_ENCRYPTION_KEY must be configured") String appEncryptionKey,
        String adminDeviceCaCertPath,
        String adminDeviceCaKeyPath,
        @NotBlank String adminMtlsHeader,
        @NotBlank String adminMtlsVerifiedHeader,
        Boolean adminMtlsLocalBypass,
        Duration adminAccessTtl,
        Duration adminRefreshTtl,
        Duration customerAccessTtl,
        Duration customerRefreshTtl,
        Duration otpTtl,
        Integer otpMaxAttempts,
        Integer otpResendSeconds,
        Integer otpMaxSendsPer15m,
        Integer adminMaxFailedLogins,
        Duration adminLockout,
        String appPublicBaseUrl,
        String appAdminApiUrl,
        String appPairUrl
    ) {
        public Auth {
            if (adminMtlsHeader == null || adminMtlsHeader.isBlank()) {
                adminMtlsHeader = "Cf-Authenticated-Client-Cert";
            }
            if (adminMtlsVerifiedHeader == null || adminMtlsVerifiedHeader.isBlank()) {
                adminMtlsVerifiedHeader = "Cf-Authenticated-Client-Cert-Verified";
            }
            if (adminMtlsLocalBypass == null) adminMtlsLocalBypass = false;
            if (adminAccessTtl == null) adminAccessTtl = Duration.ofMinutes(15);
            if (adminRefreshTtl == null) adminRefreshTtl = Duration.ofDays(14);
            if (customerAccessTtl == null) customerAccessTtl = Duration.ofMinutes(15);
            if (customerRefreshTtl == null) customerRefreshTtl = Duration.ofDays(30);
            if (otpTtl == null) otpTtl = Duration.ofMinutes(10);
            if (otpMaxAttempts == null || otpMaxAttempts <= 0) otpMaxAttempts = 5;
            if (otpResendSeconds == null || otpResendSeconds <= 0) otpResendSeconds = 60;
            if (otpMaxSendsPer15m == null || otpMaxSendsPer15m <= 0) otpMaxSendsPer15m = 3;
            if (adminMaxFailedLogins == null || adminMaxFailedLogins <= 0) adminMaxFailedLogins = 5;
            if (adminLockout == null) adminLockout = Duration.ofMinutes(15);
        }

        public boolean localBypass() {
            return Boolean.TRUE.equals(adminMtlsLocalBypass);
        }
    }
}
