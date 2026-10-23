package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Picks backup files: [open] returns the chosen file's text, or null when nothing was chosen. */
interface BackupFiles {
    suspend fun open(): String?
    suspend fun save(name: String, text: String): Boolean
}

internal val LocalBackupFiles = staticCompositionLocalOf<BackupFiles> { error("Backup files are unavailable") }
@Composable internal expect fun rememberBackupFiles(): BackupFiles
