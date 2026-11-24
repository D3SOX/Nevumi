package me.d3sox.nevumi.playback

import android.content.Context
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import me.d3sox.nevumi.diagnostics.StallWatchdog
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Records what holds a playing decoder's playback thread once it has not offered audio to the output for a second, which is
 * twice the output buffer. The stack and kernel state name a lock, a system call or the code it runs; the CPU time the thread
 * got during the gap tells a blocked thread from one the system did not schedule.
 */
internal class PlaybackStallWatchdog(context: Context) {
    private val context = context.applicationContext
    @Volatile private var tid = 0
    private var checks: ScheduledFuture<*>? = null
    private val watchdog = StallWatchdog(STALL_MS, SystemClock::elapsedRealtime, { cpu() }, ::stalled, ::ended)

    /** The playback thread offers audio on every pass while playing, even to a full output. */
    fun offered() {
        if (tid == 0) tid = Process.myTid()
        watchdog.progress()
    }

    @Synchronized fun playing() {
        watchdog.start()
        // A failed check would cancel the schedule, so each one is contained.
        if (checks == null) checks = AudioDiagnostics.executor.scheduleWithFixedDelay({ runCatching { watchdog.check() } },
            CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun stopped() {
        checks?.cancel(false)
        checks = null
        runCatching { watchdog.stop() }
    }

    private fun stalled(thread: Thread, stalledMs: Long) = AudioDiagnostics.record(context, listOf("stall", "thread=${thread.name}",
        "tid=$tid", "stalledMs=$stalledMs", "state=${thread.state}", "kernel=${task("stat")?.substringAfterLast(") ")?.take(1)}",
        "wchan=${task("wchan")}", "stack=${frames(thread.stackTrace, 40)}", "main=${frames(Looper.getMainLooper().thread.stackTrace, 15)}").joinToString(" "))

    private fun ended(thread: Thread, stalledMs: Long, resumed: Boolean, before: Cpu?, after: Cpu?) {
        val usage = if (before != null && after != null) listOf("ranMs=${(after.runNs - before.runNs) / 1_000_000}",
            "waitedForCpuMs=${(after.waitNs - before.waitNs) / 1_000_000}", "switches=${after.slices - before.slices}")
            else listOf("cpu=unknown")
        AudioDiagnostics.record(context, (listOf("stall-end", "thread=${thread.name}", "stalledMs=$stalledMs", "resumed=$resumed") + usage)
            .joinToString(" "))
    }

    /** Run and runqueue time since the thread started, from the kernel's scheduler statistics. */
    private data class Cpu(val runNs: Long, val waitNs: Long, val slices: Long)

    private fun cpu(): Cpu? = task("schedstat")?.split(' ')?.mapNotNull(String::toLongOrNull)?.takeIf { it.size >= 3 }
        ?.let { Cpu(it[0], it[1], it[2]) }

    private fun task(name: String) =
        tid.takeIf { it != 0 }?.let { runCatching { File("/proc/self/task/$it/$name").readText().trim() }.getOrNull() }

    private fun frames(stack: Array<StackTraceElement>, limit: Int) = stack.take(limit).joinToString(" < ").ifEmpty { "none" }

    private companion object {
        const val STALL_MS = 1_000L
        const val CHECK_MS = 250L
    }
}
