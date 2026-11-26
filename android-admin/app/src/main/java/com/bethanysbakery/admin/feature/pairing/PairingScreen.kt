package com.bethanysbakery.admin.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.bethanysbakery.admin.core.ui.components.BakerySnackbar
import com.bethanysbakery.admin.core.ui.components.LoadingState

@Composable
fun PairingScreen(
    onPaired: (String, Long) -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PairingEffect.Paired -> onPaired(effect.deviceId, effect.certificateExpiresAt)
            }
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showMessage(it)
            viewModel.onMessageShown()
        }
    }

    Scaffold(snackbarHost = { BakerySnackbar(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Pair this device", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Scan the QR from the bakery admin CLI, or enter the pairing details manually.",
                style = MaterialTheme.typography.bodyLarge,
            )

            if (state.isScanning) {
                QrScannerScreen(onResult = viewModel::onQrScanned, onCancel = viewModel::onScanCancelled)
            } else {
                Button(
                    onClick = viewModel::onScanClicked,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !state.isPairing,
                ) { Text("Scan pairing QR") }
                TextButton(
                    onClick = viewModel::onManualToggled,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !state.isPairing,
                ) { Text(if (state.showManual) "Hide manual entry" else "Enter details manually") }

                if (state.showManual) {
                    OutlinedTextField(
                        value = state.pairUrl,
                        onValueChange = { viewModel.onFieldChanged(pairUrl = it) },
                        label = { Text("Pair URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.isPairing,
                    )
                    OutlinedTextField(
                        value = state.apiUrl,
                        onValueChange = { viewModel.onFieldChanged(apiUrl = it) },
                        label = { Text("Admin API URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.isPairing,
                    )
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = { viewModel.onFieldChanged(code = it) },
                        label = { Text("Pairing code") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.isPairing,
                    )
                    Button(
                        onClick = { viewModel.onManualSubmit() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        enabled = !state.isPairing,
                    ) { Text("Pair device") }
                }

                if (state.isPairing) {
                    LoadingState("Installing device certificate…")
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Configured pairing endpoint", style = MaterialTheme.typography.bodyLarge)
                            Text(state.pairUrl, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(state.apiUrl, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun SnackbarHostState.showMessage(message: String) {
    showSnackbar(message = message, actionLabel = "Dismiss")
}
