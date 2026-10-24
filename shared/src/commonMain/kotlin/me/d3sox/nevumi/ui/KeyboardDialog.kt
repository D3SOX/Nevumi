package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Window properties that keep the system from panning or resizing a dialog while the keyboard is open. The dialog handles insets itself. */
internal expect val KeyboardDialogProperties: DialogProperties

/** An [AlertDialog] with text fields. It keeps clear of the keyboard and system bars itself instead of jumping while you type. */
@Composable
internal fun KeyboardAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties? = null,
) {
    // A landscape phone leaves too little room above the keyboard for the whole dialog, so let the system pan it there.
    val roomy = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } >= 480.dp
    AlertDialog(onDismissRequest, confirmButton, if (roomy) modifier.windowInsetsPadding(WindowInsets.safeDrawing) else modifier,
        dismissButton, icon, title, text, properties = properties ?: if (roomy) KeyboardDialogProperties else DialogProperties())
}
