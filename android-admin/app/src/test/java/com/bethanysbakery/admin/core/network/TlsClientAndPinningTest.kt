package com.bethanysbakery.admin.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CertificatePinner
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TlsClientAndPinningTest {

    private lateinit var server: MockWebServer
    private lateinit var serverCertificate: HeldCertificate
    private lateinit var clientCertificate: HeldCertificate
    private lateinit var apiUrl: okhttp3.HttpUrl

    @BeforeEach
    fun setUp() {
        serverCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .ecdsa256()
            .build()
        clientCertificate = HeldCertificate.Builder().commonName("admin-device").ecdsa256().build()

        val serverTls = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .addTrustedCertificate(clientCertificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory())
            requireClientAuth()
            start()
        }
        apiUrl = server.url("/admin/v1/")
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun client(pins: List<String>) = AdminHttpClientFactory(requestId = { "tls-request-id" }).create(
        apiUrl = apiUrl,
        pins = pins,
        pinningEnabled = true,
        deviceApiKey = "dummy-device-key",
        keyManagers = arrayOf(
            ClientCertificateKeyManager(clientCertificate.keyPair.private, arrayOf(clientCertificate.certificate)),
        ),
        trustManager = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .build()
            .trustManager,
        appVersion = "1.0.0",
    )

    private fun request() = Request.Builder().url(server.url("/admin/v1/devices")).build()

    private fun enqueue() = server.enqueue(
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{}""").build(),
    )

    @Test
    fun `paired client presents certificate and device key header`() = runTest {
        enqueue()
        val response = client(listOf(CertificatePinner.pin(serverCertificate.certificate), "sha256/backup")).newCall(request()).execute()
        assertEquals(200, response.code)

        val recorded = server.takeRequest()
        assertEquals("dummy-device-key", recorded.headers["X-Device-Key"])
        assertEquals("1.0.0", recorded.headers["X-App-Version"])
        assertEquals("application/json", recorded.headers["Accept"])
        assertEquals("tls-request-id", recorded.headers["X-Request-Id"])
        assertTrue(
            recorded.handshake?.peerCertificates?.any { it == clientCertificate.certificate } == true,
            "Server must receive the client certificate",
        )
    }

    @Test
    fun `backup pin succeeds after primary rotation`() = runTest {
        enqueue()
        val backupOnly = listOf(
            "sha256/rotated-out-primary",
            CertificatePinner.pin(serverCertificate.certificate),
        )

        val response = client(backupOnly).newCall(request()).execute()

        assertEquals(200, response.code)
    }

    @Test
    fun `pin mismatch surfaces TLS pinning failure`() = runTest {
        enqueue()
        val wrongBackup = HeldCertificate.Builder().commonName("other").ecdsa256().build()
        val failure = runCatching {
            client(
                listOf(
                    CertificatePinner.pin(clientCertificate.certificate),
                    CertificatePinner.pin(wrongBackup.certificate),
                ),
            ).newCall(request()).execute()
        }.exceptionOrNull()

        assertTrue(failure != null)
        val error = ApiErrorMapper.fromThrowable(failure!!)
        assertEquals("TLS_PINNING_FAILED", error.code)
    }
}
