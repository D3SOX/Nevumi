package me.d3sox.nevumi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.settings_color_amber
import me.d3sox.nevumi.resources.settings_color_blue
import me.d3sox.nevumi.resources.settings_color_brown
import me.d3sox.nevumi.resources.settings_color_coral
import me.d3sox.nevumi.resources.settings_color_cyan
import me.d3sox.nevumi.resources.settings_color_emerald
import me.d3sox.nevumi.resources.settings_color_green
import me.d3sox.nevumi.resources.settings_color_indigo
import me.d3sox.nevumi.resources.settings_color_lime
import me.d3sox.nevumi.resources.settings_color_mint
import me.d3sox.nevumi.resources.settings_color_orange
import me.d3sox.nevumi.resources.settings_color_pink
import me.d3sox.nevumi.resources.settings_color_purple
import me.d3sox.nevumi.resources.settings_color_red
import me.d3sox.nevumi.resources.settings_color_rose
import me.d3sox.nevumi.resources.settings_color_sky_blue
import me.d3sox.nevumi.resources.settings_color_slate
import me.d3sox.nevumi.resources.settings_color_teal
import me.d3sox.nevumi.resources.settings_color_violet
import me.d3sox.nevumi.resources.settings_color_yellow
import me.d3sox.nevumi.resources.settings_custom_color
import me.d3sox.nevumi.resources.settings_custom_color_named
import org.jetbrains.compose.resources.stringResource

private val customColors = listOf(
    Res.string.settings_color_red to 0xFFE53935, Res.string.settings_color_coral to 0xFFFF7043, Res.string.settings_color_orange to 0xFFFB8C00, Res.string.settings_color_amber to 0xFFFFB300,
    Res.string.settings_color_yellow to 0xFFFDD835, Res.string.settings_color_lime to 0xFFC0CA33, Res.string.settings_color_green to 0xFF4CAF50, Res.string.settings_color_emerald to 0xFF00875A,
    Res.string.settings_color_mint to 0xFF4DB6AC, Res.string.settings_color_teal to 0xFF00897B, Res.string.settings_color_cyan to 0xFF00ACC1, Res.string.settings_color_sky_blue to 0xFF29B6F6,
    Res.string.settings_color_blue to 0xFF1E88E5, Res.string.settings_color_indigo to 0xFF3949AB, Res.string.settings_color_purple to 0xFF6750A4, Res.string.settings_color_violet to 0xFF8E24AA,
    Res.string.settings_color_pink to 0xFFEC407A, Res.string.settings_color_rose to 0xFFAD1457, Res.string.settings_color_brown to 0xFF795548, Res.string.settings_color_slate to 0xFF607D8B,
)

@Composable
internal fun CustomColorPicker(value: Long, change: (Long) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(Res.string.settings_custom_color), style = MaterialTheme.typography.bodyLarge)
                Text(customColors.firstOrNull { it.second == value }?.first?.let { stringResource(it) } ?: "#${(value and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()}",
                    style = MaterialTheme.typography.labelLarge)
            }
            FlowRow(Modifier.widthIn(max = 440.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                customColors.forEach { (name, rgb) ->
                    val color = Color(rgb)
                    val selected = value == rgb
                    val description = stringResource(Res.string.settings_custom_color_named, stringResource(name))
                    IconToggleButton(selected, { change(rgb) }, Modifier.size(48.dp).semantics { contentDescription = description },
                        colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = Color.Transparent)) {
                        Box(Modifier.size(40.dp).then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .padding(if (selected) 4.dp else 2.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                            if (selected) Icon(AppIcon.CHECK.vector, null, Modifier.size(22.dp),
                                tint = if (color.luminance() > .179f) Color.Black else Color.White)
                        }
                    }
                }
            }
        }
    }
}
