package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/** Window properties that keep the system from panning or resizing a dialog while the keyboard is open. */
internal expect val KeyboardDialogProperties: DialogProperties

/** An [AlertDialog] with text fields. It moves above the keyboard itself instead of jumping while you type. */
@Composable
internal fun KeyboardAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) = AlertDialog(onDismissRequest, confirmButton, modifier.imePadding(), dismissButton, icon, title, text,
    properties = KeyboardDialogProperties)
