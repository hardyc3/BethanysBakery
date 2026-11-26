package com.bethanysbakery.admin.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.bethanysbakery.admin.core.ui.components.CertificateStatusBadge
import com.bethanysbakery.admin.core.ui.components.ConfirmDialog
import com.bethanysbakery.admin.core.ui.components.EmptyState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bethanysbakery.admin.core.security.CertificateExpiryChecker
import com.bethanysbakery.admin.core.storage.PairedDeviceStore
import com.bethanysbakery.admin.feature.pairing.UnpairUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class PostPairingUiState(
    val deviceId: String,
    val certificateExpiresAt: Long,
)

sealed interface PostPairingEffect {
    data object Unpaired : PostPairingEffect
}

@HiltViewModel
class PostPairingViewModel @Inject constructor(
    private val store: PairedDeviceStore,
    private val unpair: UnpairUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(PostPairingUiState("", 0L))
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PostPairingEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    fun initialise(deviceId: String, certificateExpiresAt: Long) {
        _state.update { it.copy(deviceId = deviceId, certificateExpiresAt = certificateExpiresAt) }
        viewModelScope.launch {
            val paired = store.load()
            if (paired != null && paired.deviceId == deviceId) {
                _state.update { it.copy(certificateExpiresAt = paired.certificateExpiresAt.epochSecond) }
            }
        }
    }

    fun unpairDevice() {
        viewModelScope.launch {
            unpair()
            _effects.emit(PostPairingEffect.Unpaired)
        }
    }
}

@Composable
fun LoginPlaceholderScreen(
    deviceId: String,
    certificateExpiresAt: Long,
    onUnpaired: () -> Unit,
    viewModel: PostPairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(deviceId, certificateExpiresAt) {
        viewModel.initialise(deviceId, certificateExpiresAt)
    }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PostPairingEffect.Unpaired -> onUnpaired()
            }
        }
    }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Admin sign-in", style = MaterialTheme.typography.headlineSmall)
            EmptyState(
                title = "Device paired",
                message = "This device is ready for admin sign-in. Sign-in arrives in A2.",
            )
            Text("Device ID: ${state.deviceId}", style = MaterialTheme.typography.bodyLarge)
            CertificateStatusBadge(
                CertificateExpiryChecker.status(Instant.ofEpochSecond(state.certificateExpiresAt)),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Unpair this device") }
        }
    }

    if (showConfirm) {
        ConfirmDialog(
            title = "Unpair device?",
            message = "This removes the certificate, API key, and tokens from this device.",
            confirmLabel = "Unpair",
            onConfirm = {
                showConfirm = false
                viewModel.unpairDevice()
            },
            onDismiss = { showConfirm = false },
        )
    }
}
