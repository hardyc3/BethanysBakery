package com.bethanysbakery.admin.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EncryptedFileStoreInstrumentedTest {

    private lateinit var store: EncryptedFileStore
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = EncryptedFileStore(context, TEST_NAMESPACE)
        directory = File(context.filesDir, "encrypted_$TEST_NAMESPACE")
        store.deleteAll()
    }

    @After
    fun tearDown() = store.deleteAll()

    @Test
    fun writesAndReadsSecretWithoutStoringPlaintext() {
        val secret = "dummy-device-key-value"

        store.write("api_key", secret)

        assertEqualsValue(secret, store.read("api_key"))
        val raw = File(directory, "api_key").readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(secret))
        assertTrue(raw.size > 12)
    }

    @Test
    fun deleteRemovesSecret() {
        store.write("api_key", "value")
        store.delete("api_key")

        assertNull(store.read("api_key"))
    }

    @Test
    fun rejectsUnsafeSecretNames() {
        val failure = runCatching { store.write("../leak", "value") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun assertEqualsValue(expected: String?, actual: String?) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    private companion object {
        const val TEST_NAMESPACE = "secrets_test"
    }
}
