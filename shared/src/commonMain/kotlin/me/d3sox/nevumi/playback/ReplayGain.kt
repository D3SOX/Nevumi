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
        val gain = (if (mode == ReplayGainMode.TRACK) tags.trackGainDb else tags.albumGainDb ?: tags.trackGainDb) ?: return 0.0
        val peak = if (mode == ReplayGainMode.TRACK) tags.trackPeak ?: tags.albumPeak else tags.albumPeak ?: tags.trackPeak
        return if (peak != null) minOf(gain + preampDb, -20 * log10(peak)) else gain + preampDb
    }
}

internal data class ReplayGainTags(val trackGainDb: Double? = null, val trackPeak: Double? = null,
    val albumGainDb: Double? = null, val albumPeak: Double? = null)

private val leadingNumber = Regex("""^[+-]?(\d+([.,]\d*)?|[.,]\d+)""")

/** Reads ReplayGain 2 tags, then Opus R128 gains for what is still missing. Keys are case-insensitive. */
internal fun replayGainTags(tags: Iterable<Pair<String, String>>): ReplayGainTags? {
    val values = HashMap<String, Double>()
    for ((key, value) in tags) {
        val number = leadingNumber.find(value.trim())?.value?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
        values.putIfAbsent(key.trim().lowercase(), number)
    }
    // R128 gains are Q7.8 integers relative to -23 LUFS; ReplayGain's reference is 5 dB louder.
    fun gain(name: String) = (values["replaygain_${name}_gain"] ?: values["r128_${name}_gain"]?.let { it / 256 + 5 })
        ?.takeIf { it in -64.0..64.0 }
    fun peak(name: String) = values["replaygain_${name}_peak"]?.takeIf { it > 0 }
    return ReplayGainTags(gain("track"), peak("track"), gain("album"), peak("album")).takeIf { it != ReplayGainTags() }
}
