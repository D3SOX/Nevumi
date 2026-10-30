package me.d3sox.nevumi.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@DBusInterfaceName("org.freedesktop.portal.FileChooser")
interface PortalChooser : DBusInterface {
    fun OpenFile(parent: String, title: String, options: Map<String, Variant<*>>): DBusPath
    fun SaveFile(parent: String, title: String, options: Map<String, Variant<*>>): DBusPath
}

@DBusInterfaceName("org.freedesktop.portal.Request")
interface PortalRequest : DBusInterface {
    fun Close()
    class Response(path: String, val response: UInt32, val results: Map<String, Variant<*>>) : DBusSignal(path, response, results)
}

class PortalFilter(@Position(0) val name: String, @Position(1) val patterns: List<PortalPattern>) : Struct()
class PortalPattern(@Position(0) val kind: UInt32, @Position(1) val pattern: String) : Struct()

/** File access goes through the desktop's portal, including its native overwrite confirmation. */
internal class PortalFileChooser(private val address: String? = null) {
    suspend fun choose(title: String, directory: Boolean = false, filter: PortalFilter? = null, saveName: String? = null): File? =
        withContext(Dispatchers.IO) {
            val builder = address?.let(DBusConnectionBuilder::forAddress) ?: DBusConnectionBuilder.forSessionBus()
            builder.withShared(false).build().use { connection ->
                val token = "nevumi_${UUID.randomUUID().toString().replace('-', '_')}"
                var path = "/org/freedesktop/portal/desktop/request/${connection.uniqueName.removePrefix(":").replace('.', '_')}/$token"
                val response = CompletableDeferred<PortalRequest.Response>()
                // Subscribe before OpenFile/SaveFile: a portal can respond before the method returns.
                val subscription = connection.addSigHandler(PortalRequest.Response::class.java) { signal ->
                    if (signal.path == path) response.complete(signal)
                }
                var opened = false
                try {
                    val options = mutableMapOf<String, Variant<*>>("handle_token" to Variant(token), "modal" to Variant(true))
                    if (directory) options["directory"] = Variant(true)
                    if (filter != null) options["filters"] = Variant(listOf(filter), "a(sa(us))")
                    if (saveName != null) options["current_name"] = Variant(File(saveName).name)
                    val portal = connection.getRemoteObject("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop", PortalChooser::class.java)
                    path = (if (saveName == null) portal.OpenFile("", title, options) else portal.SaveFile("", title, options)).path
                    opened = true
                    val result = response.await()
                    when (result.response.toInt()) {
                        0 -> {
                            val uri = (result.results["uris"]?.value as? List<*>)?.firstOrNull() as? String
                                ?: throw IOException("The desktop picker returned no file")
                            File(URI(uri))
                        }
                        1 -> null
                        else -> throw IOException("The desktop could not complete the file selection")
                    }
                } finally {
                    if (opened && !response.isCompleted) withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { connection.getRemoteObject("org.freedesktop.portal.Desktop", path, PortalRequest::class.java).Close() }
                    }
                    subscription.close()
                }
            }
        }

    /** Writes [text] to a file the user chooses, replacing it whole; false when the user cancels. */
    suspend fun save(title: String, filter: PortalFilter, name: String, text: String): Boolean {
        val file = choose(title, filter = filter, saveName = name) ?: return false
        withContext(Dispatchers.IO) {
            val temporary = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".nevumi-", ".tmp")
            try {
                Files.writeString(temporary, text, Charsets.UTF_8)
                // Some network and FUSE file systems cannot replace a file atomically; a plain replace still keeps the export whole.
                try { Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
        }
        return true
    }
}

internal fun portalFilter(name: String, vararg patterns: String) = PortalFilter(name,
    patterns.map { PortalPattern(UInt32(if ('/' in it) 1 else 0), it) })
