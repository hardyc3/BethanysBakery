package com.bethanysbakery.admin.feature.pairing

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bethanysbakery.admin.core.config.AdminBuildConfig
import com.bethanysbakery.admin.core.error.ApiErrorPresenter
import com.bethanysbakery.admin.core.network.ApiCallResult
import com.bethanysbakery.admin.core.network.DeviceApiKeyProvider
import com.bethanysbakery.admin.core.security.CertificateExpiryChecker
import com.bethanysbakery.admin.core.security.CertificateStatus
import com.bethanysbakery.admin.core.security.DeviceKeyManager
import com.bethanysbakery.admin.core.storage.PairedDevice
import com.bethanysbakery.admin.core.storage.PairedDeviceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

data class PairingUiState(
    val pairUrl: String,
    val apiUrl: String,
    val code: String = "",
    val isScanning: Boolean = false,
    val isPairing: Boolean = false,
    val showManual: Boolean = false,
    val message: String? = null,
)

sealed interface PairingEffect {
    data class Paired(val deviceId: String, val certificateExpiresAt: Long) : PairingEffect
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: AdminBuildConfig,
    private val repository: PairingRepository,
    private val keys: DeviceKeyManager,
    private val store: PairedDeviceStore,
    private val apiKey: DeviceApiKeyProvider,
    private val unpair: UnpairUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(PairingUiState(config.pairUrl, config.adminApiUrl))
    val state = _state.asStateFlow()

    // Replay restores the post-pairing destination even if the UI collects after init.
    private val _effects = MutableSharedFlow<PairingEffect>(replay = 1, extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch { restoreOrExpire() }
    }

    fun onScanClicked() {
        _state.update { it.copy(isScanning = true, message = null) }
    }

    fun onScanCancelled() {
        _state.update { it.copy(isScanning = false) }
    }

    fun onManualToggled() {
        _state.update { it.copy(showManual = !it.showManual, message = null) }
    }

    fun onFieldChanged(pairUrl: String? = null, apiUrl: String? = null, code: String? = null) {
        _state.update { current ->
            current.copy(
                pairUrl = pairUrl ?: current.pairUrl,
                apiUrl = apiUrl ?: current.apiUrl,
                code = code ?: current.code,
                message = null,
            )
        }
    }

    fun onQrScanned(payload: String) {
        _state.update { it.copy(isScanning = false) }
        parseAndPair(payload)
    }

    fun onManualSubmit() {
        val current = _state.value
        if (current.isPairing) return
        viewModelScope.launch {
            handleParsed(PairingInputParser.parseManual(current.pairUrl, current.apiUrl, current.code, config))
        }
    }

    fun onMessageShown() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun restoreOrExpire() {
        val paired = store.load() ?: return
        when (CertificateExpiryChecker.status(paired.certificateExpiresAt)) {
            CertificateStatus.EXPIRED -> {
                unpair()
                _state.update { it.copy(message = "Device certificate expired. Re-pair required.") }
            }
            else -> {
                apiKey.set(paired.apiKey)
                _effects.emit(PairingEffect.Paired(paired.deviceId, paired.certificateExpiresAt.epochSecond))
            }
        }
    }

    private fun parseAndPair(payload: String) {
        if (_state.value.isPairing) return
        viewModelScope.launch {
            handleParsed(PairingInputParser.parseQr(payload, config))
        }
    }

    private suspend fun handleParsed(parsed: PairingInputResult) {
        when (parsed) {
            is PairingInputResult.Invalid -> _state.update {
                it.copy(code = "", message = inputMessage(parsed.error))
            }
            is PairingInputResult.Valid -> pair(parsed.input)
        }
    }

    private suspend fun pair(input: PairingInput) {
        _state.update { it.copy(isPairing = true, message = null) }
        val deviceName = Build.MODEL.take(80)
        val keyMaterial = withContext(Dispatchers.IO) {
            keys.getOrCreateKeyMaterial(deviceName)
        }
        when (val result = repository.pair(input, keyMaterial, deviceName)) {
            is ApiCallResult.Success -> {
                val response = result.value
                val expiry = runCatching { Instant.parse(response.certificateExpiresAt) }.getOrNull()
                if (expiry == null) {
                    withContext(Dispatchers.IO) { unpair() }
                    _state.update {
                        it.copy(
                            isPairing = false,
                            code = "",
                            message = "The server returned an invalid certificate expiry. Retry.",
                        )
                    }
                    return
                }
                withContext(Dispatchers.IO) {
                    keys.storeCertificateChain(response.clientCertificatePem, response.caCertificatePem)
                    store.save(
                        PairedDevice(
                            deviceId = response.deviceId,
                            apiUrl = input.apiUrl.toString(),
                            apiKey = response.apiKey,
                            certificateExpiresAt = expiry,
                            pairedAt = Instant.now(),
                        ),
                    )
                }
                apiKey.set(response.apiKey)
                _state.update { it.copy(isPairing = false, code = "") }
                _effects.emit(PairingEffect.Paired(response.deviceId, expiry.epochSecond))
            }
            is ApiCallResult.Failure -> {
                // Failed pairing must not leave server-issued credentials or a usable local identity behind.
                withContext(Dispatchers.IO) { unpair() }
                _state.update {
                    it.copy(isPairing = false, code = "", message = ApiErrorPresenter.message(result.error))
                }
            }
        }
    }

    private fun inputMessage(error: PairingInputError): String = when (error) {
        PairingInputError.MALFORMED_INPUT -> "Malformed pairing input. Check the QR or use manual entry."
        PairingInputError.UNSUPPORTED_VERSION -> "This QR version is not supported."
        PairingInputError.INVALID_PAIR_URL -> "Pairing endpoint is not allowed on this build."
        PairingInputError.INVALID_API_URL -> "Admin API endpoint is not allowed on this build."
        PairingInputError.BLANK_CODE -> "Pairing code is required."
    }
}
