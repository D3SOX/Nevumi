package me.d3sox.nevumi.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.off
import me.d3sox.nevumi.resources.player_plays_remaining
import me.d3sox.nevumi.resources.player_repeat
import me.d3sox.nevumi.resources.player_repeat_count
import me.d3sox.nevumi.resources.player_repeat_song
import me.d3sox.nevumi.resources.queue
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

@Composable
internal fun PlayerRepeatControl(queue: QueueSnapshot, controller: MusicController, onRepeatCount: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        val active = queue.repeatMode != RepeatMode.OFF
        FilledIconButton(onClick = { menu = true }, modifier = modifier.semantics {
            selected = active; stateDescription = queue.repeatMode.name
        }, colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)) {
            Icon((if (queue.repeatMode == RepeatMode.ONE) AppIcon.REPEAT_ONE else AppIcon.REPEAT).vector, stringResource(Res.string.player_repeat))
        }
        DropdownMenu(menu, { menu = false }) {
            listOf(RepeatMode.OFF to stringResource(Res.string.off), RepeatMode.ONE to stringResource(Res.string.player_repeat_song), RepeatMode.ALL to stringResource(Res.string.queue)).forEach { (mode, name) ->
                AppMenuItem(text = { Text(name) }, leadingIcon = { Icon(when (mode) {
                    RepeatMode.OFF -> AppIcon.CLOSE; RepeatMode.ONE -> AppIcon.REPEAT_ONE; RepeatMode.ALL -> AppIcon.REPEAT
                }.vector, null) }, trailingIcon = { if (mode == queue.repeatMode) Icon(AppIcon.CHECK.vector, null) },
                    onClick = { controller.setQueueRepeat(mode); menu = false })
            }
            HorizontalDivider()
            AppMenuItem(text = { Text(stringResource(Res.string.player_repeat_count)) }, leadingIcon = { Icon(AppIcon.REPEAT_COUNT.vector, null) },
                onClick = { menu = false; onRepeatCount() })
            queue.repeatPlays?.let { remaining ->
                AppMenuItem(text = { Text(pluralStringResource(Res.plurals.player_plays_remaining, remaining, remaining)) }, enabled = false, onClick = {})
            }
        }
    }
}
