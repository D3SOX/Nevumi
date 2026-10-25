package me.d3sox.nevumi.releases

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import me.d3sox.nevumi.app.ReleaseNotifier
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.freedesktop.Notifications")
interface DesktopNotifications : DBusInterface {
    @Suppress("FunctionName")
    fun Notify(appName: String, replacesId: UInt32, appIcon: String, summary: String, body: String, actions: List<String>,
        hints: Map<String, Variant<*>>, expireTimeout: Int): UInt32
}

/** Shows release notifications through the desktop's notification service; [address] overrides the session bus in tests. */
class LinuxReleaseNotifier(private val address: String? = null) : ReleaseNotifier {
    override suspend fun notify(title: String, text: String) = runInterruptible(Dispatchers.IO) {
        (address?.let(DBusConnectionBuilder::forAddress) ?: DBusConnectionBuilder.forSessionBus()).withShared(false).build().use { connection ->
            connection.getRemoteObject("org.freedesktop.Notifications", "/org/freedesktop/Notifications", DesktopNotifications::class.java)
                .Notify("Nevumi", UInt32(0), "me.d3sox.nevumi", title, text, emptyList(),
                    mapOf("desktop-entry" to Variant("me.d3sox.nevumi")), -1)
        }
        Unit
    }
}
