package me.d3sox.nevumi.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.withLock

class SecretServiceVault(private val applicationId: String = "me.d3sox.nevumi",
    private val cacheFor: kotlin.time.Duration = 15.seconds) : CredentialVault {
    private data class Cached(val secret: String?, val readAt: kotlin.time.TimeMark)
    // Each lookup spawns secret-tool; background workers read the same key every few seconds.
    private val recent = mutableMapOf<String, Cached>()
    private val recentLock = kotlinx.coroutines.sync.Mutex()
    // Bumped by every save or delete so a lookup that overlapped a change cannot cache the stale secret.
    private var generation = 0L
    private suspend fun invalidate(key: String) = recentLock.withLock { recent.remove(key); generation++ }
    override suspend fun save(key: String, secret: String) {
        invalidate(key)
        try {
            val (code, _) = execute(listOf("store", "--label=Nevumi", "application", applicationId,
                "profile", key), secret)
            check(code == 0) { "Could not save the password in the desktop credential store" }
        } finally { invalidate(key) }
    }
    override suspend fun read(key: String): String? {
        val started = recentLock.withLock {
            recent[key]?.takeIf { it.readAt.elapsedNow() < cacheFor }?.let { return it.secret }
            generation
        }
        val (code, output) = execute(listOf("lookup", "application", applicationId, "profile", key))
        check(code == 0 || code == 1) { "Could not open the desktop credential store" }
        val secret = output.removeSuffix("\n").takeIf { code == 0 }
        recentLock.withLock { if (generation == started) recent[key] = Cached(secret, TimeSource.Monotonic.markNow()) }
        return secret
    }
    override suspend fun delete(key: String) {
        invalidate(key)
        try {
            val (code, _) = execute(listOf("clear", "application", applicationId, "profile", key))
            check(code == 0 || code == 1) { "Could not remove the saved password" }
        } finally { invalidate(key) }
    }
    private suspend fun execute(arguments: List<String>, input: String? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf("secret-tool") + arguments).start()
        try {
            process.outputStream.bufferedWriter().use { if (input != null) it.write(input) }
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Unlock the desktop credential store and try again" }
            process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
