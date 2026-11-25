package com.bethanysbakery.admin.integration

import com.bethanysbakery.admin.core.network.AdminApi
import com.bethanysbakery.admin.core.network.AdminHttpClientFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.ByteBuffer
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Happy-path integration coverage against the server-owned S9 dummy image.
 * Run with `./gradlew test -PincludeDockerIntegration=true --tests '*S9DummyServerIntegrationTest'`.
 */
@Tag("docker-integration")
class S9DummyServerIntegrationTest {

    private data class ResponsePayload(val status: Int, val body: String) {
        fun requireOk() {
            check(status in 200..299) { "HTTP $status: $body" }
        }

        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject

        fun field(name: String): String =
            json()[name]?.jsonPrimitive?.content ?: error("Missing '$name' in response: $body")
    }

    private class SimpleCookieJar : CookieJar {
        private val cookies = mutableMapOf<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val byName = this.cookies[url.host].orEmpty().associateBy { it.name }.toMutableMap()
            cookies.forEach { cookie ->
                if (cookie.expiresAt <= System.currentTimeMillis()) byName.remove(cookie.name)
                else byName[cookie.name] = cookie
            }
            this.cookies[url.host] = byName.values.toList()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies[url.host].orEmpty()
    }

    private data class BakeWindow(val bakeDate: String, val startAt: String, val endAt: String)

    companion object {
        private const val IMAGE = "bethanys-bakery-dummy:a1-integration"
        private const val API_PORT = 8080
        private const val MGMT_PORT = 9090
        private const val DEVICE_KEY = "dummy-device-key"
        private const val ADMIN_EMAIL = "admin@dummy.local"
        private const val ADMIN_PASSWORD = "DummyAdmin!234"
        private const val ADMIN_TOTP_SECRET = "JBSWY3DPEHPK3PXP"
        private const val CUSTOMER_EMAIL = "customer@dummy.local"
        private const val CUSTOMER_OTP = "123456"

        private val JSON = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA = "application/json".toMediaType()
        private val ZONE = ZoneId.of("America/Denver")
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        private lateinit var container: GenericContainer<*>
        private lateinit var apiBase: String
        private lateinit var managementBase: String

        @JvmStatic
        @BeforeAll
        fun startServer() {
            buildDummyImage()
            val dummy = GenericContainer<Nothing>(DockerImageName.parse(IMAGE))
            dummy.withExposedPorts(API_PORT, MGMT_PORT)
            dummy.waitingFor(
                Wait.forHttp("/actuator/health/readiness")
                    .forPort(MGMT_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)),
            )
            container = dummy
            container.start()
            apiBase = "http://${container.host}:${container.getMappedPort(API_PORT)}"
            managementBase = "http://${container.host}:${container.getMappedPort(MGMT_PORT)}"
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            if (::container.isInitialized) container.stop()
        }

