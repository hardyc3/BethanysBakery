package com.bethanysbakery.admin.core.network

import com.bethanysbakery.admin.core.config.AdminBuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdminHttpClientFactoryPinningTest {

    private val factory = AdminHttpClientFactory(requestId = { "request-1" })
    private val apiUrl = "https://bakery.cherry-home.com/admin/v1/".toHttpUrl()

    @Test
    fun `release config requires primary and backup pins`() {
        val failure = runCatching {
            AdminBuildConfig(
                pairUrl = "https://bakery.cherry-home.com/admin/v1/devices/pair",
                adminApiUrl = "https://bakery.cherry-home.com/admin/v1",
                pins = listOf("only-one"),
                pinningEnabled = true,
                isDebug = false,
                debugLanHosts = emptyList(),
                appVersion = "1.0.0",
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `client factory requires two pins when pinning enabled`() {
        val failure = runCatching {
            factory.create(
                apiUrl = apiUrl,
                pins = listOf("only-one"),
                pinningEnabled = true,
                deviceApiKey = null,
                appVersion = "1.0.0",
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `primary and backup pins are installed for the API host`() {
        val client = factory.create(
            apiUrl = apiUrl,
            pins = listOf("sha256/primary", "sha256/backup"),
            pinningEnabled = true,
            deviceApiKey = null,
            appVersion = "1.0.0",
        )

        val pinner = client.certificatePinner
        assertEquals(2, pinner.findMatchingPins(apiUrl.host).size)
    }

    @Test
    fun `pinning can be disabled explicitly in debug`() {
        val client = factory.create(
            apiUrl = apiUrl,
            pins = emptyList(),
            pinningEnabled = false,
            deviceApiKey = null,
            appVersion = "1.0.0",
        )

        assertTrue(runCatching { client.certificatePinner.check(apiUrl.host, emptyList()) }.isSuccess)
    }
}
