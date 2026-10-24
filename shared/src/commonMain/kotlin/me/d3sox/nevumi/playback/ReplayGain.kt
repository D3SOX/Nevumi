package me.d3sox.nevumi.playback

import kotlinx.serialization.Serializable
import kotlin.math.log10

@Serializable
enum class ReplayGainMode { OFF, TRACK, ALBUM }

/** Volume normalization from each file's ReplayGain tags. Files without tags play unchanged. */
@Serializable
data class ReplayGain(val mode: ReplayGainMode = ReplayGainMode.OFF, val preampDb: Double = 0.0) {
    fun validate() {
        require(preampDb in -15.0..15.0) { "ReplayGain preamp must be between -15 and 15 dB" }
    }

    /**
     * The gain for a file with [tags], lowered where a stored peak would clip. Album mode falls back to the song gain, as mpv
     * does on desktop. A missing peak falls back to the other one; a song's peak never exceeds its album's.
     */
    internal fun gainDb(tags: ReplayGainTags?): Double {
        if (mode == ReplayGainMode.OFF || tags == null) return 0.0
        val useTrack = mode == ReplayGainMode.TRACK || tags.albumGainDb == null
        val gain = (if (useTrack) tags.trackGainDb else tags.albumGainDb) ?: return 0.0
        val peak = if (useTrack) tags.trackPeak ?: tags.albumPeak else tags.albumPeak ?: tags.trackPeak
        return if (peak != null) minOf(gain + preampDb, -20 * log10(peak)) else gain + preampDb
    }
}

internal data class ReplayGainTags(val trackGainDb: Double? = null, val trackPeak: Double? = null,
    val albumGainDb: Double? = null, val albumPeak: Double? = null)

private const val numberPattern = """[+-]?(?:\d+(?:[.,]\d*)?|[.,]\d+)"""
private val numberValue = Regex(numberPattern)
private val gainValue = Regex("($numberPattern)(?:\\s*dB)?", RegexOption.IGNORE_CASE)

/** Reads ReplayGain 2 tags, then Opus R128 gains for what is still missing. Keys are case-insensitive. */
internal fun replayGainTags(tags: Iterable<Pair<String, String>>): ReplayGainTags? {
    val values = HashMap<String, Double>()
    for ((key, value) in tags) {
        val name = key.trim().lowercase()
        val text = value.trim()
        val parsed = if (name == "replaygain_track_gain" || name == "replaygain_album_gain")
            gainValue.matchEntire(text)?.groupValues?.get(1) else numberValue.matchEntire(text)?.value
        val number = parsed?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
        values.putIfAbsent(name, number)
    }
    // R128 gains are Q7.8 integers relative to -23 LUFS; ReplayGain's reference is 5 dB louder.
    fun gain(name: String) = (values["replaygain_${name}_gain"] ?: values["r128_${name}_gain"]?.let { it / 256 + 5 })
        ?.takeIf { it in -64.0..64.0 }
    fun peak(name: String) = values["replaygain_${name}_peak"]?.takeIf { it > 0 }
    return ReplayGainTags(gain("track"), peak("track"), gain("album"), peak("album")).takeIf { it != ReplayGainTags() }
}
