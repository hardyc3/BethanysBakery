package com.bethanysbakery.admin.core.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AdminBuildConfigTest {

    private fun config(
        pins: List<String> = listOf("sha256/primary", "sha256/backup"),
        pinningEnabled: Boolean = true,
        isDebug: Boolean = false,
    ) = AdminBuildConfig(
        pairUrl = "https://bakery.cherry-home.com/admin/v1/devices/pair",
        adminApiUrl = "https://bakery.cherry-home.com/admin/v1",
        pins = pins,
        pinningEnabled = pinningEnabled,
        isDebug = isDebug,
        debugLanHosts = emptyList(),
        appVersion = "1.0.0",
    )

    @Test
    fun `release accepts two pins with pinning enabled`() {
        assertDoesNotThrow { config() }
    }

    @Test
    fun `release rejects disabled pinning`() {
        assertThrows(IllegalArgumentException::class.java) { config(pinningEnabled = false) }
    }

    @Test
    fun `release rejects fewer than two pins`() {
        assertThrows(IllegalArgumentException::class.java) { config(pins = listOf("sha256/primary")) }
    }

    @Test
    fun `debug may disable pinning`() {
        assertDoesNotThrow { config(pins = emptyList(), pinningEnabled = false, isDebug = true) }
    }
}
