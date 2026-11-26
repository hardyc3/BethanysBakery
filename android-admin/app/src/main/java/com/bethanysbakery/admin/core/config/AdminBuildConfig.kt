package com.bethanysbakery.admin.core.config

/**
 * Build-time endpoint and TLS policy. Values are injected from Gradle; the app cannot edit them.
 */
data class AdminBuildConfig(
    val pairUrl: String,
    val adminApiUrl: String,
    val pins: List<String>,
    val pinningEnabled: Boolean,
    val isDebug: Boolean,
    val debugLanHosts: List<String>,
    val appVersion: String,
    val testCaPem: String = "",
) {
    init {
        require(isDebug || (pinningEnabled && pins.size >= 2)) {
            "Release builds need pinning enabled with a primary and a backup SPKI SHA-256 pin"
        }
    }
}
