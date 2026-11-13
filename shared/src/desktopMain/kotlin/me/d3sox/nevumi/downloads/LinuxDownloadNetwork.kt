package me.d3sox.nevumi.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

class LinuxDownloadNetwork(address: String? = null) : AutoCloseable {
    private val mutableState = MutableStateFlow(DownloadNetwork(connected = false, metered = true))
    val state = mutableState.asStateFlow()
    private val connection = (address?.let(DBusConnectionBuilder::forAddress)
        ?: DBusConnectionBuilder.forSystemBus()).withShared(false).build()
    private val values = mutableMapOf<String, Variant<*>>()
    private var changes: AutoCloseable? = null

    init {
        try {
            // Signals come from NetworkManager's unique bus name, which changes when it restarts; follow the new one.
            connection.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
                if (signal.name == NETWORK_MANAGER) try { subscribe(signal.newOwner.takeIf { it.isNotEmpty() }) }
                catch (error: Exception) { System.err.println("NetworkManager monitoring failed: ${error.message}") }
            }
            subscribe(requireNotNull(connection.getDBusOwnerName(NETWORK_MANAGER)) { "NetworkManager is not running" })
        } catch (error: Exception) { connection.close(); throw error }
    }

    /** Without an [owner], NetworkManager stopped, so nothing holds downloads back until it returns. */
    private fun subscribe(owner: String?) = synchronized(values) {
        changes?.close()
        changes = null
        values.clear()
        if (owner != null) {
            val properties = connection.getRemoteObject(NETWORK_MANAGER, "/org/freedesktop/NetworkManager", Properties::class.java)
            changes = connection.addSigHandler(Properties.PropertiesChanged::class.java, owner, properties) { signal ->
                if (signal.interfaceName == NETWORK_MANAGER) synchronized(values) {
                    values.putAll(signal.propertiesChanged)
                    signal.propertiesRemoved.forEach(values::remove)
                    publish()
                }
            }
            values.putAll(properties.GetAll(NETWORK_MANAGER))
        }
        publish()
    }

    private fun publish() {
        val connectivity = (values["State"]?.value as? Number)?.toInt() ?: 0
        val metered = (values["Metered"]?.value as? Number)?.toInt() ?: 0
        mutableState.value = DownloadNetwork(connected = connectivity == 0 || connectivity >= 50,
            metered = metered == 1 || metered == 3)
    }

    override fun close() { connection.close() }

    private companion object { const val NETWORK_MANAGER = "org.freedesktop.NetworkManager" }
}
