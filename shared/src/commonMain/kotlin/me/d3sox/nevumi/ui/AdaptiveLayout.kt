package me.d3sox.nevumi.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How the app arranges its navigation and player for the space it has. */
internal enum class WindowLayout {
    /** A bottom navigation bar, a mini player and a full-screen player, as on phones in portrait. */
    COMPACT,
    /** A navigation rail beside the library; the player stays a mini player that expands to full screen. */
    MEDIUM,
    /** A navigation rail and a Now Playing pane beside the library. */
    EXPANDED,
}

/** The standard Material breakpoint for medium windows. */
private val MediumWindowWidth = 600.dp
private val NowPlayingPaneMinWidth = 360.dp
private val NowPlayingPaneMaxWidth = 440.dp
private val NavigationRailWidth = 80.dp
/** The pane appears once the rail, the narrowest pane and a medium-width library fit side by side. */
private val ExpandedWindowWidth = NavigationRailWidth + NowPlayingPaneMinWidth + MediumWindowWidth
/**
 * Material's compact height. Below it the pane could not fit the artwork above the controls, and a rail would fit fewer pages than
 * the wide bottom bar of a phone in landscape.
 */
private val CompactWindowHeight = 480.dp

internal fun windowLayout(width: Dp, height: Dp) = when {
    height < CompactWindowHeight -> WindowLayout.COMPACT
    width >= ExpandedWindowWidth -> WindowLayout.EXPANDED
    width >= MediumWindowWidth -> WindowLayout.MEDIUM
    else -> WindowLayout.COMPACT
}

/** The pane grows with the window, so a large window does not leave the library all the extra width. */
internal fun nowPlayingPaneWidth(windowWidth: Dp) = (windowWidth * .3f).coerceIn(NowPlayingPaneMinWidth, NowPlayingPaneMaxWidth)

internal val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.COMPACT }

/** A window shorter than Material's compact height, such as an app in split screen or a phone in landscape. */
internal fun isShortWindow(height: Dp) = height < CompactWindowHeight

internal val LocalShortWindow = staticCompositionLocalOf { false }
