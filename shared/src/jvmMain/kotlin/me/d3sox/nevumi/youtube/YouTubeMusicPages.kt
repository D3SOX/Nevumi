package me.d3sox.nevumi.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Reads YouTube Music's own pages, which NewPipe Extractor does not cover: artists with their shelves, albums and playlists.
 * Requests go to YouTube Music's web API through NewPipe's downloader, like its YouTube Music search, without an account.
 */
internal object YouTubeMusicPages {
    private const val API = "https://music.youtube.com/youtubei/v1/"
    private const val ARTIST_SEARCH = "Eg-KAQwIABAAGAAgASgAMABqChAEEAUQAxAKEAk%3D"
    private const val ALBUM_SEARCH = "Eg-KAQwIABAAGAEgACgAMABqChAEEAUQAxAKEAk%3D"

    /**
     * A continuation token; search continues through the request address, pages through the request body. Some tokens, such as a
     * long discography's, only work for the [visitor] YouTube answered the first request for.
     */
    internal data class Next(val search: Boolean, val token: String, val visitor: String?)

    /** Finds artists or albums, whose results open as pages. */
    fun search(query: String, kind: YouTubeSearchKind): YouTubePage {
        val params = when (kind) {
            YouTubeSearchKind.ARTISTS -> ARTIST_SEARCH
            YouTubeSearchKind.ALBUMS -> ALBUM_SEARCH
            else -> throw IllegalArgumentException("YouTube Music pages cannot search for $kind")
        }
        return searchResults(request("search", mapOf("query" to query, "params" to params)))
    }

    fun searchResults(response: JsonObject): YouTubePage {
        val shelf = response.path("contents", "tabbedSearchResultsRenderer").array("tabs").firstOrEmpty()
            .path("tabRenderer", "content", "sectionListRenderer").array("contents")
            .map { it.obj("musicShelfRenderer") }.firstOrNull { !it.isEmpty() } ?: return YouTubePage(emptyList(), null)
        return YouTubePage(items(shelf.array("contents")), nextOf(shelf, true, visitor(response)))
    }

    fun browse(link: YouTubeLink): YouTubeBrowsePage = page(request("browse", buildMap {
        put("browseId", link.browseId)
        link.params?.let { put("params", it) }
    }))

    fun more(continuation: YouTubeContinuation): YouTubePage {
        val next = continuation.value as? Next ?: throw IllegalArgumentException("This page cannot continue")
        val response = if (next.search) request("search?ctoken=${next.token}&continuation=${next.token}&type=next", emptyMap(), next.visitor)
            else request("browse", mapOf("continuation" to next.token), next.visitor)
        return continued(response, next.search, next.visitor)
    }

