package com.bethanysbakery.api.auth.device;

import com.bethanysbakery.api.api.auth.AuthApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.RateLimiter;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.auth.crypto.CryptoService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.auth.AdminDevice;
import com.bethanysbakery.api.domain.auth.AdminDeviceRepository;
import com.bethanysbakery.api.domain.auth.AdminUser;
import com.bethanysbakery.api.domain.auth.AdminUserRepository;
import com.bethanysbakery.api.domain.auth.AdminUserStatus;
import com.bethanysbakery.api.domain.auth.DevicePairing;
import com.bethanysbakery.api.domain.auth.DevicePairingRepository;
import com.bethanysbakery.api.domain.auth.DevicePlatform;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DevicePairingService {
    private static final Duration PAIRING_TTL = Duration.ofMinutes(15);
    private static final Duration DEVICE_CERT_TTL = Duration.ofDays(180);
    private static final String SINGLE_ACTIVE_CONSTRAINT = "uq_admin_device_single_active";
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final DevicePairingRepository pairings;
    private final AdminDeviceRepository devices;
    private final AdminUserRepository admins;
    private final CertificateAuthority certificateAuthority;
    private final CryptoService crypto;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final AdminDeviceService adminDevices;
    private final JdbcTemplate jdbc;

    public DevicePairingService(DevicePairingRepository pairings, AdminDeviceRepository devices,
            AdminUserRepository admins, CertificateAuthority certificateAuthority, CryptoService crypto,
            AuditService audit, RateLimiter rateLimiter, AdminDeviceService adminDevices, JdbcTemplate jdbc) {
        this.pairings = pairings;
        this.devices = devices;
        this.admins = admins;
        this.certificateAuthority = certificateAuthority;
        this.crypto = crypto;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.adminDevices = adminDevices;
        this.jdbc = jdbc;
    }

    @Transactional
    public String createPairingCode(String createdBy) {
        Instant now = Instant.now();
        String code = crypto.randomToken(16);
        pairings.save(new DevicePairing(Uuids.v7(), crypto.sha256Hex(code), now.plus(PAIRING_TTL), createdBy, now));
        return code;
    }

    @Transactional
    public AuthApi.PairDeviceResponse pair(AuthApi.PairDeviceRequest request, String ip) {
        RateLimiter.Result limit = rateLimiter.tryAcquire("pair:ip:" + ip, 10, Duration.ofMinutes(15));
        if (!limit.allowed()) {
            throw AuthException.rateLimited(limit.retryAfterSeconds());
        }
        // Pairing is rare: a transaction-scoped advisory lock serializes the check-and-insert so
        // two simultaneous attempts can never both observe "no active device" (even before V8's index).
        jdbc.execute("select pg_advisory_xact_lock(81008)");
        adminDevices.ensurePairingAllowed();
        Instant now = Instant.now();
        DevicePairing pairing = pairings.findByCodeHashForUpdate(crypto.sha256Hex(request.pairingCode()))
            .orElseThrow(() -> AuthException.unauthorized("Invalid pairing code"));
        if (pairing.isUsed() || pairing.isExpired(now)) {
            throw AuthException.unauthorized("Invalid pairing code");
        }
        AdminUser admin = admins.findFirstByStatus(AdminUserStatus.ACTIVE)
            .orElseThrow(() -> AuthException.badRequest("NOT_CONFIGURED", "Admin is not bootstrapped"));
        if (!certificateAuthority.publicKeyMatches(request.csrPem(), request.publicKeyPem())) {
            throw AuthException.badRequest("VALIDATION_ERROR", "CSR public key does not match");
        }
        CertificateAuthority.IssuedCertificate issued = certificateAuthority.issue(
            request.csrPem(), request.deviceName(), DEVICE_CERT_TTL
        );
        String apiKey = crypto.randomToken(32);
        X509Certificate certificate = parseCertificate(issued.clientCertificatePem());
        String fingerprint;
        try {
            fingerprint = crypto.sha256Hex(certificate.getEncoded());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to fingerprint issued certificate", ex);
        }
        AdminDevice device = new AdminDevice(
            Uuids.v7(), admin.getId(), request.deviceName(), DevicePlatform.ANDROID, request.publicKeyPem(),
            fingerprint, crypto.sha256Hex(apiKey), now, request.appVersion()
        );
        try {
            devices.saveAndFlush(device);
        } catch (DataIntegrityViolationException ex) {
            if (!isSingleActiveDeviceViolation(ex)) {
                throw ex;
            }
            // V8's single-active partial unique index won the race: same 409, never a 500.
            throw singleActiveDeviceConflict();
        }
        pairing.markUsed(now);
        pairings.save(pairing);
        audit.record(ActorType.ADMIN, admin.getId(), "ADMIN_DEVICE_PAIRED", "ADMIN_DEVICE", device.getId(), null,
            Map.of("name", request.deviceName(), "platform", "ANDROID"));
        return new AuthApi.PairDeviceResponse(
            device.getId(), apiKey, issued.clientCertificatePem(), issued.caCertificatePem(), issued.expiresAt()
        );
    }

    static AuthException singleActiveDeviceConflict() {
        return new AuthException(HttpStatus.CONFLICT, "INVALID_STATE", "One active admin device already exists");
    }

    /** Only V8's single-active index maps to 409; CSR/certificate conflicts stay 500s. */
    static boolean isSingleActiveDeviceViolation(DataIntegrityViolationException ex) {
        boolean uniqueViolation = false;
        boolean singleActiveConstraint = false;
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                && UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())) {
                uniqueViolation = true;
            }
            String message = cause.getMessage();
            if (message != null && message.contains(SINGLE_ACTIVE_CONSTRAINT)) {
                singleActiveConstraint = true;
            }
        }
        return uniqueViolation && singleActiveConstraint;
    }

    private static X509Certificate parseCertificate(String pem) {
        try {
            String base64 = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse issued certificate", ex);
        }
    }
}
