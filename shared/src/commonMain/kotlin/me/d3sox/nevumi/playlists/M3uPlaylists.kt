package me.d3sox.nevumi.playlists

import me.d3sox.nevumi.library.*
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import okio.Path.Companion.toPath

data class PlaylistImport(val playlist: Playlist, val unresolved: List<String>)

class M3uPlaylists(private val library: MusicLibrary, private val playlists: Playlists? = null) {
    suspend fun import(name: String, text: String, baseLocation: String = ""): PlaylistImport {
        val aliases = library.tracks().flatMap { track -> track.sources.flatMap { source ->
            (if (source.kind == SourceKind.SERVER) listOf(location(source)) else listOfNotNull(source.location, source.filePath))
                .map { normalize(it) to track.id }
        } }
        val locations = aliases
            .groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.distinct().singleOrNull() }
        val filesByName = aliases.filter { it.first.startsWith('/') }.groupBy { it.first.substringAfterLast('/') }
        val ids = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        text.removePrefix("\uFEFF").lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { entry ->
            val target = when {
                entry.startsWith("file://") -> entry.removePrefix("file://").decodeURLPart().toPath().normalized().toString()
                entry.contains("://") -> entry
                else -> (if (entry.startsWith('/') || baseLocation.isBlank()) entry.toPath() else baseLocation.toPath() / entry).normalized().toString()
            }
            val relative = baseLocation.isBlank() && !target.startsWith('/') && !target.contains("://") &&
                target != ".." && !target.startsWith("../")
            val id = locations[target] ?: if (relative) filesByName[target.substringAfterLast('/')].orEmpty()
                .filter { (path, _) -> path.endsWith("/$target") }.map { it.second }.distinct().singleOrNull() else null
            if (id == null) unresolved += entry else ids += id
        }
        return PlaylistImport(requireNotNull(playlists) { "Playlists are unavailable" }.create(name, ids), unresolved)
    }

    private fun normalize(location: String): String = when {
        location.startsWith("file://") -> location.removePrefix("file://").decodeURLPart().toPath().normalized().toString()
        location.contains("://") -> location
        else -> location.toPath().normalized().toString()
    }

    suspend fun export(id: String): String {
        val playlist = requireNotNull(requireNotNull(playlists).get(id)) { "Playlist is missing" }
        return exportTracks(playlist.trackIds)
    }

    suspend fun exportTracks(trackIds: List<String>): String {
        return buildString {
            appendLine("#EXTM3U")
            trackIds.forEach { trackId ->
                val track = requireNotNull(library.findTrack(trackId)) { "A playlist song is missing from the library" }
                // A missing local copy would export a dead path, so an available source of any kind comes first.
                val source = track.sources.filter { it.available }.ifEmpty { track.sources }.let { sources ->
                    sources.firstOrNull { it.kind == SourceKind.LOCAL } ?: sources.firstOrNull { it.kind == SourceKind.SERVER } ?: sources.firstOrNull()
                }
                val entry = location(requireNotNull(source) { "A playlist song has no source" })
                require('\n' !in entry && '\r' !in entry) { "A file name contains a line break and cannot be exported to M3U" }
                val description = listOf(track.metadata.artist, track.metadata.title).filter(String::isNotBlank)
                    .joinToString(" - ").replace('\n', ' ').replace('\r', ' ')
                appendLine("#EXTINF:${track.metadata.durationMs / 1000},$description")
                appendLine(entry)
            }
        }
    }

    private fun location(source: TrackSource): String = if (source.kind == SourceKind.SERVER) {
        require(source.id.startsWith("server:")) { "The server source has no connection identity" }
        val server = source.id.removePrefix("server:").substringBefore(':')
        "navidrome://$server/${source.location.encodeURLParameter()}"
    } else {
        val location = source.filePath ?: source.location
        if (location.contains("://")) location else location.toPath().normalized().toString()
    }
}
