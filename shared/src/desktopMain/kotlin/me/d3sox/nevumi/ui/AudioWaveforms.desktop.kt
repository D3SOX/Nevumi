package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.d3sox.nevumi.playback.AudioWaveforms
import me.d3sox.nevumi.playback.FfmpegAudioWaveforms

@Composable
internal actual fun rememberAudioWaveforms(): AudioWaveforms = remember { FfmpegAudioWaveforms() }
