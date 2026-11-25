package com.bethanysbakery.api.auth.device;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.config.BakeryProperties;
import java.io.FileReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class CertificateAuthority {
    private static final Logger log = LoggerFactory.getLogger(CertificateAuthority.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final BakeryProperties.Auth config;
    private final boolean ephemeralAllowed;
    private volatile Material material;

    public CertificateAuthority(BakeryProperties properties, Environment env) {
        this.config = properties.auth();
        this.ephemeralAllowed = env.acceptsProfiles(Profiles.of("local", "test", "dummy"));
    }

    public IssuedCertificate issue(String csrPem, String deviceName, Duration ttl) {
        PKCS10CertificationRequest csr = parseCsr(csrPem);
        PublicKey publicKey = verifyCsr(csr);
        Material ca = material();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        X500Name subject = new X500Name("CN=" + safeCommonName(deviceName));
        BigInteger serial = new BigInteger(64, RANDOM).abs();
        try {
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                ca.certificate(), serial, Date.from(now.minusSeconds(60)), Date.from(expiresAt), subject, publicKey
            );
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));

            String algorithm = ca.privateKey().getAlgorithm().equalsIgnoreCase("EC") ? "SHA256withECDSA" : "SHA256withRSA";
            ContentSigner signer = new JcaContentSignerBuilder(algorithm).setProvider("BC").build(ca.privateKey());
            X509Certificate certificate = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
            return new IssuedCertificate(certificatePem(certificate), ca.certificatePem(), expiresAt);
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to issue device certificate", ex);
        }
    }

    public boolean publicKeyMatches(String csrPem, String publicKeyPem) {
        try {
            PKCS10CertificationRequest csr = parseCsr(csrPem);
            PublicKey fromCsr = new JcaPEMKeyConverter().setProvider("BC").getPublicKey(csr.getSubjectPublicKeyInfo());
            PublicKey supplied = new JcaPEMKeyConverter().setProvider("BC").getPublicKey(parsePublicKeyInfo(publicKeyPem));
            return java.util.Arrays.equals(fromCsr.getEncoded(), supplied.getEncoded());
        } catch (Exception ex) {
            return false;
        }
    }

    private PKCS10CertificationRequest parseCsr(String csrPem) {
        try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
            Object object = parser.readObject();
            if (object instanceof PKCS10CertificationRequest request) {
                return request;
            }
            throw AuthException.badRequest("VALIDATION_ERROR", "Invalid CSR");
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Invalid CSR");
        }
    }

    private org.bouncycastle.asn1.x509.SubjectPublicKeyInfo parsePublicKeyInfo(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object object = parser.readObject();
            if (object instanceof org.bouncycastle.asn1.x509.SubjectPublicKeyInfo info) {
                return info;
            }
            throw AuthException.badRequest("VALIDATION_ERROR", "Invalid public key");
        }
    }

    private PublicKey verifyCsr(PKCS10CertificationRequest csr) {
        try {
            PublicKey publicKey = new JcaPEMKeyConverter().setProvider("BC").getPublicKey(csr.getSubjectPublicKeyInfo());
            if (!(publicKey instanceof ECPublicKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 256) {
                throw AuthException.badRequest("VALIDATION_ERROR", "CSR must use an EC P-256 key");
            }
            ContentVerifierProvider verifier = new JcaContentVerifierProviderBuilder().setProvider("BC").build(publicKey);
            if (!csr.isSignatureValid(verifier)) {
                throw AuthException.badRequest("VALIDATION_ERROR", "Invalid CSR signature");
            }
            return publicKey;
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Invalid CSR");
        }
    }

    private Material material() {
        Material current = material;
        if (current != null) return current;
        synchronized (this) {
            if (material != null) return material;
            material = loadOrGenerate();
            return material;
        }
    }

    private Material loadOrGenerate() {
        String certPath = config.adminDeviceCaCertPath();
        String keyPath = config.adminDeviceCaKeyPath();
        if (certPath != null && !certPath.isBlank() && keyPath != null && !keyPath.isBlank()) {
            try {
                X509Certificate certificate;
                try (PEMParser parser = new PEMParser(new FileReader(certPath))) {
                    Object object = parser.readObject();
                    if (object instanceof X509CertificateHolder holder) {
                        certificate = new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
                    } else {
                        throw new IllegalStateException("ADMIN_DEVICE_CA_CERT_PATH must contain a PEM certificate");
                    }
                }
                PrivateKey privateKey;
                try (PEMParser parser = new PEMParser(new FileReader(keyPath))) {
                    Object object = parser.readObject();
                    JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
                    if (object instanceof PEMKeyPair pair) {
                        privateKey = converter.getPrivateKey(pair.getPrivateKeyInfo());
                    } else if (object instanceof PrivateKeyInfo info) {
                        privateKey = converter.getPrivateKey(info);
                    } else {
                        throw new IllegalStateException("ADMIN_DEVICE_CA_KEY_PATH must contain an unencrypted private key");
                    }
                }
                return new Material(certificate, privateKey, certificatePem(certificate));
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to load admin device CA", ex);
            }
        }
        if (!ephemeralAllowed) {
            throw AuthException.notConfigured("Admin device CA is not configured");
        }
        log.warn("No admin device CA configured; generating an ephemeral local CA");
        return generateEphemeral();
    }

    private Material generateEphemeral() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "BC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            X500Name name = new X500Name("CN=Bethany's Bakery Local Device CA");
            Instant now = Instant.now();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, new BigInteger(64, RANDOM).abs(), Date.from(now.minusSeconds(60)),
                Date.from(now.plus(Duration.ofDays(3650))), name, keyPair.getPublic()
            );
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC")
                .build(keyPair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
            return new Material(certificate, keyPair.getPrivate(), certificatePem(certificate));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to generate local CA", ex);
        }
    }

    private static String safeCommonName(String deviceName) {
        String value = deviceName == null || deviceName.isBlank() ? "Android device" : deviceName.trim();
        // X500 CN is attacker-influenced input; allow a conservative charset only.
        value = value.replaceAll("[^A-Za-z0-9 .'_-]", " ");
        if (value.length() > 64) value = value.substring(0, 64);
        return value;
    }

    private static String certificatePem(X509Certificate certificate) {
        try {
            StringWriter writer = new StringWriter();
            try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
                pemWriter.writeObject(certificate);
            }
            return writer.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encode certificate", ex);
        }
    }

    private record Material(X509Certificate certificate, PrivateKey privateKey, String certificatePem) {}

    public record IssuedCertificate(String clientCertificatePem, String caCertificatePem, Instant expiresAt) {}
}
