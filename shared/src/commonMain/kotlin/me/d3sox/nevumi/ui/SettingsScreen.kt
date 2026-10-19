package me.d3sox.nevumi.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal expect fun PlatformBackHandler(onBack: () -> Unit)

@Composable
internal expect fun PlatformPredictiveBackHandler(enabled: Boolean, onStarted: () -> Unit,
    onProgress: (Float) -> Unit, onCancelled: () -> Unit, onBack: () -> Unit)

@Composable
internal fun SettingsScreen(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null, actions: (@Composable () -> Unit)? = null) {
    if (!LocalPageBackHandled.current) PlatformBackHandler(onDismissRequest)
    Surface(Modifier.fillMaxSize().testTag("settings-page").onPreviewKeyEvent {
        if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onDismissRequest(); true } else false
    }) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(AppIcon.BACK, "Back", onClick = onDismissRequest)
                Box(Modifier.weight(1f)) { ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() } }
                actions?.invoke()
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) { CompositionLocalProvider(LocalSettingsCards provides true) { text() } }
            if (confirmButton != null || dismissButton != null) Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) { confirmButton?.invoke(); dismissButton?.invoke() }
        }
    }
}

@Composable
internal fun PageAction(label: String, icon: AppIcon = AppIcon.CHECK, enabled: Boolean = true,
    modifier: Modifier = Modifier, secondary: Boolean = false, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon.vector, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
    val button = modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (secondary) OutlinedButton(onClick, button, enabled = enabled, content = content)
    else Button(onClick, button, enabled = enabled, content = content)
}

internal enum class ActionStyle { TONAL, OUTLINED, DESTRUCTIVE }

@Composable
internal fun ActionButton(label: String, icon: AppIcon, modifier: Modifier = Modifier, enabled: Boolean = true,
    style: ActionStyle = ActionStyle.TONAL, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon.vector, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
    when (style) {
        ActionStyle.TONAL -> FilledTonalButton(onClick, modifier, enabled = enabled, content = content)
        ActionStyle.OUTLINED -> OutlinedButton(onClick, modifier, enabled = enabled, content = content)
        ActionStyle.DESTRUCTIVE -> OutlinedButton(onClick, modifier, enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            content = content)
    }
}
