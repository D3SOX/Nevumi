package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.backup_export
import me.d3sox.nevumi.resources.backup_file_type
import me.d3sox.nevumi.resources.backup_restore
import org.jetbrains.compose.resources.getString

@Composable
internal actual fun rememberBackupFiles(): BackupFiles = remember { DesktopBackupFiles() }

class DesktopBackupFiles(address: String? = null) : BackupFiles {
    private val portal = PortalFileChooser(address)
    private suspend fun filter() = portalFilter(getString(Res.string.backup_file_type), "*.[jJ][sS][oO][nN]")

    override suspend fun open(): String? {
        val file = portal.choose(getString(Res.string.backup_restore), filter = filter()) ?: return null
        return withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
    }

    override suspend fun save(name: String, text: String): Boolean =
        portal.save(getString(Res.string.backup_export), filter(), name, text)
}
