package me.d3sox.nevumi.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalNavigationActive = compositionLocalOf { true }
internal val LocalPageBackHandled = compositionLocalOf { false }

/**
 * The same transition is driven by navigation clicks and Android's back-gesture progress. Pages are [opaque] unless they
 * leave what lies below the navigation in view.
 */
@Composable
internal fun <T> AnimatedNavigation(target: T, parent: T, canGoBack: Boolean,
    onBack: () -> Unit, modifier: Modifier = Modifier, depth: (T) -> Int, opaque: (T) -> Boolean = { true },
    content: @Composable (T) -> Unit) {
    val navigationActive = LocalNavigationActive.current
    val state = remember { SeekableTransitionState(target) }
    val transition = rememberTransition(state, "Page navigation")
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    var gesture by remember { mutableStateOf(false) }
    var fromRightEdge by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(target, gesture) {
        if (!gesture) {
            // Forward navigation opens a fresh page; only going back restores saved state.
            if (depth(target) > depth(state.currentState)) holder.removeState(target.toString())
            if (state.currentState == target && state.targetState != target) {
                // Reverse the existing back preview; retargeting would start a new entrance.
                coroutineScope {
                    animate(state.fraction, 0f, animationSpec = tween((state.fraction * 250).roundToInt())) { fraction, _ ->
                        launch { state.seekTo(fraction) }
                    }
                }
                state.snapTo(target)
            } else state.animateTo(target)
        }
    }
    PlatformPredictiveBackHandler(enabled = navigationActive && canGoBack,
        onStarted = { fromRightEdge = it; gesture = true },
        onProgress = { progress ->
            seeking?.cancel()
            seeking = scope.launch {
                if (state.currentState != target) state.snapTo(target)
                state.seekTo(progress, parent)
            }
        },
        onCancelled = { seeking?.cancel(); gesture = false },
        onBack = { seeking?.cancel(); gesture = false; onBack() })
    transition.AnimatedContent(modifier.fillMaxSize().onKeyEvent {
        if (navigationActive && canGoBack && it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onBack(); true } else false
    }, transitionSpec = {
        val forward = depth(targetState) > depth(initialState)
        val (enter, exit) = if (gesture && !forward) {
            // While the back gesture is held, the page shrinks as it follows the finger away from the edge the gesture
            // started from, and its parent grows in behind it from that edge.
            val direction = if (fromRightEdge) -1 else 1
            slideInHorizontally(tween(350, easing = FastOutSlowInEasing)) { -direction * it / 4 } +
                fadeIn(tween(75, 66, LinearEasing), initialAlpha = .75f) + scaleIn(tween(350, 66, LinearEasing), initialScale = .9f) to
                slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { direction * it } + scaleOut(tween(250, easing = LinearEasing), targetScale = .9f)
        } else slideInHorizontally(tween(250)) { if (forward) it else -it / 4 } + fadeIn(tween(180)) to
            slideOutHorizontally(tween(250)) { if (forward) -it / 4 else it } + fadeOut(tween(180))
        // AnimatedContent keeps a page's z-index from when it was first shown, which for a restored page is not a forward
        // navigation. Deeper pages always cover their parents, so the back gesture cannot draw a parent over its page.
        (enter togetherWith exit).apply { targetContentZIndex = depth(targetState).toFloat() }
    }) { page ->
        val active = navigationActive && page == target
        holder.SaveableStateProvider(page.toString()) {
            CompositionLocalProvider(LocalNavigationActive provides active, LocalPageBackHandled provides true) {
                // Opaque, so the gesture's unfaded pages do not show through each other.
                Box(Modifier.fillMaxSize().then(if (opaque(page)) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier).then(if (active) Modifier else Modifier.clearAndSetSemantics {})) { content(page) }
            }
        }
    }
}
