package me.d3sox.nevumi.playback

import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplayGainTest {
    @Test
    fun readsReplayGainTagsWhateverTheirCaseUnitOrDecimalSeparator() {
        val tags = replayGainTags(listOf("REPLAYGAIN_TRACK_GAIN" to "-6.54 dB", "replaygain_track_peak" to "0.988",
            "ReplayGain_Album_Gain" to "+1,5 dB", "REPLAYGAIN_ALBUM_PEAK" to "garbage", "TITLE" to "12"))
        assertEquals(ReplayGainTags(trackGainDb = -6.54, trackPeak = 0.988, albumGainDb = 1.5), tags)
        assertNull(replayGainTags(listOf("TITLE" to "Song", "REPLAYGAIN_TRACK_GAIN" to "loud")))
    }

    @Test
    fun convertsOpusR128GainsOnlyWhereReplayGainTagsAreMissing() {
        val tags = replayGainTags(listOf("R128_TRACK_GAIN" to "-512", "R128_ALBUM_GAIN" to "256", "REPLAYGAIN_ALBUM_GAIN" to "-3 dB"))
        assertEquals(ReplayGainTags(trackGainDb = 3.0, albumGainDb = -3.0), tags)
    }

    @Test
    fun albumModeFallsBackToTheSongGainAsMpvDoesAndUntaggedFilesStayUnchanged() {
        val trackOnly = ReplayGainTags(trackGainDb = -7.0)
        val albumOnly = ReplayGainTags(albumGainDb = -4.0)
        assertEquals(-7.0, ReplayGain(ReplayGainMode.ALBUM).gainDb(trackOnly))
        assertEquals(0.0, ReplayGain(ReplayGainMode.TRACK).gainDb(albumOnly))
        assertEquals(0.0, ReplayGain(ReplayGainMode.TRACK, preampDb = 6.0).gainDb(null))
        assertEquals(0.0, ReplayGain(ReplayGainMode.OFF).gainDb(trackOnly))
    }

    @Test
    fun thePeakLimitsBoostsSoTheLoudestSampleDoesNotClip() {
        val tags = ReplayGainTags(trackGainDb = 2.0, trackPeak = 0.5, albumGainDb = -8.0, albumPeak = 0.9)
        assertEquals(-20 * log10(0.5), ReplayGain(ReplayGainMode.TRACK, preampDb = 6.0).gainDb(tags), 1e-9)
        assertEquals(2.0, ReplayGain(ReplayGainMode.TRACK).gainDb(tags))
        assertEquals(-5.0, ReplayGain(ReplayGainMode.ALBUM, preampDb = 3.0).gainDb(tags))
        // Without an album peak, the song's own peak still keeps an album boost from clipping.
        assertEquals(-20 * log10(0.9), ReplayGain(ReplayGainMode.ALBUM).gainDb(ReplayGainTags(albumGainDb = 6.0, trackPeak = 0.9)), 1e-9)
    }
}
