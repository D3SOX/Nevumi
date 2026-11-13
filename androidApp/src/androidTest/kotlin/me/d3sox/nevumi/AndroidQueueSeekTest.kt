package me.d3sox.nevumi

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.queue.QueueAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class AndroidQueueSeekTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun seekingImmediatelyAfterALoadAndQueueEditKeepsTheCurrentDuplicateOccurrence() = checkSeek(waitForLoad = false)
    @Test fun seekingImmediatelyAfterEditingAnAlreadyLoadedQueueKeepsTheCurrentDuplicateOccurrence() = checkSeek(waitForLoad = true)

    private fun checkSeek(waitForLoad: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val controller = (instrumentation.targetContext.applicationContext as MusicApplication).controller
        val audio = File(instrumentation.targetContext.cacheDir, "queue-seek.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use(input::copyTo) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForLibrary(controller)
                val tracks = runBlocking { (1..3).map { controller.library.importTrack(TrackMetadata("Seek song $it", durationMs = 3_000),
                    TrackSource("seek:$it", SourceKind.LOCAL, audio.path)) } }
                runBlocking {
                    controller.reload().join(); controller.playTrack(tracks[1].id, listOf(tracks[0].id, tracks[1].id, tracks[1].id), 2).join(); controller.pause().join()
                }
                if (waitForLoad) compose.waitUntil(5_000) { controller.state.value.playback.playbackId != null }
                val current = controller.state.value.queue.currentEntryId
                runBlocking { controller.enqueue(listOf(tracks[2].id, tracks[2].id), QueueAction.APPEND).join(); controller.seek(1_000).join() }
                try { compose.waitUntil(5_000) { controller.state.value.playback.positionMs in 950..1_050 } }
                catch (failure: Throwable) { throw AssertionError("After editing: queue=${controller.state.value.queue}; playback=${controller.state.value.playback}", failure) }
                assertEquals(current, controller.state.value.playback.entryId)
                assertEquals(current, controller.state.value.queue.currentEntryId)
            }
        } finally { runBlocking { controller.pause().join() }; audio.delete() }
    }
}
