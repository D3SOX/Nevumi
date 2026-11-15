package me.d3sox.nevumi.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.withLock

class SecretServiceVault(private val applicationId: String = "me.d3sox.nevumi",
    private val cacheFor: kotlin.time.Duration = 15.seconds, private val keepFoundFor: kotlin.time.Duration = 10.minutes,
    private val command: String = "secret-tool") : CredentialVault {
    private data class Cached(val secret: String?, val readAt: kotlin.time.TimeMark)
    // Each lookup spawns secret-tool; background workers read the same key every few seconds. Saves and deletes through this vault
    // update it at once, so a found secret is read again only after [keepFoundFor], for changes made in another keyring app. A missing
    // one is looked up again after [cacheFor], in case the keyring was locked.
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
        } finally { withContext(NonCancellable) { invalidate(key) } }
    }
    override suspend fun read(key: String): String? {
        val started = recentLock.withLock {
            recent[key]?.takeIf { it.readAt.elapsedNow() < if (it.secret != null) keepFoundFor else cacheFor }?.let { return it.secret }
            generation
        }
        val (code, output, errors) = execute(listOf("lookup", "application", applicationId, "profile", key))
        // secret-tool exits with 1 both for a missing secret and for errors such as a locked keyring; only errors print its
        // "secret-tool: " message, while GLib warnings can appear either way. Reading an error as a missing secret would let callers
        // replace a secret they could not read.
        check(code == 0 || code == 1 && errors.lineSequence().none { it.startsWith("secret-tool: ") }) {
            "Could not open the desktop credential store"
        }
        // A keyring that stays locked, such as after a dismissed unlock prompt, also ends the lookup silently. Searching without
        // unlocking still lists a locked item, so a secret that exists but could not be read is not mistaken for a missing one.
        if (code == 1) check(execute(listOf("search", "application", applicationId, "profile", key)).second.lineSequence()
            .none { it.startsWith("[") }) { "Unlock the desktop credential store and try again" }
        // secret-tool adds a newline only when writing to a terminal, so the output is the stored secret.
        val secret = output.takeIf { code == 0 }
        recentLock.withLock { if (generation == started) recent[key] = Cached(secret, TimeSource.Monotonic.markNow()) }
        return secret
    }
    override suspend fun delete(key: String) {
        invalidate(key)
        try {
            val (code, _) = execute(listOf("clear", "application", applicationId, "profile", key))
            check(code == 0 || code == 1) { "Could not remove the saved password" }
        } finally { withContext(NonCancellable) { invalidate(key) } }
    }
    private suspend fun execute(arguments: List<String>, input: String? = null): Triple<Int, String, String> = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(command) + arguments).start()
        try {
            // Both streams are drained while it runs, or a full pipe would block it until the unlock timeout.
            val output = async { process.inputStream.bufferedReader().readText() }
            val errors = async { process.errorStream.bufferedReader().readText() }
            process.outputStream.bufferedWriter().use { if (input != null) it.write(input) }
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Unlock the desktop credential store and try again" }
            Triple(process.exitValue(), output.await(), errors.await())
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
