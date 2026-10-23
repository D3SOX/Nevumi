package me.d3sox.nevumi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewOutlineProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_allow_camera
import me.d3sox.nevumi.resources.sources_allow_camera_hint
import me.d3sox.nevumi.resources.sources_android_camera_open_failed
import me.d3sox.nevumi.resources.sources_pairing_camera_preview
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun PairingCameraPreview(modifier: Modifier, onScanned: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scanned by rememberUpdatedState(onScanned)
    val failed by rememberUpdatedState(onError)
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Manifest.permission.CAMERA) }
    if (!permitted) {
        Column(modifier) {
            Text(stringResource(Res.string.sources_allow_camera_hint))
            ActionButton(stringResource(Res.string.sources_allow_camera), AppIcon.CAMERA) { permission.launch(Manifest.permission.CAMERA) }
        }
        return
    }
    val previewDescription = stringResource(Res.string.sources_pairing_camera_preview)
    val openFailed = stringResource(Res.string.sources_android_camera_open_failed)
    val view = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // AndroidView does not clip the camera's scaled texture to its layout bounds.
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
        }
    }
    AndroidView(factory = { view }, modifier = modifier.height(280.dp).semantics { contentDescription = previewDescription })
    DisposableEffect(context, lifecycle, view) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val disposed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { image ->
            try {
                if (!disposed.get() && !delivered.get()) {
                    val plane = image.planes[0]
                    val pixels = cameraLuminance(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                    decodePairingQr(pixels, image.width, image.height)?.let { code ->
                        if (delivered.compareAndSet(false, true)) main.execute { if (!disposed.get()) scanned(code) }
                    }
                }
            } finally { image.close() }
        }
        future.addListener({
            if (!disposed.get()) try {
                provider = future.get()
                val selector = if (requireNotNull(provider).hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                    CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                requireNotNull(provider).bindToLifecycle(lifecycle, selector, preview, analysis)
            } catch (_: Exception) { failed(openFailed) }
        }, main)
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            executor.shutdownNow()
        }
    }
}
