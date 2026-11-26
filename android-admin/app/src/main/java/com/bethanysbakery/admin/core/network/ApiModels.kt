package com.bethanysbakery.admin.core.network

import kotlinx.serialization.Serializable

@Serializable
data class PairDeviceRequest(
    val pairingCode: String,
    val deviceName: String,
    val publicKeyPem: String,
    val csrPem: String,
    val appVersion: String,
)

@Serializable
data class PairDeviceResponse(
    val deviceId: String,
    val apiKey: String,
    val clientCertificatePem: String,
    val caCertificatePem: String,
    val certificateExpiresAt: String,
)

@Serializable
data class FieldError(
    val field: String,
    val message: String,
)

@Serializable
data class ProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val code: String? = null,
    val detail: String? = null,
    val requestId: String? = null,
    val errors: List<FieldError> = emptyList(),
)
