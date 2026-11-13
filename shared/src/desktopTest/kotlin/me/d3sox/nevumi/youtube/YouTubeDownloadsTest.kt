package me.d3sox.nevumi.youtube

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.soundcloud.DirectoryMusicFiles
import me.d3sox.nevumi.soundcloud.ScGateTagChoice
import me.d3sox.nevumi.soundcloud.ScGateTags
import me.d3sox.nevumi.storage.openMusicStore
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeDownloadsTest {
    private val audio = Files.readAllBytes(Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/youtube-fragmented.m4a"))
    private val track = YouTubeTrack("abcdefghijk", "One More Time", "Daft Punk", 3_000, song = true)

    /** Answers like YouTube: [audio] in byte ranges only, from addresses that [expired] rejects. */
    private class FakeYouTube(val audio: ByteArray, val failure: Exception? = null, val mimeType: String = "audio/mp4") : YouTubeSource {
        val lookups = Collections.synchronizedList(mutableListOf<Boolean>())
        override suspend fun search(query: String, kind: YouTubeSearchKind, continuation: YouTubeContinuation?) = YouTubePage(emptyList(), null)
        override suspend fun browse(link: YouTubeLink) = YouTubeBrowsePage("")
        override suspend fun browseMore(continuation: YouTubeContinuation) = YouTubePage(emptyList(), null)
        override suspend fun audio(videoId: String, fresh: Boolean): YouTubeAudio {
            lookups += fresh
            failure?.let { throw it }
            return YouTubeAudio("https://media.test/${lookups.size}", mimeType, audio.size.toLong(), 48, YouTubeDetails("Discovery", 2001))
        }
        override fun playbackLocation(videoId: String) = "http://127.0.0.1/$videoId"
        override suspend fun artwork(url: String) = ByteArray(0)
    }

    private class Fixture(val directory: Path, val downloads: YouTubeDownloads, val library: MusicLibrary, val requests: MutableList<String>,
        val close: () -> Unit)

    private fun fixture(source: YouTubeSource, expired: Set<String> = emptySet(), includeFolder: String? = null,
        upload: (suspend (String, String) -> Unit)? = null): Fixture {
        val directory = Files.createTempDirectory("youtube-downloads")
        val store = openMusicStore(directory.resolve("library.db").toString())
        includeFolder?.let { folder -> runBlocking { MusicPreferences(store).update { it.copy(includeFolders = listOf(directory.resolve(folder).toString())) } } }
        val library = MusicLibrary(store)
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val http = HttpClient(MockEngine { request ->
            val range = request.headers[HttpHeaders.Range]
            requests += "${request.url} $range"
            val bytes = range?.removePrefix("bytes=")?.split('-')?.map { it.toInt() }
            when {
                request.url.toString() in expired -> respond("", HttpStatusCode.Forbidden)
                bytes == null -> respond("", HttpStatusCode.OK)
                else -> respond(audio.copyOfRange(bytes[0], bytes[1] + 1), HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentRange, "bytes ${bytes[0]}-${bytes[1]}/${audio.size}"))
            }
        })
        val downloads = YouTubeDownloads(store, http, source, directory.resolve("work").toString(),
            DirectoryMusicFiles(directory.resolve("Music/YouTube").toString()), LocalFiles(library), upload = upload, chunkBytes = 4_096)
        return Fixture(directory, downloads, library, requests) {
            runBlocking { downloads.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    private suspend fun YouTubeDownloads.awaitJob(id: String, check: (YouTubeJob) -> Boolean) =
        withTimeout(10_000) { jobs.first { jobs -> jobs?.firstOrNull { it.id == id }?.let(check) == true } }.orEmpty().first { it.id == id }

    @Test fun aReviewedDownloadIsFetchedInRangesTaggedSavedAndIndexed() = runBlocking {
        val fixture = fixture(FakeYouTube(audio))
        try {
            val job = fixture.downloads.download(track, YouTubeDestination.DEVICE, reviewTags = true)
            val waiting = fixture.downloads.awaitJob(job.id) { it.state == YouTubeJobState.NEEDS_TAGS }
            // Every request asks for a range no larger than a chunk, which YouTube answers at full speed.
            assertTrue(fixture.requests.size > 1)
            assertTrue(fixture.requests.all { it.contains("bytes=") })
            assertEquals(ScGateTags("One More Time", "Daft Punk", "Discovery"), waiting.tags)
            assertEquals(2001, waiting.year)

            fixture.downloads.finishTags(job.id, ScGateTagChoice(ScGateTags("One More Time", "Daft Punk", "Discovery", "House")))
            val done = fixture.downloads.awaitJob(job.id) { it.state == YouTubeJobState.COMPLETE }
            val saved = File(assertNotNull(done.location))
            assertEquals("Daft Punk - One More Time.m4a", saved.name)
            assertEquals("House", AudioFileIO.read(saved).tag.getFirst(FieldKey.GENRE))
            val song = fixture.library.tracks().single { it.id == done.trackId }
            assertEquals(SourceKind.LOCAL, song.sources.single().kind)
            assertEquals("One More Time", song.metadata.title)
            // The work files are gone once the song is saved.
            assertEquals(emptyList(), fixture.directory.resolve("work").toFile().list().orEmpty().toList())
        } finally { fixture.close() }
    }

    @Test fun aSongForTheMusicServerIsUploadedEvenWhenTheFolderRulesLeaveItsFolderOut() = runBlocking {
        val uploads = Collections.synchronizedList(mutableListOf<String>())
        val fixture = fixture(FakeYouTube(audio), includeFolder = "Elsewhere", upload = { _, path -> uploads += path })
        try {
            val job = fixture.downloads.download(track, YouTubeDestination.SERVER, reviewTags = false)
            val done = fixture.downloads.awaitJob(job.id) { it.state == YouTubeJobState.COMPLETE }
            assertEquals("Added to your music server", done.message)
            assertEquals(listOf("Daft Punk/Discovery/Daft Punk - One More Time.m4a"), uploads.toList())
        } finally { fixture.close() }
    }

    @Test fun anExpiredStreamAddressIsLookedUpAgainAndTheDownloadContinues() = runBlocking {
        val source = FakeYouTube(audio)
        val fixture = fixture(source, expired = setOf("https://media.test/1"))
        try {
            val job = fixture.downloads.download(track, YouTubeDestination.DEVICE, reviewTags = false)
            fixture.downloads.awaitJob(job.id) { it.state == YouTubeJobState.COMPLETE }
            assertEquals(listOf(false, true), source.lookups.toList())
        } finally { fixture.close() }
    }

    @Test fun anUnavailableVideoFailsWithoutARetryWhileAnOutageCanBeRetried() = runBlocking {
        val removed = fixture(FakeYouTube(audio, YouTubeException("This video is private", retryable = false)))
        try {
            val job = removed.downloads.download(track, YouTubeDestination.DEVICE, reviewTags = false)
            val failed = removed.downloads.awaitJob(job.id) { it.state == YouTubeJobState.FAILED }
            assertEquals("This video is private", failed.message)
            assertTrue(failed.permanent)
        } finally { removed.close() }
        val offline = fixture(FakeYouTube(audio, YouTubeException("Could not reach YouTube. Check your connection.")))
        try {
            val job = offline.downloads.download(track, YouTubeDestination.DEVICE, reviewTags = false)
            assertFalse(offline.downloads.awaitJob(job.id) { it.state == YouTubeJobState.FAILED }.permanent)
        } finally { offline.close() }
    }

    @Test fun aVideoWithoutMp4AudioFailsBeforeDownloadingAnything() = runBlocking {
        val fixture = fixture(FakeYouTube(audio, mimeType = "audio/webm"))
        try {
            val job = fixture.downloads.download(track, YouTubeDestination.DEVICE, reviewTags = false)
            val failed = fixture.downloads.awaitJob(job.id) { it.state == YouTubeJobState.FAILED }
            assertEquals("YouTube offers no M4A audio for this video to save", failed.message)
            assertTrue(failed.permanent)
            assertEquals(emptyList(), fixture.requests.toList())
        } finally { fixture.close() }
    }

    @Test fun cancelingAWaitingDownloadDeletesItsAudioAndRetryingStartsOver() = runBlocking {
        val fixture = fixture(FakeYouTube(audio))
        try {
            val job = fixture.downloads.download(track, YouTubeDestination.DEVICE, reviewTags = true)
            fixture.downloads.awaitJob(job.id) { it.state == YouTubeJobState.NEEDS_TAGS }
            fixture.downloads.cancel(job.id)
            val canceled = fixture.downloads.awaitJob(job.id) { it.state == YouTubeJobState.CANCELLED }
            assertFalse(canceled.downloaded)
            // A writer that was still finishing deletes the files itself once it stops.
            withTimeout(5_000) { while (fixture.directory.resolve("work").toFile().list().orEmpty().isNotEmpty()) kotlinx.coroutines.delay(20) }
            fixture.downloads.retry(job.id)
            fixture.downloads.awaitJob(job.id) { it.state == YouTubeJobState.NEEDS_TAGS }
            fixture.downloads.dismiss(job.id)
            assertNull(withTimeout(5_000) { fixture.downloads.jobs.first { jobs -> jobs?.none { it.id == job.id } == true } }.orEmpty().firstOrNull())
        } finally { fixture.close() }
    }
}
