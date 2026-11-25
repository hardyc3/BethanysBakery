package com.bethanysbakery.admin.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bethanysbakery.admin.core.security.EncryptedFileStore
import kotlinx.coroutines.flow.first
import java.time.Instant

private val Context.pairedDeviceDataStore: DataStore<Preferences> by preferencesDataStore("paired_device")

data class PairedDevice(
    val deviceId: String,
    val apiUrl: String,
    val apiKey: String,
    val certificateExpiresAt: Instant,
    val pairedAt: Instant,
)

class PairedDeviceStore(
    private val context: Context,
    private val secrets: EncryptedFileStore,
) {
    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val API_URL = stringPreferencesKey("api_url")
        val CERT_EXPIRY = stringPreferencesKey("cert_expires_at")
        val PAIRED_AT = stringPreferencesKey("paired_at")
    }

    suspend fun save(device: PairedDevice) {
        secrets.write(API_KEY_FILE, device.apiKey)
        context.pairedDeviceDataStore.edit { prefs ->
            prefs[Keys.DEVICE_ID] = device.deviceId
            prefs[Keys.API_URL] = device.apiUrl
            prefs[Keys.CERT_EXPIRY] = device.certificateExpiresAt.toString()
            prefs[Keys.PAIRED_AT] = device.pairedAt.toString()
        }
    }

    suspend fun load(): PairedDevice? {
        val prefs = context.pairedDeviceDataStore.data.first()
        val deviceId = prefs[Keys.DEVICE_ID] ?: return null
        val apiUrl = prefs[Keys.API_URL] ?: return null
        val expiry = prefs[Keys.CERT_EXPIRY]?.let(::runCatchingInstant) ?: return null
        val apiKey = secrets.read(API_KEY_FILE) ?: return null
        return PairedDevice(
            deviceId = deviceId,
            apiUrl = apiUrl,
            apiKey = apiKey,
            certificateExpiresAt = expiry,
            pairedAt = prefs[Keys.PAIRED_AT]?.let(::runCatchingInstant) ?: Instant.EPOCH,
        )
    }

    suspend fun clear() {
        context.pairedDeviceDataStore.edit { it.clear() }
        secrets.deleteAll()
    }

    private fun runCatchingInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    private companion object {
        const val API_KEY_FILE = "api_key"
    }
}
