package me.d3sox.nevumi.playback

import me.d3sox.nevumi.storage.MusicStore
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8

@Serializable
data class AutoEqCatalogEntry(val name: String, val path: String, val attribution: String) {
    val id: String get() = "autoeq:${path.encodeUtf8().sha256().hex()}"
}

@Serializable
data class EqualizerProfile(val id: String, val name: String, val effects: AudioEffects, val attribution: String,
    val source: String? = null)

class AutoEqProfiles(private val store: MusicStore, private val http: HttpClient) {
    private val root = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"

    suspend fun refreshCatalog() {
        val text = fetch("${root}INDEX.md", 4 * 1024 * 1024)
        val line = Regex("^- \\[(.+)]\\(\\./(.+)\\) by (.+)$")
        val entries = text.lineSequence().mapNotNull { line.matchEntire(it)?.destructured?.let { (name, path, author) ->
            validatePath(path)
            require(name.length in 1..200 && author.length in 1..200) { "The AutoEQ catalog contains an invalid entry" }
            AutoEqCatalogEntry(name, path, author)
        } }.distinctBy { it.path }.toList()
        require(entries.size in 1..20_000) { "The AutoEQ catalog is unavailable or invalid" }
        store.put("autoeq:catalog", "autoeq-catalog", entries)
    }

    suspend fun search(query: String, limit: Int = 200): List<AutoEqCatalogEntry> {
        require(limit in 1..1_000)
        val terms = query.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return (store.read<List<AutoEqCatalogEntry>>("autoeq:catalog") ?: emptyList()).asSequence().filter { entry ->
            val text = "${entry.name} ${entry.attribution}".lowercase()
            terms.all { it in text }
        }.take(limit).toList()
    }

    suspend fun saved(): List<EqualizerProfile> = store.list<EqualizerProfile>("equalizer-profile").sortedBy { it.name.lowercase() }

    suspend fun save(name: String, effects: AudioEffects): EqualizerProfile {
        val trimmed = name.trim()
        require(trimmed.length in 1..200) { "Choose a profile name between 1 and 200 characters" }
        val configured = effects.copy(profileName = trimmed).also { it.validate() }
        val id = "equalizer-custom:${trimmed.encodeUtf8().sha256().hex()}"
        return EqualizerProfile(id, trimmed, configured, "Custom profile").also { store.put(id, "equalizer-profile", it) }
    }

    suspend fun download(entry: AutoEqCatalogEntry): EqualizerProfile {
        store.read<EqualizerProfile>(entry.id)?.let { return it }
        validatePath(entry.path)
        val filename = "${entry.path.substringAfterLast('/').decodeURLPart()} ParametricEQ.txt".encodeURLPathPart()
        val source = "$root${entry.path}/$filename"
        val effects = parseEqualizerApo(entry.name, fetch(source, 65_536))
        return EqualizerProfile(entry.id, entry.name, effects, entry.attribution, source).also {
            store.put(it.id, "equalizer-profile", it)
        }
    }

    suspend fun import(name: String, text: String): EqualizerProfile {
        val effects = parseEqualizerApo(name, text)
        val id = "equalizer-import:${text.encodeUtf8().sha256().hex()}"
        return EqualizerProfile(id, name.trim(), effects, "Imported file").also { store.put(id, "equalizer-profile", it) }
    }

    /** Adds a saved profile, such as from a backup, unless one with its ID exists; returns whether it was added. */
    suspend fun restore(profile: EqualizerProfile): Boolean = store.transaction {
        require(profile.id.startsWith("autoeq:") || profile.id.startsWith("equalizer-")) { "Invalid equalizer profile" }
        profile.effects.validate()
        if (store.read<EqualizerProfile>(profile.id) != null) false else { store.put(profile.id, "equalizer-profile", profile); true }
    }

    suspend fun remove(id: String) { store.transaction {
        if (store.read<EqualizerProfile>(id) != null) store.database.records().delete(id)
    } }

    private suspend fun fetch(address: String, limit: Int): String = try {
        http.prepareGet(address).execute { response ->
            check(response.status.value in 200..299)
            val bytes = Buffer()
            val chunk = ByteArray(16 * 1024)
            val channel = response.bodyAsChannel()
            while (true) {
                val count = channel.readAvailable(chunk)
                if (count < 0) break
                check(bytes.size + count <= limit)
                if (count > 0) bytes.write(chunk, 0, count)
            }
            bytes.readUtf8()
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw IllegalStateException("Could not download AutoEQ data. Check your connection and try again.") }

    private fun validatePath(path: String) {
        require(path.length in 1..1_024 && '?' !in path && '#' !in path && '\\' !in path) { "Invalid AutoEQ profile path" }
        require(path.split('/').all { encoded ->
            val part = encoded.decodeURLPart()
            part.isNotEmpty() && part != "." && part != ".." && part.none { it == '/' || it == '\\' || it.isISOControl() }
        }) { "Invalid AutoEQ profile path" }
    }
}

fun parseEqualizerApo(name: String, text: String): AudioEffects {
    require(name.trim().length in 1..200 && text.length <= 65_536) { "Choose a profile name and a smaller equalizer file" }
    val number = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
    val preampPattern = Regex("Preamp:\\s*($number)\\s+dB", RegexOption.IGNORE_CASE)
    val filterPattern = Regex("Filter\\s+(\\d+):\\s+(ON|OFF)\\s+(PK|LSC?|HSC?)\\s+Fc\\s+($number)\\s+Hz\\s+Gain\\s+($number)\\s+dB\\s+Q\\s+($number)", RegexOption.IGNORE_CASE)
    var preamp: Double? = null
    val bands = mutableListOf<EqualizerBand>()
    val numbers = mutableSetOf<Int>()
    text.lineSequence().forEachIndexed { index, raw ->
        val line = raw.substringBefore('#').trim().removePrefix("\uFEFF")
        if (line.isEmpty()) return@forEachIndexed
        val gain = preampPattern.matchEntire(line)
        val filter = filterPattern.matchEntire(line)
        when {
            gain != null -> {
                require(preamp == null) { "An equalizer file can contain only one preamp" }
                preamp = gain.groupValues[1].toDouble()
            }
            filter != null -> {
                val values = filter.groupValues
                require(numbers.add(values[1].toInt()) && numbers.size <= 32) { "Choose at most 32 distinct equalizer filters" }
                val kind = when (values[3].uppercase()) {
                    "PK" -> EqualizerKind.PEAK
                    "LS", "LSC" -> EqualizerKind.LOW_SHELF
                    else -> EqualizerKind.HIGH_SHELF
                }
                val band = EqualizerBand(values[4].toDouble(), values[5].toDouble(), values[6].toDouble(), kind)
                AudioEffects(bands = listOf(band)).validate()
                if (values[2].equals("ON", true)) bands += band
            }
            else -> throw IllegalArgumentException("Unsupported equalizer setting on line ${index + 1}. Import parametric or fixed-band Equalizer APO text.")
        }
    }
    require(bands.isNotEmpty()) { "The file contains no enabled equalizer filters" }
    return AudioEffects(enabled = true, bands = bands, preampDb = preamp ?: 0.0, reduceGainForBoosts = preamp == null,
        profileName = name.trim()).also { it.validate() }
}
