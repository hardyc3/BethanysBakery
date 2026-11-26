package com.bethanysbakery.admin.feature.pairing

import com.bethanysbakery.admin.core.config.AdminBuildConfig
import com.bethanysbakery.admin.core.network.AdminHttpClientFactory
import com.bethanysbakery.admin.core.network.ApiCallResult
import com.bethanysbakery.admin.core.security.KeyMaterial
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RetrofitPairingRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: RetrofitPairingRepository

    private val keyMaterial = KeyMaterial(
        publicKeyPem = "-----BEGIN PUBLIC KEY-----\nMFkw\n-----END PUBLIC KEY-----\n",
        csrPem = "-----BEGIN CERTIFICATE REQUEST-----\nMIIB\n-----END CERTIFICATE REQUEST-----\n",
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        repository = RetrofitPairingRepository(
            config = AdminBuildConfig(
                pairUrl = "https://bakery.cherry-home.com/admin/v1/devices/pair",
                adminApiUrl = "https://bakery.cherry-home.com/admin/v1",
                pins = listOf("primary-pin", "backup-pin"),
                pinningEnabled = false,
                isDebug = true,
                debugLanHosts = emptyList(),
                appVersion = "1.0.0",
            ),
            clientFactory = AdminHttpClientFactory(requestId = { "fixed-request-id" }),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun input() = PairingInput(
        pairUrl = server.url("/admin/v1/devices/pair"),
        apiUrl = server.url("/admin/v1/"),
        code = "code-123",
    )

    @Test
    fun `successful pair sends contract fields and returns credentials`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {"deviceId":"device-1","apiKey":"api-key-1","clientCertificatePem":"client-pem",
                     "caCertificatePem":"ca-pem","certificateExpiresAt":"2027-01-01T00:00:00Z"}
                    """.trimIndent(),
                )
                .build(),
        )

        val result = repository.pair(input(), keyMaterial, "Test Tablet")

        assertTrue(result is ApiCallResult.Success)
        val response = (result as ApiCallResult.Success).value
        assertEquals("device-1", response.deviceId)
        assertEquals("api-key-1", response.apiKey)
        assertEquals("client-pem", response.clientCertificatePem)
        assertEquals("ca-pem", response.caCertificatePem)
        assertEquals("2027-01-01T00:00:00Z", response.certificateExpiresAt)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/admin/v1/devices/pair", request.url.encodedPath)
        assertEquals("application/json", request.headers["Accept"])
        assertEquals("1.0.0", request.headers["X-App-Version"])
        assertEquals("fixed-request-id", request.headers["X-Request-Id"])
        assertFalse(request.headers["X-Device-Key"] != null, "Pairing must not send a device key")
        val body = request.body?.string(Charsets.UTF_8).orEmpty()
        assertTrue(body.contains("\"pairingCode\":\"code-123\""))
        assertTrue(body.contains("\"deviceName\":\"Test Tablet\""))
        val fields = Json.parseToJsonElement(body).jsonObject
        assertEquals(keyMaterial.publicKeyPem, fields["publicKeyPem"]!!.jsonPrimitive.content)
        assertEquals(keyMaterial.csrPem, fields["csrPem"]!!.jsonPrimitive.content)
        assertTrue(body.contains("\"appVersion\":\"1.0.0\""))
    }

    @Test
    fun `invalid code maps problem details to actionable error`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .addHeader("Content-Type", "application/problem+json")
                .body(
                    """
                    {"type":"about:blank","title":"Unauthorized","status":401,"detail":"Invalid pairing code",
                     "code":"UNAUTHORIZED","requestId":"server-request-1","errors":[]}
                    """.trimIndent(),
                )
                .build(),
        )

        val result = repository.pair(input(), keyMaterial, "Test Tablet")

        assertTrue(result is ApiCallResult.Failure)
        val error = (result as ApiCallResult.Failure).error
        assertEquals("UNAUTHORIZED", error.code)
        assertEquals(401, error.status)
        assertEquals("Invalid pairing code", error.detail)
        assertEquals("server-request-1", error.requestId)
    }

    @Test
    fun `rate limit keeps retry-after`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Content-Type", "application/problem+json")
                .addHeader("Retry-After", "60")
                .body("""{"title":"Too Many Requests","status":429,"code":"RATE_LIMITED"}""")
                .build(),
        )

        val result = repository.pair(input(), keyMaterial, "Test Tablet")

        val error = (result as ApiCallResult.Failure).error
        assertEquals("RATE_LIMITED", error.code)
        assertEquals(60L, error.retryAfterSeconds)
    }

    @Test
    fun `active device conflict maps to INVALID_STATE`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(409)
                .addHeader("Content-Type", "application/problem+json")
                .body("""{"title":"Conflict","status":409,"code":"INVALID_STATE","detail":"Revoke the active device first"}""")
                .build(),
        )

        val result = repository.pair(input(), keyMaterial, "Test Tablet")

        val error = (result as ApiCallResult.Failure).error
        assertEquals("INVALID_STATE", error.code)
        assertEquals("Revoke the active device first", error.detail)
    }

    @Test
    fun `network failure maps to NETWORK_UNAVAILABLE`() = runTest {
        val unreachableInput = input()
        server.close()

        val result = repository.pair(unreachableInput, keyMaterial, "Test Tablet")

        val error = (result as ApiCallResult.Failure).error
        assertEquals("NETWORK_UNAVAILABLE", error.code)
        assertTrue(error.title.isNotBlank())
    }

    @Test
    fun `production-style API URL without trailing slash is normalized`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {"deviceId":"device-1","apiKey":"api-key-1","clientCertificatePem":"client-pem",
                     "caCertificatePem":"ca-pem","certificateExpiresAt":"2027-01-01T00:00:00Z"}
                    """.trimIndent(),
                )
                .build(),
        )
        val noSlashInput = input().copy(apiUrl = server.url("/admin/v1"))

        val result = repository.pair(noSlashInput, keyMaterial, "Test Tablet")

        assertTrue(result is ApiCallResult.Success)
        assertEquals("/admin/v1/devices/pair", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `unexpected response body maps to error without credentials`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader("Content-Type", "application/json")
                .body("not-json")
                .build(),
        )

        val result = repository.pair(input(), keyMaterial, "Test Tablet")

        assertTrue(result is ApiCallResult.Failure)
        assertFalse(result is ApiCallResult.Success)
    }
}
