package me.d3sox.nevumi.tunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.storage.MusicStore

class TailscaleTunnel(private val store: MusicStore, val tailscale: Tailscale) : Tunnel {
    override suspend fun status() = withContext(Dispatchers.IO) { tailscale.status() }
    override suspend fun signIn(controlUrl: String, authKey: String?) = withContext(Dispatchers.IO) { tailscale.signIn(controlUrl, authKey) }
    override suspend fun signOut() = withContext(Dispatchers.IO) { tailscale.signOut() }
    override suspend fun routes(): TunnelRoutes = store.read("settings:tunnel") ?: TunnelRoutes()
    override suspend fun updateRoutes(routes: TunnelRoutes) = store.put("settings:tunnel", "tunnel-settings", routes)

    /** The address to request [baseUrl] at: a Tailscale relay when the music server route is on. */
    suspend fun musicServerUrl(baseUrl: String): String =
        if (routes().musicServer) withContext(Dispatchers.IO) { tailscale.relay(baseUrl) } else baseUrl

    /** The address to request sc-gate-dl at: a Tailscale relay when its route is on. */
    suspend fun scGateUrl(baseUrl: String): String =
        if (routes().scGate) withContext(Dispatchers.IO) { tailscale.relay(baseUrl) } else baseUrl

    /** The node SFTP connects through, or null for direct connections. */
    suspend fun serverFiles(): Tailscale? = tailscale.takeIf { routes().serverFiles }
}
