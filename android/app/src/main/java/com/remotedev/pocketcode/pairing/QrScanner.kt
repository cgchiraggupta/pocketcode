package com.remotedev.pocketcode.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

@Composable
fun QrScannerScreen(onPaired: (PairingQR) -> Unit, onManual: () -> Unit) {
    val ctx = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pair with your computer", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (!hasPermission) {
            Text("Camera permission required.")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant") }
        } else {
            val lifecycleOwner = LocalLifecycleOwner.current
            AndroidView(factory = { c ->
                val view = PreviewView(c)
                val providerFuture = ProcessCameraProvider.getInstance(c)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analyzer = ImageAnalysis.Builder().build().also {
                        it.setAnalyzer(Executors.newSingleThreadExecutor(), QrAnalyzer { raw ->
                            QrParser.parse(raw)?.let(onPaired)
                        })
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
                }, ContextCompat.getMainExecutor(c))
                view
            }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        TextButton(onClick = onManual) { Text("Paste QR text manually") }
    }
}

private class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader()
    override fun analyze(image: ImageProxy) {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val src = PlanarYUVLuminanceSource(bytes, image.width, image.height, 0, 0, image.width, image.height, false)
        val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(src)))
        image.close()
        if (result != null) onResult(result.text)
    }
}
