package com.bethanysbakery.admin.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okhttp3.tls.HeldCertificate
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class DeviceKeyManagerInstrumentedTest {

    private lateinit var manager: DeviceKeyManager
    private lateinit var secrets: EncryptedFileStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        secrets = EncryptedFileStore(context, TEST_NAMESPACE)
        manager = DeviceKeyManager(secrets)
        manager.delete()
    }

    @After
    fun tearDown() {
        manager.delete()
        secrets.deleteAll()
    }

    @Test
    fun createsNonExportableEcP256KeyAndVerifiableCsr() {
        val first = manager.getOrCreateKeyMaterial("Kitchen Tablet 1")
        val second = manager.getOrCreateKeyMaterial("Kitchen Tablet 1")

        assertEquals(first.publicKeyPem, second.publicKeyPem)
        assertEquals(first.csrPem, second.csrPem)
        assertTrue(manager.hasKeyPair())

        val privateKey = manager.privateKey()
        assertEquals("EC", privateKey.algorithm)
        assertNull("Private key must not be exportable", privateKey.encoded)

        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getMimeDecoder().decode(stripPem(first.publicKeyPem))),
        )
        val csr = JcaPKCS10CertificationRequest(first.csrPem.toByteArray(Charsets.US_ASCII))
        assertTrue(csr.isSignatureValid(JcaContentVerifierProviderBuilder().build(publicKey)))
        assertTrue(csr.subject.toString().contains("CN=Kitchen Tablet 1"))
        assertNotNull(csr.publicKey)
    }

    @Test
    fun storesAndLoadsClientCertificateChain() {
        val client = HeldCertificate.Builder().commonName("admin-device").ecdsa256().build()
        val ca = HeldCertificate.Builder().commonName("Bakery CA").ecdsa256().build()

        manager.storeCertificateChain(client.certificatePem(), ca.certificatePem())

        val chain = manager.certificateChain()
        assertEquals(2, chain.size)
        assertEquals(client.certificate, chain[0])
        assertEquals(ca.certificate, chain[1])
    }

    private fun stripPem(pem: String): String = pem
        .lines()
        .filterNot { it.startsWith("-----") }
        .joinToString("")

    private companion object {
        const val TEST_NAMESPACE = "device_key_test"
    }
}
