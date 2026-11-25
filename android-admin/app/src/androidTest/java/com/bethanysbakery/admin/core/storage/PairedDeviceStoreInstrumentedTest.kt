package com.bethanysbakery.admin.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bethanysbakery.admin.core.security.EncryptedFileStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PairedDeviceStoreInstrumentedTest {

    private lateinit var secrets: EncryptedFileStore
    private lateinit var store: PairedDeviceStore
    private lateinit var context: android.content.Context

    @Before
    fun setUp() = runTest {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        secrets = EncryptedFileStore(context, TEST_NAMESPACE)
        store = PairedDeviceStore(context, secrets)
        store.clear()
    }

    @After
    fun tearDown() = runTest { store.clear() }

    @Test
    fun persistsMetadataAndEncryptedApiKeyAcrossLoads() = runTest {
        val apiKey = "api-key-secret-value"
        val paired = PairedDevice(
            deviceId = "device-1",
            apiUrl = "https://bakery.cherry-home.com/admin/v1",
            apiKey = apiKey,
            certificateExpiresAt = Instant.parse("2027-01-01T00:00:00Z"),
            pairedAt = Instant.parse("2026-11-25T00:00:00Z"),
        )

        store.save(paired)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(paired, loaded)
        val prefs = File(context.filesDir, "datastore/paired_device.preferences_pb").readBytes()
        assertFalse(String(prefs, Charsets.ISO_8859_1).contains(apiKey))
    }

    @Test
    fun clearRemovesAllPairingState() = runTest {
        store.save(
            PairedDevice(
                deviceId = "device-1",
                apiUrl = "https://bakery.cherry-home.com/admin/v1",
                apiKey = "api-key-secret-value",
                certificateExpiresAt = Instant.parse("2027-01-01T00:00:00Z"),
                pairedAt = Instant.parse("2026-11-25T00:00:00Z"),
            ),
        )

        store.clear()

        assertNull(store.load())
    }

    private companion object {
        const val TEST_NAMESPACE = "paired_device_test"
    }
}
