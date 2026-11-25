package com.bethanysbakery.admin.feature.pairing

import com.bethanysbakery.admin.core.config.AdminBuildConfig
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object PairingInputParser {

    private val json = Json { ignoreUnknownKeys = false }
    private const val MAX_PAYLOAD_LENGTH = 4096
    private const val MAX_CODE_LENGTH = 256

    fun parseQr(payload: String, config: AdminBuildConfig): PairingInputResult {
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            return PairingInputResult.Invalid(PairingInputError.MALFORMED_INPUT)
        }
        return runCatching {
            json.decodeFromString<PairingQrPayload>(payload)
        }.fold(
            onSuccess = { parse(it.v, it.pairUrl, it.apiUrl, it.code, config) },
            onFailure = { PairingInputResult.Invalid(PairingInputError.MALFORMED_INPUT) },
        )
    }

    fun parseManual(
        pairUrl: String,
        apiUrl: String,
        code: String,
        config: AdminBuildConfig,
    ): PairingInputResult {
        if (pairUrl.length > MAX_PAYLOAD_LENGTH || apiUrl.length > MAX_PAYLOAD_LENGTH) {
            return PairingInputResult.Invalid(PairingInputError.MALFORMED_INPUT)
        }
        return parse(version = 1, pairUrl, apiUrl, code, config)
    }

    private fun parse(
        version: Int,
        pairUrl: String,
        apiUrl: String,
        code: String,
        config: AdminBuildConfig,
    ): PairingInputResult {
        if (version != 1) return PairingInputResult.Invalid(PairingInputError.UNSUPPORTED_VERSION)
        if (code.isBlank()) return PairingInputResult.Invalid(PairingInputError.BLANK_CODE)
        if (code.length > MAX_CODE_LENGTH) return PairingInputResult.Invalid(PairingInputError.MALFORMED_INPUT)
        if (!PairingUrlValidator.isValid(pairUrl, PairingUrlValidator.Endpoint.PAIR, config)) {
            return PairingInputResult.Invalid(PairingInputError.INVALID_PAIR_URL)
        }
        if (!PairingUrlValidator.isValid(apiUrl, PairingUrlValidator.Endpoint.API, config)) {
            return PairingInputResult.Invalid(PairingInputError.INVALID_API_URL)
        }
        val validatedPair = pairUrl.toHttpUrlOrNull()
        val validatedApi = apiUrl.toHttpUrlOrNull()
        if (validatedPair == null || validatedApi == null) {
            return PairingInputResult.Invalid(PairingInputError.MALFORMED_INPUT)
        }
        return PairingInputResult.Valid(PairingInput(validatedPair, validatedApi, code.trim()))
    }
}
