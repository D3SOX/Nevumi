package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_choose_artwork
import me.d3sox.nevumi.resources.sources_images
import org.jetbrains.compose.resources.getString

@Composable
internal actual fun rememberArtworkPicker(): ArtworkPicker = remember { DesktopArtworkPicker() }

class DesktopArtworkPicker(address: String? = null) : ArtworkPicker {
    private val portal = PortalFileChooser(address)
    override suspend fun pick(): ByteArray? {
        val file = portal.choose(getString(Res.string.sources_choose_artwork), filter = portalFilter(getString(Res.string.sources_images), "image/png", "image/jpeg")) ?: return null
        return withContext(Dispatchers.IO) { file.inputStream().use(::readArtwork) }
    }
}
