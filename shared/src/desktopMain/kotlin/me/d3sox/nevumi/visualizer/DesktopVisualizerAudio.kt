package me.d3sox.nevumi.visualizer

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_visualizer_monitor_failed
import me.d3sox.nevumi.resources.sources_visualizer_needs_pulseaudio
import me.d3sox.nevumi.resources.sources_visualizer_start_playback
import org.jetbrains.compose.resources.StringResource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

internal data class OwnedAudioStream(val index: Int, val monitor: String)

internal fun ownedAudioStream(inputs: JsonArray, sinks: JsonArray, clients: JsonArray, pid: Long): OwnedAudioStream? {
    val owners = clients.associate { item ->
        val client = item.jsonObject
        val properties = client["properties"]?.jsonObject.orEmpty()
        client.getValue("index").jsonPrimitive.content to
            (properties["application.process.id"] ?: properties["pipewire.sec.pid"])?.jsonPrimitive?.longOrNull
    }
    val monitors = sinks.associate { entry ->
        val sink = entry.jsonObject
        sink.getValue("index").jsonPrimitive.content to sink["monitor_source"]?.jsonPrimitive?.content
    }
    return inputs.map { it.jsonObject }.filter {
        (it["properties"]?.jsonObject?.get("application.process.id")?.jsonPrimitive?.longOrNull ?:
            owners[it["client"]?.jsonPrimitive?.content]) == pid &&
            it["corked"]?.jsonPrimitive?.booleanOrNull != true
    }.sortedByDescending { it["index"]?.jsonPrimitive?.intOrNull ?: -1 }.firstNotNullOfOrNull { input ->
        val monitor = monitors[input["sink"]?.jsonPrimitive?.content]
        val index = input["index"]?.jsonPrimitive?.intOrNull
        if (monitor == null || index == null) null else OwnedAudioStream(index, monitor)
    }
}

/** PulseAudio's monitor-stream records only our sink input, including PipeWire's PulseAudio service. */
internal class DesktopVisualizerAudio : AutoCloseable {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val capture = AtomicReference<Process?>()
    private val mutableNotice = MutableStateFlow<StringResource?>(null)
    val notice = mutableNotice.asStateFlow()
    private var reader: Job? = null
    init {
        scope.launch {
            var selected: OwnedAudioStream? = null
            while (isActive) {
                try {
                    val stream = ownedAudioStream(command("sink-inputs"), command("sinks"), command("clients"), ProcessHandle.current().pid())
                    mutableNotice.value = if (stream == null) Res.string.sources_visualizer_start_playback else null
                    if (stream != selected || reader?.isActive == false) {
                        capture.getAndSet(null)?.destroyForcibly()
                        reader?.cancelAndJoin()
                        selected = stream
                        reader = stream?.let { source -> scope.launch {
                            var process: Process? = null
                            try {
                                process = ProcessBuilder("parec", "--raw", "--format=float32le", "--rate=44100", "--channels=2",
                                    "--latency-msec=30", "--client-name=Nevumi visualizer", "--device=${source.monitor}", "--monitor-stream=${source.index}")
                                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                                capture.set(process)
                                process.inputStream.use { audio ->
                                    val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
                                    while (isActive) {
                                        val count = audio.read(buffer.array(), buffer.position(), buffer.remaining())
                                        if (count < 0) break
                                        buffer.limit(buffer.position() + count).position(0)
                                        val frames = buffer.remaining() / 8
                                        val samples = FloatArray(frames * 2) { buffer.float }
                                        VisualizerPcm.writeStereo(samples)
                                        buffer.compact()
                                    }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { if (isActive) mutableNotice.value = Res.string.sources_visualizer_monitor_failed }
                            finally { capture.compareAndSet(process, null); process?.destroyForcibly() }
                        } }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutableNotice.value = Res.string.sources_visualizer_needs_pulseaudio
                }
                delay(1_000)
            }
        }
    }
    private fun command(kind: String): JsonArray {
        val process = ProcessBuilder("pactl", "--format=json", "list", kind).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return try {
            val text = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0)
            Json.parseToJsonElement(text).jsonArray
        } finally { process.destroyForcibly() }
    }
    override fun close() { scope.cancel(); capture.getAndSet(null)?.destroyForcibly() }
    suspend fun closeAndJoin() { close(); supervisor.join() }
}
