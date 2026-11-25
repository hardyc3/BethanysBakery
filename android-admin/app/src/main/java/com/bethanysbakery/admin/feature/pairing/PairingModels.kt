package com.bethanysbakery.admin.feature.pairing

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl

@Serializable
data class PairingQrPayload(
    val v: Int,
    val pairUrl: String,
    val apiUrl: String,
    val code: String,
)

data class PairingInput(
    val pairUrl: HttpUrl,
    val apiUrl: HttpUrl,
    val code: String,
)

enum class PairingInputError {
    MALFORMED_INPUT,
    UNSUPPORTED_VERSION,
    INVALID_PAIR_URL,
    INVALID_API_URL,
    BLANK_CODE,
}

sealed interface PairingInputResult {
    data class Valid(val input: PairingInput) : PairingInputResult

    data class Invalid(val error: PairingInputError) : PairingInputResult
}
