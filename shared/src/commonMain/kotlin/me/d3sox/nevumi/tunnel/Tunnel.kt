package me.d3sox.nevumi.tunnel

import kotlinx.serialization.Serializable

/** [state] is Tailscale's backend state, such as NeedsLogin or Running. */
data class TailscaleStatus(val state: String, val authUrl: String? = null, val name: String? = null, val controlUrl: String = "") {
    val connected: Boolean get() = state == "Running"
}

/** Connections that go through Tailscale on this device. Not synced, because each device signs in separately. */
@Serializable
data class TunnelRoutes(val musicServer: Boolean = false, val serverFiles: Boolean = false)

interface Tunnel {
    suspend fun status(): TailscaleStatus
    suspend fun signIn(controlUrl: String, authKey: String?): TailscaleStatus
    suspend fun signOut()
    suspend fun routes(): TunnelRoutes
    suspend fun updateRoutes(routes: TunnelRoutes)
}
