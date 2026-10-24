package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

internal class DerivedResult(val keys: List<Any?>, val refreshKeys: List<Any?>, val value: Any?, val costMs: Long = 0,
    val pending: Boolean = false)

/** Keeps derived library data while its tab is off screen, so switching back does not compute it again. */
internal class DerivedResults {
    private val results = mutableMapOf<String, DerivedResult>()
    internal operator fun get(id: String) = results[id]
    internal operator fun set(id: String, result: DerivedResult) { results[id] = result }
}

internal val LocalDerivedResults = staticCompositionLocalOf<DerivedResults?> { null }

private class DerivedCell(var result: DerivedResult?) { val version = mutableIntStateOf(0) }

/** Derivations that last longer than this on the main thread would be felt as a hitch, so they move to the background. */
private const val INLINE_COMPUTE_MS = 5L

/**
 * Like `remember(keys) { compute() }`, for library data that is expensive to derive.
 *
 * Once a value is ready, a change to [keys] computes immediately, like `remember`. A change to only [refreshKeys], such as the
 * library after one song's favorite changed or the listening history that playback checkpoints every few seconds,
 * computes it off the main thread and keeps the previous value until the new one is ready, unless the previous
 * computation was quick enough to repeat inline. With an [id], the value outlives this composable inside the library,
 * so returning to a tab reuses it. With [initial], calculations run in the background until the first value is ready.
 */
@Composable
@Suppress("UNCHECKED_CAST")
internal fun <T> rememberDerived(id: String?, keys: List<Any?>, refreshKeys: List<Any?> = emptyList(),
    initial: (() -> T)? = null, compute: () -> T): T {
    val results = LocalDerivedResults.current.takeIf { id != null }
    val latest = rememberUpdatedState(compute)
    val cell = remember { DerivedCell(id?.let { results?.get(it) }) }
    cell.version.intValue
    fun computed(): DerivedResult {
        val start = TimeSource.Monotonic.markNow()
        val value = compute()
        return DerivedResult(keys, refreshKeys, value, start.elapsedNow().inWholeMilliseconds)
    }
    // The cell is a cache of a pure function of its keys, so updating it during composition is safe.
    val previous = cell.result
    val result = when {
        initial != null && (previous == null || (previous.pending && previous.keys != keys)) ->
            DerivedResult(keys, refreshKeys, initial(), pending = true)
        previous == null || previous.keys != keys -> computed()
        previous.pending -> previous
        previous.refreshKeys != refreshKeys && previous.costMs < INLINE_COMPUTE_MS -> computed()
        else -> previous
    }
    if (result !== previous) {
        cell.result = result
        if (id != null) results?.set(id, result)
    }
    LaunchedEffect(keys, refreshKeys) {
        if (cell.result?.let { it.keys == keys && (it.pending || it.refreshKeys != refreshKeys) } != true) return@LaunchedEffect
        val start = TimeSource.Monotonic.markNow()
        val value = withContext(Dispatchers.Default) { latest.value() }
        cell.result = DerivedResult(keys, refreshKeys, value, start.elapsedNow().inWholeMilliseconds).also { if (id != null) results?.set(id, it) }
        cell.version.intValue++
    }
    return result.value as T
}
