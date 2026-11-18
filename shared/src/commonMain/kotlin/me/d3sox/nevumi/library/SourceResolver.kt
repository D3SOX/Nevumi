package me.d3sox.nevumi.library

import kotlinx.serialization.Serializable

/**
 * Where a song's audio comes from; a [YOUTUBE] source's location is the video's ID. A [RADIO] source is a music server's
 * internet radio station, whose location is the live stream's address, and a [PODCAST] source a podcast episode on the
 * music server, whose location is the episode's stream ID.
 */
@Serializable
enum class SourceKind {
    LOCAL, DOWNLOAD, SERVER, YOUTUBE, RADIO, PODCAST;

    /** Whether the audio comes over the network rather than from a file on this device. */
    val streams: Boolean get() = this != LOCAL && this != DOWNLOAD

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
    /** The [LOCAL_INDEX_FORMAT] a local file was read with; files read with an older one are read again. */
    val indexFormat: Int = 0,
    /** When the cover image in a local file's folder last changed as the file was read, or 0 without one, so a new cover is read. */
    val folderCoverModifiedAt: Long = 0,
)

/** Raise this when local scans read more from a file, so the next scan reads every file again. 1 added folder covers and artist lists. */
const val LOCAL_INDEX_FORMAT = 1

/**
 * Songs streamed or queued from YouTube are kept for the queue and history, but only songs with another source belong to the
 * library. Radio stations and podcast episodes are kept for the queue only.
 */
val Track.inLibrary: Boolean get() = sources.any { !it.kind.streams || it.kind == SourceKind.SERVER }

/** A music server's internet radio station: a live stream without a length, which is neither sought nor scrobbled. */
val Track.isRadio: Boolean get() = sources.any { it.kind == SourceKind.RADIO }

/** A podcast episode on the music server, which the listening history and scrobbling leave out. */
val Track.isPodcastEpisode: Boolean get() = sources.any { it.kind == SourceKind.PODCAST }

/** The YouTube video the song streams from, if it was ever played from YouTube. */
val Track.youTubeVideoId: String? get() = sources.firstOrNull { it.kind == SourceKind.YOUTUBE }?.location

object SourceResolver {
    fun resolve(
        sources: List<TrackSource>,
        priority: List<SourceKind> = SourceKind.prioritized,
        online: Boolean = true,
    ): TrackSource? = (priority + SourceKind.YOUTUBE + SourceKind.RADIO + SourceKind.PODCAST).firstNotNullOfOrNull { kind ->
        sources.firstOrNull { it.kind == kind && it.available && (online || !kind.streams) }
    }
}
