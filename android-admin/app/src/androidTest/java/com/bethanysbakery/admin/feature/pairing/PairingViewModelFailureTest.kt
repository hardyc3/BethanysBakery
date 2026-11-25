package com.bethanysbakery.admin.feature.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bethanysbakery.admin.core.config.AdminBuildConfig
import com.bethanysbakery.admin.core.network.ApiCallResult
import com.bethanysbakery.admin.core.network.ApiError
import com.bethanysbakery.admin.core.network.DeviceApiKeyProvider
import com.bethanysbakery.admin.core.network.PairDeviceResponse
import com.bethanysbakery.admin.core.security.DeviceKeyManager
import com.bethanysbakery.admin.core.security.EncryptedFileStore
import com.bethanysbakery.admin.core.security.KeyMaterial
import com.bethanysbakery.admin.core.storage.PairedDevice
import com.bethanysbakery.admin.core.storage.PairedDeviceStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PairingViewModelFailureTest {

    @Test
    fun failedPairingClearsAllCredentialsAndShowsActionableError() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = EncryptedFileStore(context, TEST_NAMESPACE)
        val keys = DeviceKeyManager(secrets)
        val store = PairedDeviceStore(context, secrets)
        val apiKey = DeviceApiKeyProvider()
        val unpair = UnpairUseCase(store, keys, apiKey)
        val config = AdminBuildConfig(
            pairUrl = "https://bakery.cherry-home.com/admin/v1/devices/pair",
            adminApiUrl = "https://bakery.cherry-home.com/admin/v1",
            pins = listOf("sha256/primary", "sha256/backup"),
            pinningEnabled = true,
            isDebug = false,
            debugLanHosts = emptyList(),
            appVersion = "1.0.0",
        )
        val repository = object : PairingRepository {
            override suspend fun pair(
                input: PairingInput,
                keyMaterial: KeyMaterial,
                deviceName: String,
            ): ApiCallResult<PairDeviceResponse> = ApiCallResult.Failure(
                ApiError("UNAUTHORIZED", 401, "Unauthorized", "Invalid pairing code"),
            )
        }
        val viewModel = PairingViewModel(context, config, repository, keys, store, apiKey, unpair)

        secrets.deleteAll()
        keys.delete()
        keys.getOrCreateKeyMaterial("Old Tablet")
        keys.storeCertificateChain("client-pem", "ca-pem")
        store.save(
            PairedDevice(
                deviceId = "old-device",
                apiUrl = config.adminApiUrl,
                apiKey = "old-api-key",
                certificateExpiresAt = Instant.parse("2027-01-01T00:00:00Z"),
                pairedAt = Instant.parse("2026-11-25T00:00:00Z"),
            ),
        )
        apiKey.set("old-api-key")

        viewModel.onQrScanned(qrPayload(config))
        val state = viewModel.state.first { it.message != null && !it.isPairing }

        assertTrue(state.message!!.contains("pairing code"))
        assertEquals("", state.code)
        assertNull(store.load())
        assertNull(secrets.read("api_key"))
        assertNull(secrets.read("client_cert_chain.pem"))
        assertFalse(keys.hasKeyPair())
        assertNull(apiKey.apiKey)
    }

    private fun qrPayload(config: AdminBuildConfig) = """
        {"v":1,"pairUrl":"${config.pairUrl}","apiUrl":"${config.adminApiUrl}","code":"expired-code"}
    """.trimIndent()

    private companion object {
        const val TEST_NAMESPACE = "pairing_vm_failure_test"
    }
}
