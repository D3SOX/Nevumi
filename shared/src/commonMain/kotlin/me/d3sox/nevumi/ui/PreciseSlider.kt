package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.round
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.library_decrease
import me.d3sox.nevumi.resources.library_increase
import org.jetbrains.compose.resources.stringResource

/** A labeled slider whose step buttons sit in the header so the track keeps the full width. */
@Composable
internal fun PreciseSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    modifier: Modifier = Modifier, enabled: Boolean = true, visibleLabel: String = label, change: (Float) -> Unit, finish: () -> Unit) {
    val step = (range.endInclusive - range.start) / if (steps > 0) steps + 1 else 100
    fun adjust(direction: Int) {
        change((range.start + round((value - range.start) / step + direction) * step).coerceIn(range))
        finish()
    }
    Column(modifier.fillMaxWidth()) {
        // The value sits under the label so neither shares its width with the other, and the buttons stay put as the value changes.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(visibleLabel, style = MaterialTheme.typography.bodyLarge)
                Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIconButton(AppIcon.MINUS, stringResource(Res.string.library_decrease, label), enabled = enabled && value > range.start) { adjust(-1) }
            AppIconButton(AppIcon.PLUS, stringResource(Res.string.library_increase, label), enabled = enabled && value < range.endInclusive) { adjust(1) }
        }
        Slider(value, change, Modifier.fillMaxWidth().semantics { contentDescription = label }, enabled = enabled,
            valueRange = range, steps = steps, colors = appSliderColors(), onValueChangeFinished = finish)
    }
}
