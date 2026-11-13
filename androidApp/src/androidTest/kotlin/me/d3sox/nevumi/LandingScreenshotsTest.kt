package me.d3sox.nevumi

import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.settings.*
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Run explicitly on a fresh emulator with -e nevumiScreenshots true. */
class LandingScreenshotsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun captureFictionalLibrary() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nevumiScreenshots") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val controller = app.controller
        val audio = File(app.filesDir, "landing-music.mp3")
        instrumentation.context.assets.open("waveform-four-minutes.mp3").use { input -> audio.outputStream().use(input::copyTo) }
        val artwork = ArtworkLibrary(File(app.filesDir, "artwork").path)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForLibrary(controller, 10_000)
            val store = openMusicStore(app)
            val tracks = try { runBlocking {
                controller.updatePreferences { it.copy(themeMode = ThemeMode.DARK, colors = ColorMode.CUSTOM,
                    customColor = 0xFFB8A0F0, precomputeWaveforms = false, lastLibraryTab = LibraryTab.HOME,
                    homeSections = listOf(HomeSectionId.MIXES, HomeSectionId.RECENT_ALBUMS, HomeSectionId.ADDED) +
                        HomeSectionId.entries.filter { section -> section !in listOf(HomeSectionId.MIXES, HomeSectionId.RECENT_ALBUMS, HomeSectionId.ADDED) }) }.join()
                var elapsed = 0L
                var wall = System.currentTimeMillis()
                val history = ListeningHistory(store, controller.library, wallClock = { wall }, elapsedClock = { elapsed })
                val albums = listOf("Violet hour", "Open water", "Night bus", "Sunroom", "Soft focus", "After the rain")
                val artists = listOf("Mira Vale", "Northbound", "Paper Satellites", "Lena Moss", "Slow Signal", "Juniper")
                val titles = listOf("First light", "Late bloom", "Side streets", "Still awake", "Half a world", "Coming home")
                val songs = albums.flatMapIndexed { albumIndex, album -> titles.mapIndexed { index, title ->
                    val track = controller.library.importTrack(TrackMetadata(title, artists[albumIndex], album,
                        durationMs = 240_000, genres = listOf("Electronic"), albumArtist = artists[albumIndex],
                        trackNumber = index + 1, year = 2026), TrackSource("landing:$albumIndex:$index", SourceKind.LOCAL, audio.path))
                    artwork.save(track.id, cover(albumIndex, album, artists[albumIndex]))
                    if (index < 2) controller.library.setFavorite(track.id, true)
                    wall = System.currentTimeMillis() - (albumIndex * 6 + index) * 3_600_000L
                    val status = PlaybackStatus(trackId = track.id, playbackId = "landing:$albumIndex:$index",
                        durationMs = 3_000, playing = true)
                    history.record(status)
                    elapsed += 2_000
                    history.record(status.copy(positionMs = 2_000, playing = false, ended = true))
                    track
                } }
                Playlists(store).create("Night drive", songs.filterIndexed { index, _ -> index % 3 == 0 }.map { track -> track.id })
                controller.reload().join()
                songs
            } } finally { store.close() }
            runBlocking {
                controller.playTrack(tracks[2].id, tracks.take(6).map { track -> track.id }, 2).join()
                controller.pause().join()
                controller.enqueue(tracks.drop(6).take(3).map { track -> track.id }, QueueAction.APPEND).join()
                controller.seek(68_000).join()
            }
            compose.waitUntil(10_000) { controller.state.value.playback.positionMs >= 67_000 && controller.state.value.artwork.size == 36 }
            compose.waitForIdle()
            val notices = compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss))
            if (notices.fetchSemanticsNodes().isNotEmpty())
                notices[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { it() }
            compose.onNodeWithContentDescription("Home").performClick()
            capture("home")
            compose.onNodeWithTag("mini-player").performClick()
            compose.onNodeWithContentDescription("Playback settings").assertIsDisplayed()
            capture("now-playing")
            compose.onNodeWithContentDescription("Queue").performClick()
            compose.onNodeWithTag("queue-header").assertIsDisplayed()
            capture("queue")
            compose.onNodeWithContentDescription("Now playing").performClick()
            compose.onNodeWithContentDescription("Playback settings").performClick()
            compose.onNodeWithText("Audio effects").performClick()
            compose.onNodeWithText("Headphone profiles").assertExists()
            capture("audio-effects")
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(700)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "screenshots").apply { mkdirs() }
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun cover(index: Int, album: String, artist: String): ByteArray {
        val colors = intArrayOf(0xffa78bfa.toInt(), 0xff67d4cd.toInt(), 0xfff6aa7c.toInt(), 0xfff3d778.toInt(), 0xffa6c3fa.toInt(), 0xffdb92be.toInt())
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 640f, 640f, colors[index], 0xff182133.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 640f, 640f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = 0x90ffffff.toInt()
        repeat(12) { ring -> canvas.drawCircle(390f - index * 18, 285f, 32f + ring * 20, paint) }
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD); paint.textSize = 52f
        canvas.drawText(album.uppercase(), 40f, 535f, paint)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL); paint.textSize = 26f
        canvas.drawText(artist, 42f, 578f, paint)
        return ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle(); stream.toByteArray() }
    }
}
