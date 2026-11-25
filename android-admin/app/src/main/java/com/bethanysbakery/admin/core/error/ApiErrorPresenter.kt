package com.bethanysbakery.admin.core.error

import com.bethanysbakery.admin.core.network.ApiError

object ApiErrorPresenter {
    fun message(error: ApiError): String = when (error.code) {
        "UNAUTHORIZED" -> "That pairing code is invalid or expired. Ask for a new QR code."
        "RATE_LIMITED" -> error.retryAfterSeconds?.let { "Too many attempts. Try again in $it seconds." }
            ?: "Too many attempts. Wait a moment and try again."
        "INVALID_STATE" -> "Another device is already paired. Revoke it first."
        "TLS_PINNING_FAILED" -> "The server certificate is not trusted. Check the network and retry."
        "TLS_ERROR" -> "Could not verify the server identity. Do not continue on an untrusted network."
        "TIMEOUT" -> "The server took too long to respond. Retry."
        "NETWORK_UNAVAILABLE" -> "No network connection. Check connectivity and retry."
        "EMPTY_RESPONSE" -> "The server returned an empty response. Retry."
        // Raw server detail / throwable messages may contain internal host or stack information.
        else -> "Pairing failed. Retry or enter the code manually."
    }
}
