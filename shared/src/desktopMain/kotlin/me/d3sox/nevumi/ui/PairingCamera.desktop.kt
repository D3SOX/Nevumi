package me.d3sox.nevumi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_camera
import me.d3sox.nevumi.resources.sources_desktop_camera_open_failed
import me.d3sox.nevumi.resources.sources_no_webcam
import me.d3sox.nevumi.resources.sources_opening_camera
import me.d3sox.nevumi.resources.sources_pairing_camera_preview
import org.jetbrains.compose.resources.stringResource

internal data class DesktopCamera(val path: String, val name: String)

internal fun desktopCameras(): List<DesktopCamera> {
    val directory = Path.of("/sys/class/video4linux")
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.list(directory).use { files -> files.sorted().map { path ->
        DesktopCamera("/dev/${path.fileName}", runCatching { Files.readString(path.resolve("name")).trim() }.getOrDefault(path.fileName.toString()) + " (${path.fileName})")
    }.toList() }
}

// Both webcam input and fixture videos use this frame reader and QR decoder.
internal class DesktopCameraCapture(input: List<String>) : AutoCloseable {
    private val process = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-loglevel", "error") + input +
        listOf("-an", "-vf", "scale=640:480", "-r", "10", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1")).start()
    private val errors = Thread {
        runCatching { process.errorStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) } }
    }.apply { isDaemon = true; name = "nevumi-camera-errors"; start() }
    val alive: Boolean get() = process.isAlive

    fun readFrame(): BufferedImage {
        val rgb = process.inputStream.readNBytes(640 * 480 * 3)
        if (rgb.size != 640 * 480 * 3) throw EOFException("Could not read the camera. Check camera access or choose another camera.")
        val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val pixels = IntArray(640 * 480) { index ->
            val offset = index * 3
            ((rgb[offset].toInt() and 255) shl 16) or ((rgb[offset + 1].toInt() and 255) shl 8) or (rgb[offset + 2].toInt() and 255)
        }
        image.setRGB(0, 0, 640, 480, pixels, 0, 640)
        return image
    }

    override fun close() {
        process.destroyForcibly()
        runCatching { process.inputStream.close() }
    }
}

internal fun BufferedImage.pairingInvitation(): String? {
    val pixels = getRGB(0, 0, width, height, null, 0, width)
    val luminance = ByteArray(pixels.size) { index ->
        val rgb = pixels[index]
        (((rgb shr 16 and 255) + 2 * (rgb shr 8 and 255) + (rgb and 255)) / 4).toByte()
    }
    return decodePairingQr(luminance, width, height)
}

@Composable
internal actual fun PairingCameraPreview(modifier: Modifier, onScanned: (String) -> Unit, onError: (String) -> Unit) {
    val cameras = remember { desktopCameras() }
    DesktopPairingCameraPreview(cameras, modifier, onScanned, onError)
}

@Composable
internal fun DesktopPairingCameraPreview(cameras: List<DesktopCamera>, modifier: Modifier,
    onScanned: (String) -> Unit, onError: (String) -> Unit,
    openCamera: (String) -> DesktopCameraCapture = { DesktopCameraCapture(listOf("-f", "v4l2", "-i", it)) }) {
    var selected by remember { mutableStateOf(cameras.firstOrNull()?.path) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val scanned by rememberUpdatedState(onScanned)
    val failed by rememberUpdatedState(onError)
    val scope = rememberCoroutineScope()
    val openFailed = stringResource(Res.string.sources_desktop_camera_open_failed)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cameras.isEmpty()) Text(stringResource(Res.string.sources_no_webcam))
        else {
            SettingChoice(stringResource(Res.string.sources_camera), selected, cameras.map { it.path as String? to it.name }, card = false) { selected = it }
            frame?.let { Image(it, stringResource(Res.string.sources_pairing_camera_preview), Modifier.fillMaxWidth().height(220.dp)) }
                ?: Box(Modifier.fillMaxWidth().height(220.dp)) { Text(stringResource(Res.string.sources_opening_camera)) }
        }
    }
    DisposableEffect(selected) {
        frame = null
        val disposed = AtomicBoolean(false)
        val capture = AtomicReference<DesktopCameraCapture?>(null)
        val job = selected?.let { path -> scope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    openCamera(path).also {
                        capture.set(it)
                        if (disposed.get()) it.close()
                    }
                }
                while (isActive && !disposed.get()) {
                    val (bitmap, code) = withContext(Dispatchers.IO) {
                        val image = opened.readFrame()
                        image.toComposeImageBitmap() to image.pairingInvitation()
                    }
                    frame = bitmap
                    if (code != null) { scanned(code); break }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!disposed.get()) {
                    failed(openFailed)
                }
            } finally { capture.getAndSet(null)?.close() }
        } }
        onDispose { disposed.set(true); capture.getAndSet(null)?.close(); job?.cancel() }
    }
}
