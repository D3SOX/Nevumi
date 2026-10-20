package me.d3sox.nevumi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import me.d3sox.nevumi.settings.ThemeMode
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.settings.ColorMode

@Composable
internal expect fun platformColorScheme(dark: Boolean): ColorScheme?

@Composable
internal expect fun platformSystemBars(dark: Boolean)

@Composable
internal fun MusicTheme(settings: MusicSettings, artworkColor: Color?, content: @Composable () -> Unit) {
    val mode = settings.themeMode
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val platform = if (settings.colors == ColorMode.SYSTEM) platformColorScheme(dark) else null
    platformSystemBars(dark)
    val seed = when (settings.colors) {
        ColorMode.ARTWORK -> artworkColor ?: Color(0xFF6750A4)
        ColorMode.CUSTOM -> Color(settings.customColor)
        ColorMode.SYSTEM -> Color(0xFF6750A4)
    }
    val scheme = remember(platform, dark, settings.amoledBlack, seed) {
        val generated = platform ?: dynamicColorScheme(seedColor = seed, isDark = dark,
            style = PaletteStyle.Expressive, specVersion = ColorSpec.SpecVersion.SPEC_2025)
        val accented = generated.copy(secondary = generated.primary, onSecondary = generated.onPrimary,
            secondaryContainer = generated.primaryContainer, onSecondaryContainer = generated.onPrimaryContainer)
        accented.withAmoledBlack(dark && settings.amoledBlack)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

internal fun ColorScheme.withAmoledBlack(enabled: Boolean): ColorScheme {
    if (!enabled) return this
    // Some system palettes supply black cards on a gray surface. Keep that gray
    // for containers when making the canvas black, with a fallback for all-black palettes.
    val cardFloor = listOf(background, surface).maxBy { it.luminance() }
        .takeIf { it.luminance() > 0f } ?: darkColorScheme().surfaceContainerLow
    fun visibleContainer(color: Color) = if (color.luminance() < cardFloor.luminance()) cardFloor else color
    return copy(background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
        surfaceContainerLow = visibleContainer(surfaceContainerLow),
        surfaceContainer = visibleContainer(surfaceContainer),
        surfaceContainerHigh = visibleContainer(surfaceContainerHigh),
        surfaceContainerHighest = visibleContainer(surfaceContainerHighest))
}

@Composable
internal fun appSliderColors() = androidx.compose.material3.SliderDefaults.colors(
    inactiveTrackColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
    inactiveTickColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
    disabledInactiveTrackColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer.copy(alpha = .38f),
)
