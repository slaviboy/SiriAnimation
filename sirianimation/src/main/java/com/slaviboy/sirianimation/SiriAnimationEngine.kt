package com.slaviboy.sirianimation

import android.graphics.Canvas
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.exp

/**
 * UI-toolkit independent animation core shared by [SiriAnimationView] and the [SiriAnimation]
 * composable. It owns time, smoothing of state / config / audio, style crossfades and both renderers.
 *
 * Not thread-safe: use it from the main thread only.
 */
internal class SiriAnimationEngine {

    private var orb: SiriAnimationRenderer = createOrb(false)
    private val band = SiriHorizontalBandRenderer()

    /** Forces the CPU orb renderer even on API 33+ (for testing the compatibility path). */
    var forceCompatibilityRenderer: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            orb.release()
            orb = createOrb(value)
            reconfigure()
        }

    private fun createOrb(forceCpu: Boolean): SiriAnimationRenderer {
        if (!forceCpu && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                return SiriOrbShaderRenderer()
            } catch (e: IllegalArgumentException) {
                // A driver or platform that rejects the AGSL still gets an orb.
                Log.e(TAG, "Orb shader failed to compile; using the compatibility renderer", e)
            }
        }
        return SiriOrbCpuRenderer()
    }

    /** Releases renderer resources. Rendering re-creates them after the next size change or config change. */
    fun release() {
        orb.release()
        band.release()
        width = 0
        height = 0
    }
    private val params = FrameParams()

    private var width = 0
    private var height = 0
    private var density = 1f

    var config: SiriAnimationConfig = SiriAnimationConfig.DEFAULT
        private set
    var style: SiriAnimationStyle = SiriAnimationStyle.ORB
        private set
    var state: SiriAnimationState = SiriAnimationState.IDLE
        private set

    private var previousStyle: SiriAnimationStyle? = null
    private var styleFade = 1f

    // Animation time (seconds) and smoothed values. `time` wraps for the band; `timeD` never wraps.
    private var time = 0f
    private var timeD = 0.0
    private var stepped = false
    private var sSpeed = state.speed
    private var sAmp = state.amplitude
    private var sEnergy = state.energy
    private var sPulse = state.pulse
    private var sSwirl = state.swirl
    private var sIntensity = config.intensity
    private var sGlow = config.glowIntensity
    private var sCore = config.coreIntensity
    private var sGlowOn = 1f
    private var sGlowRadius = config.glowRadius
    private var audioTarget = 0f
    private var sAudio = 0f

    fun setSize(width: Int, height: Int, density: Float) {
        if (width == this.width && height == this.height && density == this.density) return
        this.width = width
        this.height = height
        this.density = density
        reconfigure()
    }

    fun setConfig(newConfig: SiriAnimationConfig) {
        val old = config
        config = newConfig
        // The first configuration (XML attributes, initial composition) applies instantly.
        if (!stepped) snapConfig()
        // glowRadius needs no reconfigure: both orb renderers follow its eased value every frame.
        if (old.palette != newConfig.palette || old.orbSizeFraction != newConfig.orbSizeFraction ||
            old.bandHeightFraction != newConfig.bandHeightFraction || old.bandVerticalBias != newConfig.bandVerticalBias
        ) reconfigure()
    }

    fun setStyle(newStyle: SiriAnimationStyle, animate: Boolean) {
        if (newStyle == style) return
        previousStyle = if (animate) style else null
        styleFade = if (animate) 0f else 1f
        style = newStyle
    }

    fun setState(newState: SiriAnimationState, animate: Boolean) {
        state = newState
        if (!animate) snapToTargets()
    }

    /** Sets the audio target; returns true when it changed (so hosts only request frames when needed). */
    fun setAudioLevel(level: Float): Boolean {
        val target = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        if (target == audioTarget) return false
        audioTarget = target
        return true
    }

    /** True while smoothed values are still moving toward their targets. */
    val isSettling: Boolean
        get() = styleFade < 1f ||
            abs(sSpeed - state.speed) > EPS || abs(sAmp - state.amplitude) > EPS ||
            abs(sEnergy - state.energy) > EPS || abs(sPulse - state.pulse) > EPS ||
            abs(sSwirl - state.swirl) > EPS || abs(sIntensity - config.intensity) > EPS ||
            abs(sGlow - config.glowIntensity) > EPS || abs(sAudio - audioTarget) > EPS ||
            abs(sCore - config.coreIntensity) > EPS || abs(sGlowOn - glowOnTarget) > EPS ||
            abs(sGlowRadius - config.glowRadius) > EPS

    private val glowOnTarget: Float get() = if (config.isGlowEnabled) 1f else 0f

    /**
     * Advances the simulation by [dtSeconds]. Animation time only moves when [advanceTime] is true,
     * so pausing freezes the pattern while state / slider changes still ease in.
     */
    fun step(dtSeconds: Float, advanceTime: Boolean) {
        stepped = true
        val dt = dtSeconds.coerceIn(0f, MAX_DT)
        val k = 1f - exp(-STATE_RATE * dt)
        sSpeed += (state.speed - sSpeed) * k
        sAmp += (state.amplitude - sAmp) * k
        sEnergy += (state.energy - sEnergy) * k
        sPulse += (state.pulse - sPulse) * k
        sSwirl += (state.swirl - sSwirl) * k
        val kc = 1f - exp(-CONFIG_RATE * dt)
        sIntensity += (config.intensity - sIntensity) * kc
        sGlow += (config.glowIntensity - sGlow) * kc
        sCore += (config.coreIntensity - sCore) * kc
        sGlowOn += (glowOnTarget - sGlowOn) * kc
        sGlowRadius += (config.glowRadius - sGlowRadius) * kc
        val audioRate = if (audioTarget > sAudio) AUDIO_ATTACK else AUDIO_RELEASE
        sAudio += (audioTarget - sAudio) * (1f - exp(-audioRate * dt))
        if (styleFade < 1f) {
            styleFade = (styleFade + dt / STYLE_FADE_SECONDS).coerceAtMost(1f)
            if (styleFade >= 1f) previousStyle = null
        }
        if (advanceTime) {
            val preset = config.preset
            val adv = dt * config.speed * sSpeed * (0.6f + 0.4f * preset.motion)
            timeD += adv
            time += adv
            // The band's float clock wraps to keep precision; the orb uses the unwrapped double clock.
            if (time > FrameParams.TIME_WRAP) time -= FrameParams.TIME_WRAP.toFloat()
        }
    }

    fun draw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        fillParams()
        val prev = previousStyle
        if (prev != null && styleFade < 1f) {
            val eased = styleFade * styleFade * (3f - 2f * styleFade)
            drawStyle(canvas, prev, 1f - eased)
            drawStyle(canvas, style, eased)
        } else {
            drawStyle(canvas, style, 1f)
        }
    }

    private fun drawStyle(canvas: Canvas, s: SiriAnimationStyle, alpha: Float) {
        if (alpha <= 0.002f) return
        val renderer = rendererFor(s)
        if (alpha >= 0.998f && !renderer.needsIsolation) {
            // Plain SRC_OVER drawing at full opacity needs no offscreen layer.
            renderer.draw(canvas, params)
            return
        }
        // Isolate the style so its alpha (and any additive blending) never touches content behind the view.
        @Suppress("DEPRECATION")
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), RenderUtils.alpha255(alpha))
        renderer.draw(canvas, params)
        canvas.restoreToCount(save)
    }

    private fun fillParams() {
        params.fill(
            time, timeD, config.preset, sAmp, sEnergy, sPulse, sSwirl, sIntensity, sGlow, sCore, sGlowOn,
            sGlowRadius, sAudio, config.audioReactivity
        )
    }

    private fun rendererFor(s: SiriAnimationStyle): SiriAnimationRenderer = when (s) {
        SiriAnimationStyle.ORB -> orb
        SiriAnimationStyle.HORIZONTAL_BAND -> band
    }

    private fun reconfigure() {
        if (width <= 0 || height <= 0) return
        val palette = config.palette
        orb.configure(width, height, density, palette, config)
        band.configure(width, height, density, palette, config)
    }

    private fun snapToTargets() {
        sSpeed = state.speed; sAmp = state.amplitude; sEnergy = state.energy
        sPulse = state.pulse; sSwirl = state.swirl
    }

    private fun snapConfig() {
        sIntensity = config.intensity; sGlow = config.glowIntensity; sCore = config.coreIntensity
        sGlowOn = glowOnTarget; sGlowRadius = config.glowRadius
    }

    private companion object {
        const val TAG = "SiriAnimation"
        const val EPS = 0.002f
        const val MAX_DT = 0.05f
        const val STATE_RATE = 2.8f
        const val CONFIG_RATE = 8f
        const val AUDIO_ATTACK = 18f
        const val AUDIO_RELEASE = 5f
        const val STYLE_FADE_SECONDS = 0.45f
    }
}
