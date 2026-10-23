package me.d3sox.nevumi.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.backup_error_picker_open
import me.d3sox.nevumi.resources.backup_error_unreadable
import me.d3sox.nevumi.resources.backup_error_unwritable
import org.jetbrains.compose.resources.getString

@Composable
internal actual fun rememberBackupFiles(): BackupFiles {
    val resolver = LocalContext.current.contentResolver
    var opening by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    var saving by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { opening?.complete(it) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { saving?.complete(it) }
    DisposableEffect(Unit) { onDispose { opening?.cancel(); saving?.cancel() } }
    return remember(resolver, open, save) {
        object : BackupFiles {
            override suspend fun open(): String? {
                val answer = CompletableDeferred<Uri?>()
                try {
                    val busy = getString(Res.string.backup_error_picker_open)
                    withContext(Dispatchers.Main) {
                        check(opening == null && saving == null) { busy }
                        opening = answer
                        open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }
                    val uri = answer.await() ?: return null
                    val unreadable = getString(Res.string.backup_error_unreadable)
                    return withContext(Dispatchers.IO) {
                        requireNotNull(resolver.openInputStream(uri)) { unreadable }.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                } finally { withContext(NonCancellable + Dispatchers.Main) { if (opening === answer) opening = null } }
            }

            override suspend fun save(name: String, text: String): Boolean {
                val answer = CompletableDeferred<Uri?>()
                try {
                    val busy = getString(Res.string.backup_error_picker_open)
                    withContext(Dispatchers.Main) {
                        check(opening == null && saving == null) { busy }
                        saving = answer
                        save.launch(name)
                    }
                    val uri = answer.await() ?: return false
                    val unwritable = getString(Res.string.backup_error_unwritable)
                    withContext(Dispatchers.IO) {
                        requireNotNull(resolver.openOutputStream(uri, "wt")) { unwritable }.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    }
                    return true
                } finally { withContext(NonCancellable + Dispatchers.Main) { if (saving === answer) saving = null } }
            }
        }
    }
}
