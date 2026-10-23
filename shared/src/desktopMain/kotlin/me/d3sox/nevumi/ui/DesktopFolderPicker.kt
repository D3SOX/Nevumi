package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_choose_music_folder
import org.jetbrains.compose.resources.getString

@Composable
internal actual fun rememberFolderPicker(): FolderPicker = remember { DesktopFolderPicker() }

class DesktopFolderPicker(address: String? = null) : FolderPicker {
    private val portal = PortalFileChooser(address)
    override suspend fun pick(): String? = portal.choose(getString(Res.string.sources_choose_music_folder), directory = true)?.absolutePath
}
