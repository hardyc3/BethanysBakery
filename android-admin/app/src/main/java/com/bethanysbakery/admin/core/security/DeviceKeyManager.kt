package com.bethanysbakery.admin.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.security.auth.x500.X500Principal

class DeviceKeyManager(
    private val secrets: EncryptedFileStore,
) {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun getOrCreateKeyMaterial(deviceName: String): KeyMaterial {
        ensureKeyPair()
        val entry = privateEntry()
        val subject = X500Name("CN=${sanitizeName(deviceName)},O=Bethanys Bakery")
        val csr = JcaPKCS10CertificationRequestBuilder(subject, entry.certificate.publicKey)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(entry.privateKey))
        return KeyMaterial(
            publicKeyPem = toPem("PUBLIC KEY", entry.certificate.publicKey.encoded),
            csrPem = toPem("CERTIFICATE REQUEST", csr.encoded),
        )
    }

    fun storeCertificateChain(clientCertificatePem: String, caCertificatePem: String) {
        secrets.write(CERT_CHAIN_FILE, "$clientCertificatePem\n$caCertificatePem")
    }

    fun certificateChain(): Array<X509Certificate> {
        val pem = secrets.read(CERT_CHAIN_FILE) ?: error("No client certificate stored")
        @Suppress("UNCHECKED_CAST")
        return CertificateFactory.getInstance("X.509")
            .generateCertificates(pem.byteInputStream())
            .filterIsInstance<X509Certificate>()
            .toTypedArray()
    }

    fun privateKey(): java.security.PrivateKey = privateEntry().privateKey

    fun hasKeyPair(): Boolean = keyStore.containsAlias(KEY_ALIAS)

    fun delete() {
        keyStore.deleteEntry(KEY_ALIAS)
        secrets.delete(CERT_CHAIN_FILE)
    }

    private fun ensureKeyPair() {
        if (hasKeyPair()) return
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setCertificateSubject(X500Principal("CN=Bethany's Bakery Admin Device"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    private fun privateEntry(): KeyStore.PrivateKeyEntry =
        keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry ?: error("Device key missing")

    private fun toPem(label: String, encoded: ByteArray): String = StringWriter().use { writer ->
        JcaPEMWriter(writer).use { pem -> pem.writeObject(org.bouncycastle.util.io.pem.PemObject(label, encoded)) }
        writer.toString()
    }

    private fun sanitizeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9 _-]"), "").take(60).ifBlank { "Admin Device" }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "device_key"
        const val CERT_CHAIN_FILE = "client_cert_chain.pem"
    }
}
