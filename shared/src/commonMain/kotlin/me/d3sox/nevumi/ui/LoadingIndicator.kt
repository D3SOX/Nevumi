// Ported from Material 3's indeterminate LoadingIndicator and MaterialShapes, which only ship in its alpha releases.
// Copyright 2024 The Android Open Source Project. Licensed under the Apache License, Version 2.0.
package me.d3sox.nevumi.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.d3sox.nevumi.app.playRequested
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.pause
import me.d3sox.nevumi.resources.play
import me.d3sox.nevumi.resources.player_buffering
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Whether playback waits for audio long enough to show it; quick local loads and seeks stay without an indicator. */
@Composable
internal fun rememberBuffering(playback: PlaybackStatus): Boolean {
    val waiting = playback.playRequested && !playback.playing
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        if (waiting) delay(BUFFERING_INDICATOR_DELAY_MS)
        shown = waiting
    }
    return waiting && shown
}

/** The play button's glyph, which turns into a loading indicator of about the same footprint while playback buffers. */
@Composable
internal fun PlayPauseIcon(requested: Boolean, buffering: Boolean, iconSize: Dp, contentDescription: String? = null) {
    val description = contentDescription ?: stringResource(if (requested) Res.string.pause else Res.string.play)
    val bufferingDescription = stringResource(Res.string.player_buffering)
    Crossfade(buffering, label = "Play button buffering") { loading ->
        if (loading) LoadingIndicator(Modifier.size(iconSize * (CONTAINER_SIZE / ACTIVE_SIZE)).semantics {
            this.contentDescription = description
            stateDescription = bufferingDescription
        }, LocalContentColor.current)
        else Icon((if (requested) AppIcon.PAUSE else AppIcon.PLAY).vector, description, Modifier.size(iconSize))
    }
}

/** Material 3 Expressive's indeterminate loading indicator: a rotating shape that morphs through [indicatorPolygons]. */
@Composable
internal fun LoadingIndicator(modifier: Modifier, color: Color) {
    val morphProgress = remember { Animatable(0f) }
    val globalRotation = remember { Animatable(0f) }
    var morphRotation by remember { mutableFloatStateOf(QUARTER_ROTATION) }
    var morphIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val animations = {
            launch {
                // The raised threshold ends the low-damping spring well within one morph interval.
                val spec = spring(dampingRatio = .6f, stiffness = 200f, visibilityThreshold = .1f)
                while (true) {
                    val morph = async {
                        if (morphProgress.animateTo(1f, spec).endReason == AnimationEndReason.Finished) {
                            morphIndex = (morphIndex + 1) % indicatorMorphs.size
                            morphProgress.snapTo(0f)
                            morphRotation = (morphRotation + QUARTER_ROTATION) % FULL_ROTATION
                        }
                    }
                    delay(MORPH_INTERVAL_MS)
                    morph.await()
                }
            }
            launch {
                globalRotation.animateTo(FULL_ROTATION, infiniteRepeatable(tween(GLOBAL_ROTATION_MS, easing = LinearEasing), RepeatMode.Restart))
            }
        }
        when (val policy = coroutineContext[InfiniteAnimationPolicy]) {
            null -> animations()
            else -> policy.onInfiniteOperation { animations() }
        }
    }
    val path = remember { Path() }
    val matrix = remember { Matrix() }
    Box(modifier.progressSemantics().drawBehind {
        val progress = morphProgress.value
        val side = min(size.width, size.height) * indicatorScale
        path.rewind()
        indicatorMorphs[morphIndex].asCubics(progress).forEachIndexed { index, cubic ->
            if (index == 0) path.moveTo(cubic.anchor0X, cubic.anchor0Y)
            path.cubicTo(cubic.control0X, cubic.control0Y, cubic.control1X, cubic.control1Y, cubic.anchor1X, cubic.anchor1Y)
        }
        path.close()
        matrix.reset()
        matrix.scale(side, side)
        path.transform(matrix)
        path.translate(size.center - path.getBounds().center)
        rotate(progress * 90 + morphRotation + globalRotation.value) { drawPath(path, color) }
    })
}