        private fun buildDummyImage() {
            val workingDir = checkNotNull(System.getProperty("user.dir")) { "Missing user.dir" }
            val serverDir = generateSequence(Path(workingDir).toAbsolutePath()) { it.parent }
                .first { it.resolve("server/Dockerfile").exists() }
                .resolve("server")
                .toFile()
            val process = ProcessBuilder("docker", "build", "--target", "dummy", "-t", IMAGE, ".")
                .directory(serverDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            assertEquals(0, exitCode, "docker build --target dummy failed:\n$output")
        }

        private fun quote(value: String): String = JSON.encodeToString(value)

        private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(SimpleCookieJar())
            .build()

        private fun call(
            client: OkHttpClient,
            path: String,
            method: String = "GET",
            body: String? = null,
            headers: Map<String, String> = emptyMap(),
            base: String = apiBase,
        ): ResponsePayload {
            val builder = Request.Builder().url(base + path)
            headers.forEach { (name, value) -> builder.header(name, value) }
            if (body != null) builder.header("Content-Type", "application/json")
            val request = when (method) {
                "GET" -> builder.get().build()
                "POST" -> builder.post(body.orEmpty().toRequestBody(JSON_MEDIA)).build()
                "PUT" -> builder.put(body.orEmpty().toRequestBody(JSON_MEDIA)).build()
                "PATCH" -> builder.patch(body.orEmpty().toRequestBody(JSON_MEDIA)).build()
                else -> error("Unsupported method: $method")
            }
            return client.newCall(request).execute().use { response ->
                ResponsePayload(response.code, response.body.string())
            }
        }

        private fun csrf(client: OkHttpClient): String =
            call(client, "/api/v1/auth/csrf").also { it.requireOk() }.field("token")

        private fun postJson(
            client: OkHttpClient,
            path: String,
            body: String,
            headers: Map<String, String> = emptyMap(),
            base: String = apiBase,
        ): ResponsePayload = call(client, path, "POST", body, headers, base).also { it.requireOk() }

        private fun adminLogin(): String {
            val body = """{"email":${quote(ADMIN_EMAIL)},"password":${quote(ADMIN_PASSWORD)},""" +
                """"totpCode":${quote(totp(ADMIN_TOTP_SECRET))}}"""
            val response = postJson(
                client = httpClient(),
                path = "/admin/v1/auth/login",
                body = body,
                headers = mapOf("X-Device-Key" to DEVICE_KEY, "CF-Connecting-IP" to "127.0.0.1"),
            )
            return response.field("accessToken")
        }

        private fun adminHeaders(token: String) = mapOf(
            "Authorization" to "Bearer $token",
            "X-Device-Key" to DEVICE_KEY,
        )

        private fun authenticateCustomer(client: OkHttpClient) {
            val challenge = postJson(
                client = client,
                path = "/api/v1/auth/otp/request",
                body = """{"channel":"EMAIL","destination":${quote(CUSTOMER_EMAIL)}}""",
                headers = mapOf("X-CSRF-Token" to csrf(client)),
            ).field("challengeId")
            postJson(
                client = client,
                path = "/api/v1/auth/otp/verify",
                body = """{"challengeId":${quote(challenge)},"code":${quote(CUSTOMER_OTP)}}""",
            )
        }

        private fun bakeWindow(): BakeWindow {
            val date = LocalDate.now(ZONE).plusDays(20)
            return BakeWindow(
                bakeDate = date.toString(),
                startAt = ZonedDateTime.of(date, LocalTime.of(9, 0), ZONE).format(TIME_FORMAT),
                endAt = ZonedDateTime.of(date, LocalTime.of(11, 0), ZONE).format(TIME_FORMAT),
            )
        }

        private fun seedCatalogAndOrder(customer: OkHttpClient, adminToken: String): Pair<String, String> {
            val admin = adminHeaders(adminToken)
            val window = bakeWindow()
            val templateId = postJson(
                client = httpClient(),
                path = "/admin/v1/item-templates",
                body = """{"name":"Cinnamon Roll","active":true,"allergenTags":["gluten"],"customizationGroups":[]}""",
                headers = admin,
            ).field("id")
            val menuId = postJson(
                client = httpClient(),
                path = "/admin/v1/menus",
                body = """{"title":"A1 Docker Integration Menu"}""",
                headers = admin,
            ).field("id")
            val dateId = postJson(
                client = httpClient(),
                path = "/admin/v1/menus/$menuId/dates",
                body = """{"bakeDate":${quote(window.bakeDate)}}""",
                headers = admin,
            ).field("id")
            val windowId = postJson(
                client = httpClient(),
                path = "/admin/v1/menu-dates/$dateId/pickup-windows",
                body = """{"label":"Morning","startAt":${quote(window.startAt)},""" +
                    """"endAt":${quote(window.endAt)},"maxOrders":10}""",
                headers = admin,
            ).field("id")
            val offeringId = postJson(
                client = httpClient(),
                path = "/admin/v1/menu-dates/$dateId/offerings",
                body = """{"itemTemplateId":${quote(templateId)},"priceCents":650,"quantityLimit":20,""" +
                    """"windowIds":[${quote(windowId)}]}""",
                headers = admin,
            ).field("id")
            postJson(
                client = httpClient(),
                path = "/admin/v1/menus/$menuId/publish",
                body = """{"notificationEnabled":true}""",
                headers = admin,
            )
            val order = postJson(
                client = customer,
                path = "/api/v1/orders",
                body = """{"menuDateId":${quote(dateId)},"pickupWindowId":${quote(windowId)},""" +
                    """"idempotency_key":"a1-docker-integration-1","items":""" +
                    """[{"offeringId":${quote(offeringId)},"quantity":2}]}""",
                headers = mapOf("X-CSRF-Token" to csrf(customer)),
            )
            return menuId to order.field("id")
        }

        private fun totp(secret: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val base32 = secret.uppercase().trimEnd('=')
            val bits = buildString {
                base32.forEach { append(alphabet.indexOf(it).toString(2).padStart(5, '0')) }
            }
            val key = ByteArray(bits.length / 8) { index ->
                bits.substring(index * 8, index * 8 + 8).toInt(2).toByte()
            }
            val counter = System.currentTimeMillis() / 1000 / 30
            val message = ByteBuffer.allocate(8).putLong(counter).array()
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
                .doFinal(message)
            val offset = mac[mac.size - 1].toInt() and 0x0f
            val value = ((mac[offset].toInt() and 0x7f) shl 24) or
                ((mac[offset + 1].toInt() and 0xff) shl 16) or
                ((mac[offset + 2].toInt() and 0xff) shl 8) or
                (mac[offset + 3].toInt() and 0xff)
            return (value % 1_000_000).toString().padStart(6, '0')
        }
    }

