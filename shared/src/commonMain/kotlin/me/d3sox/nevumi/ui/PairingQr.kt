package me.d3sox.nevumi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_pairing_qr_code
import me.d3sox.nevumi.resources.sources_scan_pairing_hint
import me.d3sox.nevumi.resources.sources_scan_pairing_qr_code
import me.d3sox.nevumi.resources.sources_use_manual_code
import org.jetbrains.compose.resources.stringResource

internal data class QrMatrix(val size: Int, val modules: BooleanArray)
internal expect fun pairingQrMatrix(invitation: String): QrMatrix

@Composable
internal fun PairingQr(invitation: String, modifier: Modifier = Modifier) {
    val matrix = remember(invitation) { pairingQrMatrix(invitation) }
    val description = stringResource(Res.string.sources_pairing_qr_code)
    Canvas(modifier.sizeIn(maxWidth = 360.dp, maxHeight = 360.dp).fillMaxWidth().aspectRatio(1f)
        .background(Color.White).semantics { contentDescription = description }) {
        val module = floor(size.width / matrix.size)
        val inset = (size.width - module * matrix.size) / 2
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) if (matrix.modules[y * matrix.size + x]) {
            drawRect(Color.Black, Offset(inset + x * module, inset + y * module), Size(module, module))
        }
    }
}

@Composable
internal expect fun PairingCameraPreview(modifier: Modifier, onScanned: (String) -> Unit, onError: (String) -> Unit)

internal val LocalPairingCamera = staticCompositionLocalOf<@Composable (Modifier, (String) -> Unit, (String) -> Unit) -> Unit> {
    { modifier, scanned, error -> PairingCameraPreview(modifier, scanned, error) }
}

@Composable
internal fun PairingScanner(onScanned: (String) -> Unit, dismiss: () -> Unit, useManualCode: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.sources_scan_pairing_qr_code)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.sources_scan_pairing_hint))
            LocalPairingCamera.current(Modifier.fillMaxWidth().heightIn(max = 320.dp), onScanned) { error = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = useManualCode) { Text(stringResource(Res.string.sources_use_manual_code)) } })
}
