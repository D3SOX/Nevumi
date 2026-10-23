package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_redone
import me.d3sox.nevumi.resources.notice_undone
import org.jetbrains.compose.resources.StringResource

/** What every domain controller shares: the coroutine scope, the app state, the operation lock and the action helpers. */
internal class ControllerContext {
    val supervisor = SupervisorJob()
    val scope = CoroutineScope(supervisor + Dispatchers.Default)
    /** Serializes the actions that read and change the queue, the library and the player together. */
    val operations = Mutex()
    val mutableState = MutableStateFlow(AppState())
    val mutableSeeks = MutableSharedFlow<PlaybackSeek>()
    val mutableNotices = MutableSharedFlow<ActionNotice>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val state = mutableState.asStateFlow()
    val seeks = mutableSeeks.asSharedFlow()
    val notices = mutableNotices.asSharedFlow()

    /** Inline like [MutableStateFlow.update], so a state change may read suspending sources. */
    inline fun update(change: (AppState) -> AppState) = mutableState.update(change)

    /** Runs [block] on the scope, serialized with the other operations unless told otherwise, and reports a failure as the app error. */
    fun action(serialized: Boolean = true, block: suspend () -> Unit) = scope.launch {
        try { if (serialized) operations.withLock { block() } else block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.update { it.copy(error = error.message ?: "Operation failed", loading = it.serverRefresh?.running == true) } }
    }

    /** Shows [message] as a notice; with [undo] it offers a reversal that in turn offers [redo], alternating until one is used. */
    fun confirm(message: StringResource, destination: ActionDestination? = null, serializedUndo: Boolean = true,
        undo: (suspend () -> Unit)? = null, redo: (suspend () -> Unit)? = null, isRedo: Boolean = false,
        redoCreatesNotice: Boolean = false, arguments: List<Any> = emptyList()) {
        var used = false
        val reverse: (() -> Job)? = undo?.let { change -> { action(serialized = serializedUndo) {
            if (!used) {
                change(); used = true
                if (!(isRedo && redoCreatesNotice)) confirm(if (isRedo) Res.string.notice_redone else Res.string.notice_undone, destination,
                    serializedUndo, undo = redo, redo = undo, isRedo = !isRedo, redoCreatesNotice = redoCreatesNotice)
            }
        } } }
        mutableNotices.tryEmit(ActionNotice(message, destination, reverse, isRedo, arguments))
    }

    fun dismissError(error: String) { mutableState.update { if (it.error == error) it.copy(error = null) else it } }
}
