package com.bethanysbakery.admin.feature.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        if (!permissionGranted) requestPermission.launch(Manifest.permission.CAMERA)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Scan pairing QR", style = MaterialTheme.typography.headlineSmall)
        if (!permissionGranted) {
            Text("Camera access is needed to scan the pairing QR.", style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = { requestPermission.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Grant camera access") }
        } else {
            AndroidView(
                factory = { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } },
                modifier = Modifier.fillMaxWidth().height(320.dp),
                update = { view ->
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener({
                        val provider = future.get()
                        cameraProvider = provider
                        val preview = Preview.Builder().build().apply { setSurfaceProvider(view.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .apply { setAnalyzer(ContextCompat.getMainExecutor(context), QrAnalyzer(onResult)) }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }, ContextCompat.getMainExecutor(context))
                },
            )
            Text("Hold the QR code inside the frame. Manual entry is also available.", style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Cancel scan") }
    }
}

private class QrAnalyzer(onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    private val handled = AtomicBoolean(false)
    private val result = onResult

    override fun analyze(image: ImageProxy) {
        if (handled.get()) {
            image.close()
            return
        }
        val bitmap = yPlaneBitmap(image) ?: run {
            image.close()
            return
        }
        val input = InputImage.fromBitmap(bitmap, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull()?.rawValue
                if (raw != null && handled.compareAndSet(false, true)) result(raw)
            }
            .addOnCompleteListener { image.close() }
    }

    private fun yPlaneBitmap(image: ImageProxy): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        val row = ByteArray(plane.rowStride)
        val buffer: ByteBuffer = plane.buffer.duplicate()
        buffer.rewind()
        for (y in 0 until height) {
            buffer.position(y * plane.rowStride)
            buffer.get(row, 0, plane.rowStride)
            val base = y * width
            for (x in 0 until width) {
                val gray = row[x].toInt() and 0xFF
                pixels[base + x] = 0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
