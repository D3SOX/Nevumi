package me.d3sox.nevumi.diagnostics

import kotlinx.coroutines.flow.StateFlow

/** A crash Nevumi recorded on this device. [text] holds the app version, platform and stack trace. */
data class CrashReport(val id: String, val time: Long, val text: String) {
    /** The exception line, such as `java.lang.IllegalStateException: …`, for lists. */
    val summary: String get() = text.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith(HEADER_PREFIX) }.orEmpty()

    companion object { const val HEADER_PREFIX = "# " }
}

/** Crashes kept on this device, newest first, for users to copy into a bug report. Nothing is uploaded. */
interface CrashReports {
    val reports: StateFlow<List<CrashReport>>
    /** The newest report the user has not been told about yet, so the app can offer it once after a crash. */
    val unseen: StateFlow<CrashReport?>
    fun markSeen()
    fun delete(id: String)
    fun deleteAll()
}
