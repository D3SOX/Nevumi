package me.d3sox.nevumi.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AmoledThemeTest {
    @Test fun blackSystemCardsBecomeGrayAgainstTheAmoledBackground() {
        val gray = Color(0xFF0E0E12)
        val system = darkColorScheme(background = gray, surface = gray,
            surfaceContainerLow = Color.Black, surfaceContainer = Color.Black,
            surfaceContainerHigh = Color.Black, surfaceContainerHighest = Color.Black)

        val amoled = system.withAmoledBlack(true)

        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color.Black, amoled.surfaceContainerLowest)
        assertEquals(gray, amoled.surfaceContainerLow)
        assertEquals(gray, amoled.surfaceContainer)
        assertEquals(gray, amoled.surfaceContainerHigh)
        assertEquals(gray, amoled.surfaceContainerHighest)
        assertEquals(system.primary, amoled.primary)
        assertEquals(system.onSurface, amoled.onSurface)
    }

    @Test fun alreadyBlackSystemBackgroundStillGetsVisibleCards() {
        val amoled = darkColorScheme(background = Color.Black, surface = Color.Black,
            surfaceContainer = Color.Black).withAmoledBlack(true)
        assertTrue(amoled.surfaceContainer.luminance() > 0.01f)
    }

    @Test fun normalDarkCardsKeepTheirElevationColors() {
        val normal = darkColorScheme()
        val amoled = normal.withAmoledBlack(true)
        assertEquals(normal.surfaceContainerLow, amoled.surfaceContainerLow)
        assertEquals(normal.surfaceContainer, amoled.surfaceContainer)
        assertEquals(normal.surfaceContainerHigh, amoled.surfaceContainerHigh)
        assertEquals(normal.surfaceContainerHighest, amoled.surfaceContainerHighest)
    }

    @Test fun disabledOverridePreservesLightAndDarkSchemes() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            assertSame(scheme, scheme.withAmoledBlack(false))
        }
    }
}
