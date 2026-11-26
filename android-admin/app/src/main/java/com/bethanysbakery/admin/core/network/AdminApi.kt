package com.bethanysbakery.admin.core.network

import kotlinx.serialization.Serializable
import retrofit2.http.GET

@Serializable
data class AdminDeviceSummary(
    val id: String,
    val name: String,
    val platform: String,
    val status: String,
    val pairedAt: String,
    val lastSeenAt: String? = null,
    val revokedAt: String? = null,
    val appVersion: String? = null,
)

/** A1-owned smoke endpoint: device-key + admin bearer proves the paired client works. */
interface AdminApi {
    @GET("devices")
    suspend fun devices(): List<AdminDeviceSummary>
}
