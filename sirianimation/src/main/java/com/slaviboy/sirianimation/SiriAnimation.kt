package com.slaviboy.sirianimation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

/**
 * Native Jetpack Compose version of [SiriAnimationView], backed by the same rendering engine.
 *
 * ```kotlin
 * SiriAnimation(
 *     style = SiriAnimationStyle.ORB,
 *     state = SiriAnimationState.LISTENING,
 *     modifier = Modifier.size(240.dp)
 * )
 * ```
 *
 * Size it with [modifier]; it draws no background. Frames are only produced while the host
 * lifecycle is at least STARTED and the animation is playing or a transition is easing in.
 *
 * @param style Which visual to draw. Changes crossfade.
 * @param modifier Layout modifier; give it a size (e.g. `fillMaxSize()` / `size()`).
 * @param state Visual state; changes ease in smoothly.
 * @param config Visual configuration.
 * @param isPlaying `false` freezes the pattern on the current frame.
 * @param audioLevel Read every frame; return a normalized level `0..1`. Using a lambda avoids recomposition per sample.
 * @param forceCompatibilityRenderer Use the CPU orb renderer even on API 33+ (testing only).
 */
@Composable
fun SiriAnimation(
    style: SiriAnimationStyle,
    modifier: Modifier = Modifier,
    state: SiriAnimationState = SiriAnimationState.IDLE,
    config: SiriAnimationConfig = SiriAnimationConfig.DEFAULT,
    isPlaying: Boolean = true,
    audioLevel: () -> Float = { 0f },
    forceCompatibilityRenderer: Boolean = false
) {
    val engine = remember { SiriAnimationEngine().also { it.setStyle(style, false); it.setState(state, false) } }
    engine.forceCompatibilityRenderer = forceCompatibilityRenderer
    DisposableEffect(engine) { onDispose { engine.release() } }
    val frame = remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current.density
    val currentAudio by rememberUpdatedState(audioLevel)
    val running = isPlaying && config.isAnimated
    val lifecycleOwner = LocalLifecycleOwner.current

    // Inputs are pushed during composition so the next frame sees them immediately.
    engine.setConfig(config)
    engine.setStyle(style, animate = true)
    engine.setState(state, animate = true)

    // The last frame time survives effect restarts: while a slider drags, config changes every frame,
    // and a fresh timestamp each time would make every step dt = 0 and freeze the animation.
    val lastFrameNanos = remember { LongArray(1) }

    // Input changes restart the loop so a settled (idle) animation wakes up; renderer switches are
    // keys too, so a paused orb still redraws with the new renderer.
    LaunchedEffect(engine, running, style, state, config, forceCompatibilityRenderer, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                withFrameNanos { now ->
                    val last = lastFrameNanos[0]
                    // The engine clamps long gaps (e.g. after the app was in the background).
                    val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                    lastFrameNanos[0] = now
                    engine.setAudioLevel(currentAudio())
                    engine.step(dt, advanceTime = running)
                    frame.longValue = now
                }
            } while (running || engine.isSettling)
            lastFrameNanos[0] = 0L
        }
    }

    Spacer(
        modifier
            .onSizeChanged { engine.setSize(it.width, it.height, density) }
            .drawBehind {
                frame.longValue // read to redraw on every frame
                drawIntoCanvas { engine.draw(it.nativeCanvas) }
            }
    )
}
