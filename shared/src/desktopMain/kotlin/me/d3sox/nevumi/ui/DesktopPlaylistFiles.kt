package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_export_playlist
import me.d3sox.nevumi.resources.sources_import_playlist
import me.d3sox.nevumi.resources.sources_m3u_playlists
import org.jetbrains.compose.resources.getString

@Composable
internal actual fun rememberPlaylistFiles(): PlaylistFiles = remember { DesktopPlaylistFiles() }

class DesktopPlaylistFiles(address: String? = null) : PlaylistFiles {
    private val portal = PortalFileChooser(address)
    private suspend fun filter() = portalFilter(getString(Res.string.sources_m3u_playlists), "*.[mM]3[uU]", "*.[mM]3[uU]8")
    override suspend fun open(): PlaylistDocument? {
        val file = portal.choose(getString(Res.string.sources_import_playlist), filter = filter()) ?: return null
        return withContext(Dispatchers.IO) {
            PlaylistDocument(file.nameWithoutExtension, file.inputStream().use(::readPlaylist), file.absoluteFile.parent)
        }
    }

    override suspend fun save(name: String, text: String): Boolean =
        portal.save(getString(Res.string.sources_export_playlist), filter(), name, text)
}
