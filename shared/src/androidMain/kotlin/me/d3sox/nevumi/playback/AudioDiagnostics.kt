package me.d3sox.nevumi.playback

import android.content.Context
import android.util.Log
import me.d3sox.nevumi.diagnostics.DiagnosticLog
import java.io.File
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/** The local log of audio gaps. It stays in the app's private storage, which backups exclude. */
internal object AudioDiagnostics {
    private const val TAG = "NevumiAudio"
    /** Writes the log and checks the playback threads, away from the threads it watches. */
    val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "Nevumi audio diagnostics").apply { isDaemon = true } }
    @Volatile private var log: DiagnosticLog? = null

    fun record(context: Context, event: String) {
        val line = "${OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS)} $event"
        Log.w(TAG, line)
        executor.execute { runCatching { log(context).append(line) } }
    }

    // Every decoder records into one file, so one log serializes the writes.
    private fun log(context: Context) = log ?: synchronized(this) {
        log ?: DiagnosticLog(File(context.filesDir, "diagnostics/audio-underruns.log"), maxBytes = 256 * 1024).also { log = it }
    }
}
