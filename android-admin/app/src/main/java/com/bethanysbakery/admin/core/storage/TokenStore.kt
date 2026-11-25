package com.bethanysbakery.admin.core.storage

import com.bethanysbakery.admin.core.security.EncryptedFileStore

data class AdminTokens(val accessToken: String, val refreshToken: String)

/** A2 writes tokens here; A1 only guarantees the encrypted-at-rest seam exists. */
class TokenStore(private val secrets: EncryptedFileStore) {
    fun save(tokens: AdminTokens) {
        secrets.write(ACCESS_FILE, tokens.accessToken)
        secrets.write(REFRESH_FILE, tokens.refreshToken)
    }

    fun load(): AdminTokens? {
        val access = secrets.read(ACCESS_FILE) ?: return null
        val refresh = secrets.read(REFRESH_FILE) ?: return null
        return AdminTokens(access, refresh)
    }

    fun clear() {
        secrets.delete(ACCESS_FILE)
        secrets.delete(REFRESH_FILE)
    }

    private companion object {
        const val ACCESS_FILE = "access_token"
        const val REFRESH_FILE = "refresh_token"
    }
}
