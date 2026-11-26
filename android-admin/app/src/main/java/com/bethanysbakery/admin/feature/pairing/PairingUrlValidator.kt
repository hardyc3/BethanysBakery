package com.bethanysbakery.admin.feature.pairing

import com.bethanysbakery.admin.core.config.AdminBuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Exact endpoint allowlist. Nothing here performs a network call.
 */
object PairingUrlValidator {

    enum class Endpoint { PAIR, API }

    fun isValid(value: String, endpoint: Endpoint, config: AdminBuildConfig): Boolean {
        val expected = when (endpoint) {
            Endpoint.PAIR -> config.pairUrl.toHttpUrl()
            Endpoint.API -> config.adminApiUrl.toHttpUrl()
        }
        val candidate = value.toHttpUrlOrNull() ?: return false
        if (!candidate.username.isNullOrEmpty() || !candidate.password.isNullOrEmpty() || candidate.fragment?.isNotEmpty() == true) return false
        if (!candidate.query.isNullOrEmpty()) return false
        if (candidate.pathSegments != expected.pathSegments) return false

        return if (candidate.scheme == "http" && config.isDebug) {
            candidate.host == "10.0.2.2" || candidate.host in config.debugLanHosts
        } else {
            candidate.scheme == "https" &&
                candidate.port == 443 &&
                candidate == expected
        }
    }
}
