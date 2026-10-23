package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_equalizer_apo_text
import me.d3sox.nevumi.resources.sources_import_equalizer_profile
import org.jetbrains.compose.resources.getString

@Composable
internal actual fun rememberEqualizerFiles(): EqualizerFiles = remember { DesktopEqualizerFiles() }

class DesktopEqualizerFiles(address: String? = null) : EqualizerFiles {
    private val portal = PortalFileChooser(address)
    override suspend fun open(): EqualizerDocument? {
        val file = portal.choose(getString(Res.string.sources_import_equalizer_profile), filter = portalFilter(getString(Res.string.sources_equalizer_apo_text), "*.[tT][xX][tT]")) ?: return null
        return withContext(Dispatchers.IO) {
            EqualizerDocument(file.nameWithoutExtension.removeSuffix(" ParametricEQ").removeSuffix(" FixedBandEQ"),
                file.inputStream().use(::readEqualizer))
        }
    }
}
