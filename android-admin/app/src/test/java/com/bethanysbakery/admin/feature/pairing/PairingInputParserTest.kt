package com.bethanysbakery.admin.feature.pairing

import com.bethanysbakery.admin.core.config.AdminBuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingInputParserTest {

    private val releaseConfig = AdminBuildConfig(
        pairUrl = "https://bakery.cherry-home.com/admin/v1/devices/pair",
        adminApiUrl = "https://bakery.cherry-home.com/admin/v1",
        pins = listOf("primary", "backup"),
        pinningEnabled = true,
        isDebug = false,
        debugLanHosts = emptyList(),
        appVersion = "1.0.0",
    )

    private val debugConfig = releaseConfig.copy(
        isDebug = true,
        pinningEnabled = false,
        debugLanHosts = listOf("192.168.1.42"),
    )

    @Test
    fun `accepts v1 QR with exact configured endpoints`() {
        val json = """
            {"v":1,"pairUrl":"https://bakery.cherry-home.com/admin/v1/devices/pair","apiUrl":"https://bakery.cherry-home.com/admin/v1","code":"one-time-code"}
        """.trimIndent()

        val result = PairingInputParser.parseQr(json, releaseConfig)

        assertTrue(result is PairingInputResult.Valid)
        val valid = result as PairingInputResult.Valid
        assertEquals(releaseConfig.pairUrl, valid.input.pairUrl.toString())
        assertEquals(releaseConfig.adminApiUrl, valid.input.apiUrl.toString())
        assertEquals("one-time-code", valid.input.code)
    }

    @Test
    fun `rejects malformed QR JSON without producing input`() {
        val result = PairingInputParser.parseQr("""{"v":1,"pairUrl":"https://""", releaseConfig)

        assertEquals(PairingInputResult.Invalid(PairingInputError.MALFORMED_INPUT), result)
    }

    @Test
    fun `rejects unknown QR version`() {
        val json = """
            {"v":2,"pairUrl":"https://bakery.cherry-home.com/admin/v1/devices/pair","apiUrl":"https://bakery.cherry-home.com/admin/v1","code":"code"}
        """.trimIndent()

        val result = PairingInputParser.parseQr(json, releaseConfig)

        assertEquals(PairingInputResult.Invalid(PairingInputError.UNSUPPORTED_VERSION), result)
    }

    @Test
    fun `rejects QR missing required fields`() {
        val json = """{"v":1,"pairUrl":"https://bakery.cherry-home.com/admin/v1/devices/pair"}"""

        val result = PairingInputParser.parseQr(json, releaseConfig)

        assertEquals(PairingInputResult.Invalid(PairingInputError.MALFORMED_INPUT), result)
    }

    @Test
    fun `rejects QR whose pair host is not on allowlist`() {
        val json = """
            {"v":1,"pairUrl":"https://evil.example.com/admin/v1/devices/pair","apiUrl":"https://bakery.cherry-home.com/admin/v1","code":"code"}
        """.trimIndent()

        val result = PairingInputParser.parseQr(json, releaseConfig)

        assertEquals(PairingInputResult.Invalid(PairingInputError.INVALID_PAIR_URL), result)
    }

    @Test
    fun `rejects QR whose api path is not on allowlist`() {
        val json = """
            {"v":1,"pairUrl":"https://bakery.cherry-home.com/admin/v1/devices/pair","apiUrl":"https://bakery.cherry-home.com/api/v1","code":"code"}
        """.trimIndent()

        val result = PairingInputParser.parseQr(json, releaseConfig)

        assertEquals(PairingInputResult.Invalid(PairingInputError.INVALID_API_URL), result)
    }

    @Test
    fun `rejects blank pairing code`() {
        val json = """
            {"v":1,"pairUrl":"https://bakery.cherry-home.com/admin/v1/devices/pair","apiUrl":"https://bakery.cherry-home.com/admin/v1","code":"   "}
        """.trimIndent()

        val result = PairingInputParser.parseQr(json, releaseConfig)

        assertEquals(PairingInputResult.Invalid(PairingInputError.BLANK_CODE), result)
    }

    @Test
    fun `rejects http pairing URL in release`() {
        val result = PairingInputParser.parseManual(
            pairUrl = "http://bakery.cherry-home.com/admin/v1/devices/pair",
            apiUrl = "https://bakery.cherry-home.com/admin/v1",
            code = "code",
            config = releaseConfig,
        )

        assertEquals(PairingInputResult.Invalid(PairingInputError.INVALID_PAIR_URL), result)
    }

    @Test
    fun `rejects non-default port in release`() {
        val result = PairingInputParser.parseManual(
            pairUrl = "https://bakery.cherry-home.com:8443/admin/v1/devices/pair",
            apiUrl = "https://bakery.cherry-home.com/admin/v1",
            code = "code",
            config = releaseConfig,
        )

        assertEquals(PairingInputResult.Invalid(PairingInputError.INVALID_PAIR_URL), result)
    }

    @Test
    fun `rejects userinfo and fragments`() {
        val withUserInfo = PairingInputParser.parseManual(
            pairUrl = "https://user@bakery.cherry-home.com/admin/v1/devices/pair",
            apiUrl = "https://bakery.cherry-home.com/admin/v1",
            code = "code",
            config = releaseConfig,
        )
        val withFragment = PairingInputParser.parseManual(
            pairUrl = "https://bakery.cherry-home.com/admin/v1/devices/pair#frag",
            apiUrl = "https://bakery.cherry-home.com/admin/v1",
            code = "code",
            config = releaseConfig,
        )

        assertEquals(PairingInputResult.Invalid(PairingInputError.INVALID_PAIR_URL), withUserInfo)
        assertEquals(PairingInputResult.Invalid(PairingInputError.INVALID_PAIR_URL), withFragment)
    }

    @Test
    fun `debug accepts emulator loopback and documented LAN host over http`() {
        val emulator = PairingInputParser.parseManual(
            pairUrl = "http://10.0.2.2:8080/admin/v1/devices/pair",
            apiUrl = "http://10.0.2.2:8080/admin/v1",
            code = "code",
            config = debugConfig,
        )
        val lan = PairingInputParser.parseManual(
            pairUrl = "http://192.168.1.42:8080/admin/v1/devices/pair",
            apiUrl = "http://192.168.1.42:8080/admin/v1",
            code = "code",
            config = debugConfig,
        )

        assertTrue(emulator is PairingInputResult.Valid)
        assertTrue(lan is PairingInputResult.Valid)
    }

    @Test
    fun `debug still rejects other http hosts and wrong paths`() {
        val otherHost = PairingInputParser.parseManual(
            pairUrl = "http://evil.example.com:8080/admin/v1/devices/pair",
            apiUrl = "http://10.0.2.2:8080/admin/v1",
            code = "code",
            config = debugConfig,
        )
        val wrongPath = PairingInputParser.parseManual(
            pairUrl = "http://10.0.2.2:8080/pair",
            apiUrl = "http://10.0.2.2:8080/admin/v1",
            code = "code",
            config = debugConfig,
        )

        assertEquals(PairingInputResult.Invalid(PairingInputError.INVALID_PAIR_URL), otherHost)
        assertEquals(PairingInputResult.Invalid(PairingInputError.INVALID_PAIR_URL), wrongPath)
    }

    @Test
    fun `accepts manual entry with valid endpoints`() {
        val result = PairingInputParser.parseManual(
            pairUrl = releaseConfig.pairUrl,
            apiUrl = releaseConfig.adminApiUrl,
            code = "manual-code",
            config = releaseConfig,
        )

        assertTrue(result is PairingInputResult.Valid)
        assertEquals("manual-code", (result as PairingInputResult.Valid).input.code)
    }

    @Test
    fun `rejects api URL host query string and non-default port`() {
        val wrongHost = PairingInputParser.parseManual(
            pairUrl = releaseConfig.pairUrl,
            apiUrl = "https://evil.example.com/admin/v1",
            code = "code",
            config = releaseConfig,
        )
        val query = PairingInputParser.parseManual(
            pairUrl = releaseConfig.pairUrl,
            apiUrl = "https://bakery.cherry-home.com/admin/v1?code=code",
            code = "code",
            config = releaseConfig,
        )
        val port = PairingInputParser.parseManual(
            pairUrl = releaseConfig.pairUrl,
            apiUrl = "https://bakery.cherry-home.com:8443/admin/v1",
            code = "code",
            config = releaseConfig,
        )

        assertEquals(PairingInputResult.Invalid(PairingInputError.INVALID_API_URL), wrongHost)
        assertEquals(PairingInputResult.Invalid(PairingInputError.INVALID_API_URL), query)
        assertEquals(PairingInputResult.Invalid(PairingInputError.INVALID_API_URL), port)
    }

    @Test
    fun `rejects oversized QR payload and pairing code`() {
        val hugeQr = "{\"code\":\"" + "a".repeat(5000) + "\"}"
        val hugeCode = PairingInputParser.parseManual(
            pairUrl = releaseConfig.pairUrl,
            apiUrl = releaseConfig.adminApiUrl,
            code = "a".repeat(257),
            config = releaseConfig,
        )

        assertEquals(
            PairingInputResult.Invalid(PairingInputError.MALFORMED_INPUT),
            PairingInputParser.parseQr(hugeQr, releaseConfig),
        )
        assertEquals(PairingInputResult.Invalid(PairingInputError.MALFORMED_INPUT), hugeCode)
    }
}