    @Test
    fun `dummy container serves readiness and public config`() {
        val readiness = call(httpClient(), "/actuator/health/readiness", base = managementBase)
        readiness.requireOk()
        assertEquals("UP", readiness.field("status"))

        val config = call(httpClient(), "/api/v1/config")
        config.requireOk()
        assertEquals("Dummy Bakery", config.field("bakeryName"))
    }

    @Test
    fun `customer and admin complete an order happy path through the real API`() {
        val customer = httpClient()
        authenticateCustomer(customer)
        val profileCsrf = csrf(customer)
        call(
            customer,
            "/api/v1/me",
            "PATCH",
            """{"name":"A1 Docker Integration Customer"}""",
            mapOf("X-CSRF-Token" to profileCsrf),
        ).also { it.requireOk() }
        call(
            customer,
            "/api/v1/me/notification-preferences",
            "PUT",
            """{"smsConsent":true,"preferences":[{"eventType":"MENU_PUBLISHED","emailEnabled":true,"smsEnabled":false}]}""",
            mapOf("X-CSRF-Token" to profileCsrf),
        ).also { it.requireOk() }

        val adminToken = adminLogin()
        val (menuId, orderId) = seedCatalogAndOrder(customer, adminToken)
        call(httpClient(), "/admin/v1/orders/$orderId", headers = adminHeaders(adminToken))
            .also { it.requireOk() }

        val preview = postJson(
            client = httpClient(),
            path = "/admin/v1/notifications/recipient-preview?page=0&size=20",
            body = """{"eventType":"MENU_PUBLISHED","entityType":"MENU","entityId":${quote(menuId)}}""",
            headers = adminHeaders(adminToken),
        )
        assertNotNull(preview.json()["items"]?.jsonArray)
    }

    @Test
    fun `A1 paired client reads devices from the real server`() = runBlocking {
        val adminToken = adminLogin()
        val baseUrl = "$apiBase/admin/v1/".toHttpUrl()
        val client = AdminHttpClientFactory(requestId = { "a1-docker-integration" })
            .create(
                apiUrl = baseUrl,
                pins = emptyList(),
                pinningEnabled = false,
                deviceApiKey = DEVICE_KEY,
                appVersion = "1.0.0",
            )
            .newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $adminToken")
                        .build(),
                )
            }
            .build()
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(JSON.asConverterFactory(JSON_MEDIA))
            .build()
            .create(AdminApi::class.java)

        val devices = api.devices()

        assertTrue(devices.isNotEmpty(), "Dummy image must seed at least one paired device")
        assertTrue(devices.any { it.platform == "ANDROID" }, "Dummy image must seed an Android device")
    }
}
