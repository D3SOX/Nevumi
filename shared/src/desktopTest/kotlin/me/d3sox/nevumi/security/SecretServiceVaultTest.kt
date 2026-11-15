package me.d3sox.nevumi.security

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.sync.SyncTls
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SecretServiceVaultTest {
    private val directory = Files.createTempDirectory("nevumi-secret-tool")
    private val calls = directory.resolve("calls")

    @AfterTest fun cleanUp() { directory.toFile().deleteRecursively() }

    /** A stand-in for secret-tool that logs its action and runs [lookup] for lookups and [search] for searches. */
    private fun vault(lookup: String, search: String = "exit 1"): SecretServiceVault {
        val script = directory.resolve("secret-tool")
        script.writeText("#!/bin/sh\necho \"$1\" >> '$calls'\nif [ \"$1\" = lookup ]; then\n$lookup\nfi\n" +
            "if [ \"$1\" = search ]; then\n$search\nfi\ncat > /dev/null\nexit 0\n")
        script.toFile().setExecutable(true)
        return SecretServiceVault("me.d3sox.nevumi.tests", command = script.toString())
    }

    private fun Path.lines() = if (Files.exists(this)) readText().lines().filter { it.isNotEmpty() } else emptyList()

    @Test
    fun aMissingSecretReadsAsNull() = runBlocking {
        assertNull(vault("exit 1").read("server"))
    }

    @Test
    fun aWarningAloneStillReadsAsAMissingSecret() = runBlocking {
        assertNull(vault("echo '(secret-tool:42): GLib-GIO-WARNING **: unrelated' >&2; exit 1").read("server"))
    }

    @Test
    fun aKeyringErrorIsNotReadAsAMissingSecret() = runBlocking {
        val vault = vault("echo 'secret-tool: Cannot create an item in a locked collection' >&2; exit 1")
        assertFailsWith<IllegalStateException> { vault.read("server") }
        // The sync identity must stay as it is when the keyring cannot be read; a new one would end every pairing.
        assertFailsWith<IllegalStateException> { SyncTls.load(vault, "11111111-1111-4111-8111-111111111111") }
        assertFalse("store" in calls.lines())
    }

    @Test
    fun aSecretInALockedKeyringIsNotReadAsMissing() = runBlocking {
        // A dismissed unlock prompt ends the lookup without a message, while a search without unlocking still lists the item.
        val vault = vault("exit 1", search = "printf '[/org/freedesktop/secrets/collection/login/1]\\nlabel = Nevumi\\n'; exit 0")
        assertFailsWith<IllegalStateException> { vault.read("server") }
        assertFailsWith<IllegalStateException> { SyncTls.load(vault, "11111111-1111-4111-8111-111111111111") }
        assertFalse("store" in calls.lines())
    }

    @Test
    fun aFailedSearchAfterASilentLookupIsNotReadAsMissing() = runBlocking {
        val vault = vault("exit 1", search = "echo 'secret-tool: Cannot get secret service' >&2; exit 1")
        assertFailsWith<IllegalStateException> { vault.read("server") }
        assertFailsWith<IllegalStateException> { SyncTls.load(vault, "11111111-1111-4111-8111-111111111111") }
        assertFalse("store" in calls.lines())
    }

    @Test
    fun theSecretIsReturnedExactly() = runBlocking {
        // secret-tool prints the stored value without a newline when its output is not a terminal.
        assertEquals("token\n", vault("printf 'token\\n'; exit 0").read("server"))
    }

    @Test
    fun aLongErrorOutputDoesNotBlockTheLookup() = runBlocking {
        val vault = vault("head -c 1000000 /dev/zero | tr '\\0' x >&2; printf token; exit 0")
        assertEquals("token", withTimeout(10_000) { vault.read("server") })
    }
}
