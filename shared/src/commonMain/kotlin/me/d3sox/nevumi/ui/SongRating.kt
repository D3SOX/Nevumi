package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.player_clear_rating
import me.d3sox.nevumi.resources.player_confirm
import me.d3sox.nevumi.resources.player_not_rated
import me.d3sox.nevumi.resources.player_rate_song
import me.d3sox.nevumi.resources.player_rating_out_of_five
import me.d3sox.nevumi.resources.player_stars
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

@Composable
internal fun SongRating(track: Track, controller: MusicController, dismiss: () -> Unit) {
    var closed by remember(track.id) { mutableStateOf(false) }
    fun close() { closed = true; dismiss() }
    var draft by remember(track.id) { mutableIntStateOf(track.rating) }
    fun confirm() {
        if (closed) return
        closed = true
        controller.setRating(track.id, draft)
        dismiss()
    }
    var preview by remember(track.id) { mutableStateOf<Int?>(null) }
    val selectedRating = preview ?: draft
    AlertDialog(onDismissRequest = ::close, title = { Text(stringResource(Res.string.player_rate_song)) }, text = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(track.metadata.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.widthIn(max = 240.dp).fillMaxWidth().pointerInput(track.id) {
                fun ratingAt(x: Float) = (x / size.width.coerceAtLeast(1) * 5).toInt().plus(1).coerceIn(1, 5)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    down.consume()
                    preview = ratingAt(down.position.x)
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            change.consume()
                            preview = ratingAt(change.position.x)
                            if (!change.pressed) {
                                if (!closed) draft = requireNotNull(preview)
                                break
                            }
                        }
                    } finally { preview = null }
                }
            }) {
                for (rating in 1..5) IconButton(onClick = { draft = rating },
                    modifier = Modifier.weight(1f).height(48.dp)
                        .background(if (preview == rating) MaterialTheme.colorScheme.onSurface.copy(alpha = .12f) else Color.Transparent, CircleShape)
                        .semantics { selected = rating <= selectedRating }) {
                    Icon((if (rating <= selectedRating) AppIcon.RATING_FILLED else AppIcon.RATING).vector,
                        pluralStringResource(Res.plurals.player_stars, rating, rating), Modifier.size(32.dp),
                        tint = if (rating <= selectedRating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(if (selectedRating == 0) stringResource(Res.string.player_not_rated) else stringResource(Res.string.player_rating_out_of_five, selectedRating), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { draft = 0 }) {
                Icon(AppIcon.CLOSE.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.player_clear_rating))
            }
        }
    }, confirmButton = { Button(onClick = ::confirm) {
        Icon(AppIcon.CHECK.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.player_confirm))
    } }, dismissButton = { TextButton(onClick = ::close) { Text(stringResource(Res.string.cancel)) } })
}