    private fun request(endpoint: String, fields: Map<String, String>, visitor: String? = null): JsonObject {
        val version = YoutubeParsingHelper.getYoutubeMusicClientVersion()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    visitor?.let { put("visitorData", it) }
                    put("clientName", "WEB_REMIX"); put("clientVersion", version); put("hl", "en-GB")
                    put("gl", NewPipe.getPreferredContentCountry().countryCode); put("platform", "DESKTOP"); put("utcOffsetMinutes", 0)
                }
                putJsonObject("user") { put("lockedSafetyMode", false) }
            }
            fields.forEach { (name, value) -> put(name, value) }
        }.toString().encodeToByteArray()
        val separator = if ('?' in endpoint) '&' else '?'
        val response = NewPipe.getDownloader().postWithContentTypeJson("$API$endpoint${separator}prettyPrint=false",
            YoutubeParsingHelper.getYoutubeMusicHeaders(), body)
        if (response.responseCode() == 404) throw YouTubeException("YouTube Music has no such page", retryable = false)
        return Json.parseToJsonElement(YoutubeParsingHelper.getValidJsonResponseBody(response)).jsonObject
    }

    /** Reads a browse response: the header and every shelf, in the order YouTube Music shows them. */
    fun page(response: JsonObject): YouTubeBrowsePage {
        val contents = response.obj("contents")
        val single = contents.path("singleColumnBrowseResultsRenderer").array("tabs").firstOrEmpty().path("tabRenderer", "content", "sectionListRenderer")
        val twoColumns = contents.obj("twoColumnBrowseResultsRenderer")
        // Albums and playlists put their header in the first column and their songs in the second.
        val sections = if (twoColumns.isEmpty()) single.array("contents")
            else twoColumns.array("tabs").firstOrEmpty().path("tabRenderer", "content", "sectionListRenderer").array("contents") +
                twoColumns.path("secondaryContents", "sectionListRenderer").array("contents")
        val header = response.obj("header")
        val immersive = header.obj("musicImmersiveHeaderRenderer")
        val visual = header.obj("musicVisualHeaderRenderer")
        val responsive = sections.map { it.obj("musicResponsiveHeaderRenderer") }.firstOrNull { !it.isEmpty() } ?: EMPTY
        val about = sections.map { it.obj("musicDescriptionShelfRenderer") }.firstOrNull { !it.isEmpty() } ?: EMPTY
        val artist = responsive.obj("straplineTextOne").array("runs").firstNotNullOfOrNull { run ->
            collection(run.obj("navigationEndpoint"), run.string("text").orEmpty().trim(), "", null)
        }
        val title = listOf(immersive, visual, responsive, header.obj("musicHeaderRenderer")).map { text(it.obj("title")) }
            .firstOrNull { it.isNotEmpty() } ?: response.path("microformat", "microformatDataRenderer").string("title").orEmpty()
        val subtitle = listOf(text(responsive.obj("subtitle")), text(responsive.obj("secondSubtitle")),
            text(immersive.obj("monthlyListenerCount")).ifEmpty { text(visual.obj("monthlyListenerCount")) })
            .filter { it.isNotEmpty() }.joinToString(" · ").replace(" • ", " · ")
        val description = text(about.obj("description")).ifEmpty { text(immersive.obj("description")) }
            .ifEmpty { text(responsive.path("description", "musicDescriptionShelfRenderer").obj("description")) }
        val artwork = largest(immersive.obj("thumbnail")) ?: largest(visual.obj("foregroundThumbnail"))
            ?: largest(responsive.obj("thumbnail")) ?: largest(visual.obj("thumbnail"))
        var next: YouTubeContinuation? = null
        val shelves = sections.mapNotNull { section ->
            val rows = section.obj("musicShelfRenderer").takeUnless { it.isEmpty() } ?: section.obj("musicPlaylistShelfRenderer")
            val carousel = section.obj("musicCarouselShelfRenderer")
            val grid = section.obj("gridRenderer")
            when {
                !rows.isEmpty() -> {
                    val items = rows.array("contents")
                    next = nextOf(rows, false, visitor(response))
                    YouTubeShelf(text(rows.obj("title")), items(items), YouTubeShelfLayout.ROWS,
                        more = collection(rows.obj("bottomEndpoint"), text(rows.obj("title")), "", null, YouTubeCollectionKind.PLAYLIST))
                }
                !carousel.isEmpty() -> {
                    val heading = carousel.path("header", "musicCarouselShelfBasicHeaderRenderer")
                    val shelfTitle = text(heading.obj("title"))
                    // Without an account, an artist's own lists such as their playlists come back empty, so only other pages are offered.
                    val more = collection(heading.path("moreContentButton", "buttonRenderer").obj("navigationEndpoint"), shelfTitle, "", null)
                        ?.takeUnless { it.kind == YouTubeCollectionKind.ARTIST }
                    YouTubeShelf(shelfTitle, items(carousel.array("contents")), YouTubeShelfLayout.CAROUSEL, more)
                }
                !grid.isEmpty() -> {
                    next = nextOf(grid, false, visitor(response))
                    YouTubeShelf(text(grid.path("header", "gridHeaderRenderer").obj("title")), items(grid.array("items")), YouTubeShelfLayout.GRID)
                }
                else -> null
            }
        }.filter { it.items.isNotEmpty() }
        // Album songs leave out their artist and artwork, which the album names once for all of them.
        val filled = shelves.map { shelf ->
            if (shelf.layout != YouTubeShelfLayout.ROWS || (artist == null && artwork == null)) shelf
            else YouTubeShelf(shelf.title, shelf.items.map { item ->
                if (item is YouTubeTrack) item.copy(artist = item.artist.ifEmpty { artist?.title.orEmpty() }, artworkUrl = item.artworkUrl ?: artwork) else item
            }, shelf.layout, shelf.more)
        }
        return YouTubeBrowsePage(title, subtitle, description, artwork, banner = !immersive.isEmpty(), artist = artist, shelves = filled, next = next)
    }

    /** Reads the entries a continuation request adds to the last shelf of a page or search. */
    fun continued(response: JsonObject, search: Boolean = false, visitor: String? = null): YouTubePage {
        val appended = response.array("onResponseReceivedActions")
            .map { it.path("appendContinuationItemsAction") }.firstOrNull { it.array("continuationItems").isNotEmpty() }
        val continuation = appended ?: response.obj("continuationContents").let { contents ->
            contents.keys.firstNotNullOfOrNull { key -> contents.obj(key).takeIf { key.endsWith("Continuation") } } } ?: EMPTY
        return YouTubePage(items(listItems(continuation)), nextOf(continuation, search, visitor(response) ?: visitor))
    }

    private fun listItems(list: JsonObject) = list.array("contents").ifEmpty { list.array("items") }.ifEmpty { list.array("continuationItems") }

    /** Where a list continues: a token after its last entry, as in playlists, or beside its entries, as in searches and discographies. */
    private fun nextOf(list: JsonObject, search: Boolean, visitor: String?): YouTubeContinuation? {
        val token = listItems(list).lastOrNull()?.path("continuationItemRenderer", "continuationEndpoint", "continuationCommand")?.string("token")
            ?: list.array("continuations").firstOrEmpty().obj("nextContinuationData").string("continuation") ?: return null
        return YouTubeContinuation(Next(search, token, visitor))
    }

    private fun visitor(response: JsonObject): String? = response.obj("responseContext").string("visitorData")

    private fun items(array: List<JsonObject>): List<YouTubeItem> = array.mapNotNull { item ->
        item.obj("musicResponsiveListItemRenderer").takeUnless { it.isEmpty() }?.let(::row)
            ?: item.obj("musicTwoRowItemRenderer").takeUnless { it.isEmpty() }?.let(::card)
    }.distinctBy { it.key }

    /** A list row, such as a song of an album or an artist in search results. */
    private fun row(row: JsonObject): YouTubeItem? {
        // YouTube greys out songs it does not play in this country.
        if (row.string("musicItemRendererDisplayPolicy") == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT") return null
        val columns = row.array("flexColumns").map { text(it.path("musicResponsiveListItemFlexColumnRenderer").obj("text")) }
        val title = columns.firstOrNull().orEmpty()
        val details = columns.getOrNull(1).orEmpty()
        val artwork = largest(row.obj("thumbnail"))
        val videoId = row.path("playlistItemData").string("videoId")
            ?: row.path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint")
                .string("videoId")
        if (videoId != null) {
            val length = row.array("fixedColumns").firstNotNullOfOrNull {
                duration(text(it.path("musicResponsiveListItemFixedColumnRenderer").obj("text")))
            } ?: 0
            return YouTubeTrack(videoId, title, details.substringBefore(" • "), length, artwork, song = true)
        }
        return collection(row.obj("navigationEndpoint"), title, details.replace(" • ", " · "), artwork)
    }

    /** A card in a shelf: an album, single, video, playlist or artist. */
    private fun card(card: JsonObject): YouTubeItem? {
        val title = text(card.obj("title"))
        val runs = card.obj("subtitle").array("runs")
        val subtitle = text(card.obj("subtitle")).replace(" • ", " · ")
        val artwork = largest(card.obj("thumbnailRenderer"))
        val endpoint = card.obj("navigationEndpoint")
        val watch = endpoint.obj("watchEndpoint")
        watch.string("videoId")?.let { videoId ->
            val artist = (runs.firstOrNull { !it.obj("navigationEndpoint").isEmpty() } ?: runs.firstOrNull())?.string("text").orEmpty().trim()
            val type = watch.path("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig").string("musicVideoType")
            return YouTubeTrack(videoId, title, artist, artworkUrl = artwork, song = type == "MUSIC_VIDEO_TYPE_ATV")
        }
        return collection(endpoint, title, subtitle, artwork)
    }

    private fun collection(endpoint: JsonObject, title: String, subtitle: String, artwork: String?,
        fallback: YouTubeCollectionKind? = null): YouTubeCollection? {
        val browse = endpoint.obj("browseEndpoint")
        val id = browse.string("browseId") ?: return null
        val kind = when (browse.path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig").string("pageType")) {
            "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL" -> YouTubeCollectionKind.ARTIST
            "MUSIC_PAGE_TYPE_ALBUM", "MUSIC_PAGE_TYPE_AUDIOBOOK" -> YouTubeCollectionKind.ALBUM
            "MUSIC_PAGE_TYPE_PLAYLIST" -> YouTubeCollectionKind.PLAYLIST
            "MUSIC_PAGE_TYPE_ARTIST_DISCOGRAPHY" -> YouTubeCollectionKind.LIST
            // Podcasts and other pages hold no music to show.
            else -> fallback ?: return null
        }
        return YouTubeCollection(YouTubeLink(id, browse.string("params")), kind, title, subtitle, artwork)
    }

    private fun text(value: JsonObject): String = value.array("runs").joinToString("") { it.string("text").orEmpty() }
        .ifEmpty { value.string("simpleText").orEmpty() }.trim()

    private fun largest(thumbnail: JsonObject): String? {
        val images = thumbnail.path("musicThumbnailRenderer", "thumbnail").array("thumbnails").takeIf { it.isNotEmpty() }
            ?: thumbnail.path("croppedSquareThumbnailRenderer", "thumbnail").array("thumbnails").takeIf { it.isNotEmpty() }
            ?: thumbnail.array("thumbnails")
        return images.maxByOrNull { it.long("width") * it.long("height") }?.string("url")
    }

    /** A length such as `3:47` or `1:02:03`. */
    internal fun duration(text: String): Long? {
        val parts = text.split(':').map { it.toLongOrNull() ?: return null }
        if (parts.size !in 2..3) return null
        return parts.fold(0L) { total, part -> total * 60 + part } * 1_000
    }

    private val EMPTY = JsonObject(emptyMap())
    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: EMPTY
    private fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0
    private fun List<JsonObject>.firstOrEmpty(): JsonObject = firstOrNull() ?: EMPTY
    private fun JsonObject.path(vararg keys: String): JsonObject = keys.fold(this) { value, key -> value.obj(key) }
}
