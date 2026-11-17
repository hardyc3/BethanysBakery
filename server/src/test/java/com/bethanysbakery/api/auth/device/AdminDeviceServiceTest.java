package com.bethanysbakery.api.auth.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.auth.crypto.CryptoService;
import com.bethanysbakery.api.auth.session.SessionService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.auth.AdminDevice;
import com.bethanysbakery.api.domain.auth.AdminDeviceRepository;
import com.bethanysbakery.api.domain.auth.DevicePlatform;
import com.bethanysbakery.api.support.TestProperties;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDeviceServiceTest {

    private static final String API_KEY = "device-api-key";

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Mock AdminDeviceRepository devices;
    @Mock SessionService sessions;
    @Mock AuditService audit;
    @Mock Environment environment;

    private final CryptoService crypto = new CryptoService(TestProperties.defaults());
    private X509Certificate certificate;
    private String certificatePem;
    private String fingerprint;
    private AdminDevice device;

    @BeforeEach
    void setUp() throws Exception {
        certificate = selfSignedCertificate();
        certificatePem = pem(certificate);
        fingerprint = crypto.sha256Hex(certificate.getEncoded());
        device = new AdminDevice(
            Uuids.v7(), UUID.randomUUID(), "Pixel", DevicePlatform.ANDROID, "public-key-pem",
            fingerprint, crypto.sha256Hex(API_KEY), Instant.now().minusSeconds(3600), "1.0.0"
        );
        when(environment.acceptsProfiles(Profiles.of("local", "test"))).thenReturn(false);
    }

    @Test
    void authenticatesVerifiedCertificateAndDeviceKey() {
        when(devices.findByCertFingerprintSha256(fingerprint)).thenReturn(Optional.of(device));

        AdminDevice authenticated = service(TestProperties.defaults()).authenticate(mtlsRequest(API_KEY));

        assertThat(authenticated.getId()).isEqualTo(device.getId());
        verify(devices).save(any(AdminDevice.class));
    }

    @Test
    void acceptsUrlEncodedCertificateHeader() {
        when(devices.findByCertFingerprintSha256(fingerprint)).thenReturn(Optional.of(device));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Cf-Authenticated-Client-Cert-Verified", "true");
        request.addHeader("Cf-Authenticated-Client-Cert",
            URLEncoder.encode(certificatePem, StandardCharsets.UTF_8));
        request.addHeader("X-Device-Key", API_KEY);

        assertThat(service(TestProperties.defaults()).authenticate(request).getId()).isEqualTo(device.getId());
    }

    @Test
    void rejectsMissingOrUnverifiedCertificate() {
        MockHttpServletRequest missing = new MockHttpServletRequest();
        missing.addHeader("Cf-Authenticated-Client-Cert", certificatePem);
        missing.addHeader("X-Device-Key", API_KEY);
        assertUnauthorized(missing);

        MockHttpServletRequest unverified = new MockHttpServletRequest();
        unverified.addHeader("Cf-Authenticated-Client-Cert-Verified", "false");
        unverified.addHeader("Cf-Authenticated-Client-Cert", certificatePem);
        unverified.addHeader("X-Device-Key", API_KEY);
        assertUnauthorized(unverified);
    }

    @Test
    void rejectsMissingOrMalformedCertificate() {
        MockHttpServletRequest missing = new MockHttpServletRequest();
        missing.addHeader("Cf-Authenticated-Client-Cert-Verified", "true");
        missing.addHeader("X-Device-Key", API_KEY);
        assertUnauthorized(missing);

        assertUnauthorized(requestWith("Cf-Authenticated-Client-Cert", "not-a-certificate"));
    }

    @Test
    void rejectsUnknownFingerprintAndRevokedDevice() {
        when(devices.findByCertFingerprintSha256(fingerprint)).thenReturn(Optional.empty());
        assertUnauthorized(mtlsRequest(API_KEY));

        AdminDevice revoked = new AdminDevice(
            Uuids.v7(), UUID.randomUUID(), "Pixel", DevicePlatform.ANDROID, "public-key-pem",
            fingerprint, crypto.sha256Hex(API_KEY), Instant.now().minusSeconds(3600), "1.0.0"
        );
        revoked.revoke(Instant.now());
        when(devices.findByCertFingerprintSha256(fingerprint)).thenReturn(Optional.of(revoked));
        assertUnauthorized(mtlsRequest(API_KEY));
    }

    @Test
    void rejectsWrongDeviceKey() {
        when(devices.findByCertFingerprintSha256(fingerprint)).thenReturn(Optional.of(device));
        MockHttpServletRequest request = mtlsRequest("wrong-key");

        assertUnauthorized(request);
    }

    @Test
    void bypassRequiresLocalProfileAndExplicitFlag() {
        BakeryProperties bypass = new BakeryProperties(
            "Test", "America/Denver", "addr", "https://maps", "t@example.com", 12,
            TestProperties.auth(true), new BakeryProperties.AdminApp("1.0.0", "1.0.0", "https://example.com/a", null)
        );
        when(devices.findByApiKeyHash(crypto.sha256Hex(API_KEY))).thenReturn(Optional.of(device));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Device-Key", API_KEY);

        when(environment.acceptsProfiles(Profiles.of("local", "test"))).thenReturn(true);
        assertThat(service(bypass).authenticate(request).getId()).isEqualTo(device.getId());

        when(environment.acceptsProfiles(Profiles.of("local", "test"))).thenReturn(false);
        assertUnauthorized(service(bypass), request);
    }

    private AdminDeviceService service(BakeryProperties properties) {
        return new AdminDeviceService(devices, crypto, sessions, audit, properties, environment);
    }

    private void assertUnauthorized(MockHttpServletRequest request) {
        assertUnauthorized(service(TestProperties.defaults()), request);
    }

    private void assertUnauthorized(AdminDeviceService service, MockHttpServletRequest request) {
        assertThatThrownBy(() -> service.authenticate(request)).isInstanceOf(AuthException.class);
    }

    private MockHttpServletRequest mtlsRequest(String deviceKey) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Cf-Authenticated-Client-Cert-Verified", "true");
        request.addHeader("X-Device-Key", deviceKey);
        request.addHeader("Cf-Authenticated-Client-Cert", certificatePem);
        return request;
    }

    private MockHttpServletRequest requestWith(String header, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Cf-Authenticated-Client-Cert-Verified", "true");
        request.addHeader("X-Device-Key", API_KEY);
        request.addHeader(header, value);
        return request;
    }

    private static X509Certificate selfSignedCertificate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=test-device");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            name, BigInteger.ONE, Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(3600)),
            name, keyPair.getPublic()
        );
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC")
            .build(keyPair.getPrivate());
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer));
    }

    private static String pem(X509Certificate certificate) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
            + java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(certificate.getEncoded())
            + "\n-----END CERTIFICATE-----\n";
    }
}
