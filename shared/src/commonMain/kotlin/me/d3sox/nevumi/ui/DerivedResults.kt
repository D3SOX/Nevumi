package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DerivedResult(val keys: List<Any?>, val refreshKeys: List<Any?>, val value: Any?)

/** Keeps derived library data while its tab is off screen, so switching back does not compute it again. */
internal class DerivedResults {
    private val results = mutableMapOf<String, DerivedResult>()
    internal operator fun get(id: String) = results[id]
    internal operator fun set(id: String, result: DerivedResult) { results[id] = result }
}

internal val LocalDerivedResults = staticCompositionLocalOf<DerivedResults?> { null }

private class DerivedCell(var result: DerivedResult?) { val version = mutableIntStateOf(0) }

/**
 * Like `remember(keys) { compute() }`, for library data that is expensive to derive.
 *
 * A change to [keys] computes the value immediately, like `remember`. A change to only [refreshKeys], such as the
 * listening history that playback checkpoints every few seconds, computes it off the main thread and keeps the
 * previous value until the new one is ready. With an [id], the value outlives this composable inside the library,
 * so returning to a tab reuses it.
 */
@Composable
@Suppress("UNCHECKED_CAST")
internal fun <T> rememberDerived(id: String?, keys: List<Any?>, refreshKeys: List<Any?> = emptyList(), compute: () -> T): T {
    val results = LocalDerivedResults.current.takeIf { id != null }
    val latest = rememberUpdatedState(compute)
    val cell = remember { DerivedCell(id?.let { results?.get(it) }) }
    cell.version.intValue
    // The cell is a cache of a pure function of its keys, so updating it during composition is safe.
    val result = cell.result?.takeIf { it.keys == keys } ?: DerivedResult(keys, refreshKeys, compute()).also { result ->
        cell.result = result
        if (id != null) results?.set(id, result)
    }
    LaunchedEffect(keys, refreshKeys) {
        if (cell.result?.let { it.keys == keys && it.refreshKeys != refreshKeys } != true) return@LaunchedEffect
        val value = withContext(Dispatchers.Default) { latest.value() }
        cell.result = DerivedResult(keys, refreshKeys, value).also { if (id != null) results?.set(id, it) }
        cell.version.intValue++
    }
    return result.value as T
}
