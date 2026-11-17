package com.bethanysbakery.api.support;

import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

/** Generates EC key pairs and PKCS#10 CSRs for pairing tests. */
public final class CsrTestSupport {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private CsrTestSupport() {}

    public static Material ecP256(String commonName) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "BC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        return withKeyPair(commonName, keyPair, "SHA256withECDSA");
    }

    public static Material rsa(String commonName) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        return withKeyPair(commonName, keyPair, "SHA256withRSA");
    }

    private static Material withKeyPair(String commonName, KeyPair keyPair, String algorithm) throws Exception {
        JcaPKCS10CertificationRequestBuilder builder = new JcaPKCS10CertificationRequestBuilder(
            new X500Name("CN=" + commonName), keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder(algorithm).setProvider("BC").build(keyPair.getPrivate());
        PKCS10CertificationRequest csr = builder.build(signer);
        return new Material(pem(keyPair.getPublic()), pem(csr), keyPair);
    }

    private static String pem(Object value) throws Exception {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(value);
        }
        return writer.toString();
    }

    public record Material(String publicKeyPem, String csrPem, KeyPair keyPair) {}
}
