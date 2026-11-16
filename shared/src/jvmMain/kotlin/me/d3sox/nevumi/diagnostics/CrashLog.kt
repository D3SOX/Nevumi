package me.d3sox.nevumi.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant

/**
 * Writes uncaught exceptions to [directory] before the platform's own handler ends the process, and keeps the newest
 * [limit] of them. [describe] names the app version and platform for the report's header.
 */
class CrashLog(private val directory: File, private val describe: () -> String, private val limit: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis) : CrashReports {
    private val seenFile = File(directory, "seen")
    private val mutableReports = MutableStateFlow(read())
    override val reports: StateFlow<List<CrashReport>> = mutableReports.asStateFlow()
    private val mutableUnseen = MutableStateFlow(unseenReport())
    override val unseen: StateFlow<CrashReport?> = mutableUnseen.asStateFlow()

    /** Records crashes from now on, then lets the previous handler, such as Android's crash dialog, run as before. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { record(thread.name, error) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized fun record(threadName: String, error: Throwable) {
        directory.mkdirs()
        val time = clock()
        val header = listOf("Nevumi crash report", describe(), "Time: ${Instant.ofEpochMilli(time)}", "Thread: $threadName")
        File(directory, "crash-$time.txt").writeText(header.joinToString("") { "${CrashReport.HEADER_PREFIX}$it\n" } + "\n" + error.stackTraceToString())
        files().drop(limit).forEach { it.delete() }
        refresh()
    }

    @Synchronized override fun markSeen() {
        reports.value.firstOrNull()?.let { newest -> directory.mkdirs(); seenFile.writeText(newest.time.toString()) }
        mutableUnseen.value = null
    }

    @Synchronized override fun delete(id: String) {
        files().firstOrNull { it.name == id }?.delete()
        refresh()
    }

    @Synchronized override fun deleteAll() {
        files().forEach { it.delete() }
        seenFile.delete()
        refresh()
    }

    private fun refresh() {
        mutableReports.value = read()
        mutableUnseen.value = unseenReport()
    }

    private fun files(): List<File> = directory.listFiles { file -> file.isFile && file.name.matches(reportName) }.orEmpty()
        .sortedByDescending { it.name.removePrefix("crash-").removeSuffix(".txt").toLong() }

    private fun read(): List<CrashReport> = files().mapNotNull { file ->
        runCatching { CrashReport(file.name, file.name.removePrefix("crash-").removeSuffix(".txt").toLong(), file.readText()) }.getOrNull()
    }

    private fun unseenReport(): CrashReport? {
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(Long.MIN_VALUE)
        return mutableReports.value.firstOrNull()?.takeIf { it.time > seen }
    }

    private companion object { val reportName = Regex("crash-\\d+\\.txt") }
}
