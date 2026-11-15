package me.d3sox.nevumi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.artwork_loading
import org.jetbrains.compose.resources.stringResource

internal expect fun decodeArtwork(bytes: ByteArray, maxDimension: Int): ImageBitmap?

/** A cover as far as it is read: [loading] until [image] is decoded, which stays null for a missing or unreadable cover. */
internal data class ArtworkImage(val image: ImageBitmap?, val loading: Boolean)

@Composable
internal fun rememberArtwork(path: String?, maxDimension: Int = 256): ImageBitmap? = rememberArtworkImage(path, maxDimension).image

@Composable
private fun rememberArtworkImage(path: String?, maxDimension: Int): ArtworkImage {
    // Keyed state, so a new path shows as loading in its first frame rather than as the previous path's result.
    val image = remember(path, maxDimension) {
        mutableStateOf(ArtworkMemoryCache.peek(path, maxDimension).let { ArtworkImage(it, it == null && path != null) })
    }
    LaunchedEffect(path, maxDimension) {
        if (path != null) image.value = ArtworkImage(ArtworkMemoryCache.load(path, maxDimension), loading = false)
    }
    return image.value
}

/** The cover of the song [trackId], which shows as loading while the app still downloads it. */
@Composable
internal fun MusicArtwork(state: AppState, trackId: String, title: String, description: String? = null, maxDimension: Int = 256) =
    MusicArtwork(state.artwork[trackId], title, description, maxDimension, downloading = trackId in state.downloadingArtwork)

@Composable
internal fun MusicArtwork(path: String?, title: String, description: String? = null, maxDimension: Int = 256, downloading: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        val artwork = rememberArtworkImage(path, maxDimension)
        if (artwork.image != null) Image(artwork.image, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else ArtworkPlaceholder(artwork.loading || path == null && downloading, AppIcon.MUSIC, description, Modifier.fillMaxSize(.45f))
    }
}

/** A cover slot without an image: a loading indicator while the cover is read, otherwise [icon], which marks a missing one. */
@Composable
internal fun ArtworkPlaceholder(loading: Boolean, icon: AppIcon, description: String?, iconModifier: Modifier) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            val loadingDescription = stringResource(Res.string.artwork_loading)
            // Covers that decode right away appear without the indicator flashing up first.
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(ARTWORK_INDICATOR_DELAY_MS)
                shown = true
            }
            Box(Modifier.fillMaxSize().semantics {
                description?.let { contentDescription = it }
                stateDescription = loadingDescription
            }, contentAlignment = Alignment.Center) {
                // 70 % of the slot, but no larger than the indicator's standard 48 dp.
                if (shown) LoadingIndicator(Modifier.sizeIn(maxWidth = 68.dp, maxHeight = 68.dp).fillMaxSize(.7f),
                    MaterialTheme.colorScheme.onSecondaryContainer)
            }
        } else Icon(icon.vector, description, iconModifier, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private const val ARTWORK_INDICATOR_DELAY_MS = 200L
