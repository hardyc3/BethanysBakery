package com.bethanysbakery.admin.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bethanysbakery.admin.core.security.CertificateStatus

@Composable
fun LoadingState(label: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun EmptyState(title: String, message: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, retryLabel: String = "Retry") {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Something went wrong", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
            Text(message, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(retryLabel) }
        }
    }
}

@Composable
fun CertificateStatusBadge(status: CertificateStatus) {
    val label = when (status) {
        CertificateStatus.VALID -> "Certificate valid"
        CertificateStatus.EXPIRING_SOON -> "Device certificate expiring; re-pair required"
        CertificateStatus.EXPIRED -> "Certificate expired — re-pair required"
    }
    val color = when (status) {
        CertificateStatus.VALID -> MaterialTheme.colorScheme.primary
        CertificateStatus.EXPIRING_SOON -> MaterialTheme.colorScheme.secondary
        CertificateStatus.EXPIRED -> MaterialTheme.colorScheme.error
    }
    Text(text = label, color = color, style = MaterialTheme.typography.bodyLarge)
}

@Composable
fun BakerySnackbar(hostState: SnackbarHostState) {
    SnackbarHost(hostState) { data ->
        Snackbar(snackbarData = data)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
