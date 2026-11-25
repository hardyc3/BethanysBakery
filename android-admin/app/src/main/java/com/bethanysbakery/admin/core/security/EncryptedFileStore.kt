package com.bethanysbakery.admin.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.SecretKey

/**
 * AES-GCM files keyed by a non-exportable Android Keystore key.
 * Replaces EncryptedSharedPreferences; secrets never appear in DataStore or logs.
 */
class EncryptedFileStore(
    context: Context,
    private val nameSpace: String = "admin_secrets",
) {
    private val directory = File(context.filesDir, "encrypted_$nameSpace").apply { mkdirs() }
    private val keyAlias = "aes_$nameSpace"

    init {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) {
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                .apply { init(spec) }
                .generateKey()
        }
    }

    fun write(name: String, value: String) {
        val file = fileFor(name)
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keystoreKey(), GCMParameterSpec(TAG_BITS, iv))
        }
        file.writeBytes(iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    fun read(name: String): String? {
        val file = fileFor(name)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        }
        return String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
    }

    fun delete(name: String) = fileFor(name).delete()

    fun deleteAll() = directory.listFiles()?.forEach { it.delete() }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as SecretKey
    }

    private fun fileFor(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+"))) { "Invalid secret name" }
        return File(directory, name)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
