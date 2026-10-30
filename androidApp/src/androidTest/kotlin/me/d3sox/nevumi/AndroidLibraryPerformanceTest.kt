package me.d3sox.nevumi

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidLibraryPerformanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test
    fun scrollTheLibraryWithArtworkWhileMusicIsPlaying() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "scroll-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(42)
        bitmap.setPixels(IntArray(512 * 512) { 0xff000000.toInt() or random.nextInt(0xffffff) }, 0, 512, 0, 0, 512, 512)
        val cover = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val artwork = ArtworkLibrary(File(app.filesDir, "artwork").path)
        val tracks = runBlocking {
            (0..119).map { index ->
                app.controller.library.importTrack(TrackMetadata("Scroll song %03d".format(index)),
                    TrackSource("scroll-$index", SourceKind.LOCAL, audio.path)).also { artwork.save(it.id, cover) }
            }
        }
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { app.controller.refresh(); app.controller.playTrack(tracks.first().id, tracks.map { it.id }).join() }
                compose.waitUntil(5_000) { app.controller.state.value.playback.playing }
                compose.onNodeWithTag("mini-player").assertIsDisplayed()
                val library = compose.onNodeWithTag("library-pager").fetchSemanticsNode().boundsInRoot
                val x = library.center.x.toInt()
                val start = (library.top + library.height * .8f).toInt()
                val end = (library.top + library.height * .2f).toInt()
                shell("dumpsys gfxinfo ${app.packageName} reset")
                repeat(5) { shell("input swipe $x $start $x $end 400") }
                repeat(5) { shell("input swipe $x $end $x $start 400") }
                compose.waitForIdle()
                val output = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
                File(output, "scroll-gfxinfo.txt").apply { parentFile!!.mkdirs() }.writeText(shell("dumpsys gfxinfo ${app.packageName}"))
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                compose.onNodeWithContentDescription("Track options: Scroll song 000").assertIsDisplayed()
            }
        } finally { runBlocking { app.controller.pause().join() }; audio.delete() }
    }
}
