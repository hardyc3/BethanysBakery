package com.bethanysbakery.admin.feature.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bethanysbakery.admin.core.network.DeviceApiKeyProvider
import com.bethanysbakery.admin.core.security.DeviceKeyManager
import com.bethanysbakery.admin.core.security.EncryptedFileStore
import com.bethanysbakery.admin.core.storage.AdminTokens
import com.bethanysbakery.admin.core.storage.PairedDevice
import com.bethanysbakery.admin.core.storage.PairedDeviceStore
import com.bethanysbakery.admin.core.storage.TokenStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class UnpairUseCaseInstrumentedTest {

    @Test
    fun clearsKeysCertificatesApiKeyTokensAndMetadata() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = EncryptedFileStore(context, TEST_NAMESPACE)
        val keys = DeviceKeyManager(secrets)
        val store = PairedDeviceStore(context, secrets)
        val tokens = TokenStore(secrets)
        val apiKey = DeviceApiKeyProvider()
        val unpair = UnpairUseCase(store, keys, apiKey)

        keys.delete()
        store.clear()
        keys.getOrCreateKeyMaterial("Unpair Tablet")
        keys.storeCertificateChain("client-pem", "ca-pem")
        store.save(
            PairedDevice(
                deviceId = "device-1",
                apiUrl = "https://bakery.cherry-home.com/admin/v1",
                apiKey = "api-key-secret",
                certificateExpiresAt = Instant.parse("2027-01-01T00:00:00Z"),
                pairedAt = Instant.parse("2026-11-25T00:00:00Z"),
            ),
        )
        tokens.save(AdminTokens("access", "refresh"))
        apiKey.set("api-key-secret")

        unpair()

        assertNull(store.load())
        assertNull(tokens.load())
        assertNull(secrets.read("api_key"))
        assertNull(secrets.read("client_cert_chain.pem"))
        assertFalse(keys.hasKeyPair())
        assertNull(apiKey.apiKey)
    }

    private companion object {
        const val TEST_NAMESPACE = "unpair_test"
    }
}
