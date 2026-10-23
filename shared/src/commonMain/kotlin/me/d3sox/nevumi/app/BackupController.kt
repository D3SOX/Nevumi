package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.playback.PlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.backup.BackupFormatException
import me.d3sox.nevumi.backup.MusicBackup
import me.d3sox.nevumi.backup.RestoreSummary
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.backup_error_export
import me.d3sox.nevumi.resources.backup_error_invalid
import me.d3sox.nevumi.resources.backup_error_restore
import me.d3sox.nevumi.resources.backup_error_importing
import me.d3sox.nevumi.resources.backup_error_unavailable
import me.d3sox.nevumi.resources.backup_error_version
import me.d3sox.nevumi.resources.backup_notice_exported
import me.d3sox.nevumi.resources.backup_notice_restored
import me.d3sox.nevumi.ui.BackupFiles
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock

/** Whether a backup is being written or restored, and what the last restore changed until it is dismissed. */
data class BackupStatus(val working: Boolean = false, val summary: RestoreSummary? = null)

/** Writes the app's data to a backup file and merges a backup file back, through the file picker. */
class BackupController internal constructor(private val context: ControllerContext, private val loader: AppStateLoader,
    private val playback: PlaybackController, private val fileEditing: FileEditController, private val queue: PlaybackQueue,
    private val engine: PlaybackEngine, private val backup: MusicBackup?) {
    val backupAvailable: Boolean get() = backup != null
    private val mutableStatus = MutableStateFlow(BackupStatus())
    val status = mutableStatus.asStateFlow()

    fun export(files: BackupFiles, appVersion: String = "") = run(Res.string.backup_error_export) {
        val text = requireNotNull(backup).export(appVersion)
        if (files.save(backupFileName(Clock.System.now().toEpochMilliseconds()), text)) context.confirm(Res.string.backup_notice_exported)
    }

    fun restore(files: BackupFiles) = run(Res.string.backup_error_restore) {
        val text = files.open() ?: return@run
        // Settings are not observed; reload them with the library and apply them as changing them in Settings does,
        // also after a failure, because each restored part is saved as it goes.
        val previous = context.state.value.preferences
        val summary = try { requireNotNull(backup).restore(text) } finally {
            withContext(NonCancellable) { context.operations.withLock {
                loader.refresh()
                playback.applyPreferences()
                val settings = context.state.value.preferences
                if (playback.queueLoaded && settings.sourcePriority != previous.sourcePriority)
                    engine.updateQueue(playback.playableQueue(queue.snapshot()))
                if (settings.undoRetentionDays != previous.undoRetentionDays || settings.undoBackupBytes != previous.undoBackupBytes)
                    fileEditing.trimFileHistory()
            } }
        }
        mutableStatus.update { it.copy(summary = summary) }
        context.confirm(Res.string.backup_notice_restored)
    }

    fun dismissSummary() = mutableStatus.update { it.copy(summary = null) }

    /** Runs outside the operation lock: the picker waits for the user and the services keep their own transactions. */
    private fun run(failure: org.jetbrains.compose.resources.StringResource, block: suspend () -> Unit) = context.action(serialized = false) {
        if (backup == null) throw IllegalStateException(getString(Res.string.backup_error_unavailable))
        // A backup taken or matched while songs are still importing would miss the songs imported later.
        val state = context.state.value
        if (state.localScan != null || state.serverRefresh?.running == true) throw IllegalStateException(getString(Res.string.backup_error_importing))
        // One step, so two quick taps cannot both start.
        if (mutableStatus.getAndUpdate { it.copy(working = true) }.working) return@action
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: BackupFormatException) {
            throw IllegalArgumentException(error.version?.let { getString(Res.string.backup_error_version, it) } ?: getString(Res.string.backup_error_invalid))
        }
        catch (error: Exception) { throw IllegalStateException(getString(failure, error.message.orEmpty()), error) }
        finally { mutableStatus.update { it.copy(working = false) } }
    }
}

/** `nevumi-backup-yyyy-MM-dd.json` for the UTC date of [epochMillis]. */
internal fun backupFileName(epochMillis: Long) =
    "nevumi-backup-${kotlin.time.Instant.fromEpochMilliseconds(epochMillis).toString().take(10)}.json"