private fun RoundedPolygon.rotated(degrees: Float): RoundedPolygon {
    val radians = degrees * PI.toFloat() / 180
    return transformed { x, y -> TransformResult(x * cos(radians) - y * sin(radians), x * sin(radians) + y * cos(radians)) }
}

/** Mirrors or repeats [points] around the shape's center, as MaterialShapes builds its custom polygons. */
private fun customPolygon(points: List<Pair<Float, Float>>, roundings: List<Float>, reps: Int, mirroring: Boolean = false): RoundedPolygon {
    val vertices = mutableListOf<Pair<Float, Float>>()
    val corners = mutableListOf<CornerRounding>()
    fun add(angle: Float, distance: Float, rounding: Float) {
        vertices += .5f + cos(angle) * distance to .5f + sin(angle) * distance
        corners += CornerRounding(rounding)
    }
    val angles = points.map { (x, y) -> atan2(y - .5f, x - .5f) }
    val distances = points.map { (x, y) -> hypot(x - .5f, y - .5f) }
    if (mirroring) {
        val section = 2 * PI.toFloat() / (reps * 2)
        repeat(reps * 2) { rep ->
            points.indices.forEach { index ->
                val i = if (rep % 2 == 0) index else points.lastIndex - index
                if (i > 0 || rep % 2 == 0)
                    add(section * rep + if (rep % 2 == 0) angles[i] else section - angles[i] + 2 * angles[0], distances[i], roundings[i])
            }
        }
    } else repeat(points.size * reps) {
        val i = it % points.size
        add(angles[i] + (it / points.size) * 2 * PI.toFloat() / reps, distances[i], roundings[i])
    }
    return RoundedPolygon(vertices.flatMap { listOf(it.first, it.second) }.toFloatArray(), perVertexRounding = corners, centerX = .5f, centerY = .5f)
}

/** SoftBurst, Cookie9Sided, Pentagon, Pill, Sunny, Cookie4Sided and Oval, in the order the indicator morphs through them. */
private val indicatorPolygons: List<RoundedPolygon> by lazy {
    listOf(
        customPolygon(listOf(.193f to .277f, .176f to .055f), listOf(.053f, .053f), reps = 10),
        RoundedPolygon.star(9, innerRadius = .8f, rounding = CornerRounding(.5f)).rotated(-90f),
        customPolygon(listOf(.5f to -.009f, 1.03f to .365f, .828f to .97f), listOf(.172f, .164f, .169f), reps = 1, mirroring = true),
        customPolygon(listOf(.961f to .039f, 1.001f to .428f, 1f to .609f), listOf(.426f, 0f, 1f), reps = 2, mirroring = true),
        RoundedPolygon.star(8, innerRadius = .8f, rounding = CornerRounding(.15f)),
        customPolygon(listOf(1.237f to 1.236f, .5f to .918f), listOf(.258f, .233f), reps = 4),
        RoundedPolygon.circle().transformed { x, y -> TransformResult(x, y * .64f) }.rotated(-45f),
    ).map { it.normalized() }
}

private val indicatorMorphs: List<Morph> by lazy {
    indicatorPolygons.indices.map { Morph(indicatorPolygons[it], indicatorPolygons[(it + 1) % indicatorPolygons.size]) }
}

/** Fits every shape inside the indicator while it rotates, at the specification's 38 dp active size in a 48 dp container. */
private val indicatorScale: Float by lazy {
    val bounds = FloatArray(4)
    val maxBounds = FloatArray(4)
    indicatorPolygons.minOf { polygon ->
        polygon.calculateBounds(bounds)
        polygon.calculateMaxBounds(maxBounds)
        max((bounds[2] - bounds[0]) / (maxBounds[2] - maxBounds[0]), (bounds[3] - bounds[1]) / (maxBounds[3] - maxBounds[1]))
    }.coerceAtMost(1f) * ACTIVE_SIZE / CONTAINER_SIZE
}

private const val BUFFERING_INDICATOR_DELAY_MS = 300L
private const val ACTIVE_SIZE = 38f
private const val CONTAINER_SIZE = 48f
private const val GLOBAL_ROTATION_MS = 4666
private const val MORPH_INTERVAL_MS = 650L
private const val FULL_ROTATION = 360f
private const val QUARTER_ROTATION = FULL_ROTATION / 4
