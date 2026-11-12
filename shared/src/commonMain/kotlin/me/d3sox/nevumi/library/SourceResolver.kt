package me.d3sox.nevumi.library

import kotlinx.serialization.Serializable

/** Where a song's audio comes from; a [YOUTUBE] source's location is the video's ID. */
@Serializable
enum class SourceKind {
    LOCAL, DOWNLOAD, SERVER, YOUTUBE;

    companion object {
        /** The kinds the source priority setting orders. A YouTube stream always comes last, after every copy the library holds. */
        val prioritized = listOf(LOCAL, DOWNLOAD, SERVER)
    }
}

@Serializable
data class TrackSource(
    val id: String,
    val kind: SourceKind,
    val location: String,
    val available: Boolean = true,
    val filePath: String? = null,
    val fileLibraryId: Long? = null,
    val modifiedAt: Long = 0,
    val indexedSize: Long = 0,
    val hasArtwork: Boolean = false,
)

/** Songs streamed or queued from YouTube are kept for the queue and history, but only songs with another source belong to the library. */
val Track.inLibrary: Boolean get() = sources.any { it.kind != SourceKind.YOUTUBE }

object SourceResolver {
    fun resolve(
        sources: List<TrackSource>,
        priority: List<SourceKind> = SourceKind.prioritized,
        online: Boolean = true,
    ): TrackSource? = (priority + SourceKind.YOUTUBE).firstNotNullOfOrNull { kind ->
        sources.firstOrNull { it.kind == kind && it.available && (online || kind != SourceKind.SERVER && kind != SourceKind.YOUTUBE) }
    }
}
