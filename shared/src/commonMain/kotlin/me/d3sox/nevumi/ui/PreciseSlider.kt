package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
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

@Composable
internal fun PreciseSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    enabled: Boolean = true, change: (Float) -> Unit, finish: () -> Unit) {
    val step = (range.endInclusive - range.start) / if (steps > 0) steps + 1 else 100
    fun adjust(direction: Int) {
        change((range.start + round((value - range.start) / step + direction) * step).coerceIn(range))
        finish()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(AppIcon.MINUS, stringResource(Res.string.library_decrease, label), enabled = enabled && value > range.start) { adjust(-1) }
        Slider(value, change, Modifier.weight(1f).semantics { contentDescription = label }, enabled = enabled,
            valueRange = range, steps = steps, colors = appSliderColors(), onValueChangeFinished = finish)
        AppIconButton(AppIcon.PLUS, stringResource(Res.string.library_increase, label), enabled = enabled && value < range.endInclusive) { adjust(1) }
    }
}
