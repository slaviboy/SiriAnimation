package com.slaviboy.sirianimation

import kotlin.math.PI
import kotlin.math.sin

/**
 * Per-frame values shared by all renderers. A single instance is reused every frame.
 * Pure Kotlin (no android.* imports) so JVM tests can drive the orb model directly.
 */
internal class FrameParams {
    /** Animation time in seconds, already scaled by speed and state. Wraps; used by the band. */
    var time = 0f
    /** The same clock, unwrapped and in double precision. The orb derives all of its phases from it. */
    var timeD = 0.0
    /** Motion amplitude, roughly `0..2`. */
    var energy = 0f
    /** Brightness multiplier, roughly `0..1.5`. */
    var brightness = 1f
    /** Glow multiplier, roughly `0..3`. */
    var glow = 1f
    /** Current pulse value `0..1`. */
    var pulse = 0f
    /** Swirl amount `0..1` (thinking state). */
    var swirl = 0f
    /** Core emitter strength `0..2`. */
    var core = 1f
    /** Glow enable factor `0..1` (eased). */
    var glowEnabled = 1f
    /** Smoothed audio level `0..1.5`. */
    var audio = 0f
    /** Eased [SiriAnimationConfig.glowRadius], orb halo spread `0.5..2`. The band ignores it. */
    var glowRadius = 1f

    /** Maps the engine's smoothed values to frame values. */
    fun fill(
        time: Float,
        timeD: Double,
        preset: SiriAnimationPreset,
        amplitude: Float,
        stateEnergy: Float,
        pulse: Float,
        swirl: Float,
        intensity: Float,
        glow: Float,
        core: Float,
        glowOn: Float,
        glowRadius: Float,
        audioLevel: Float,
        audioReactivity: Float
    ) {
        val audio = audioLevel * audioReactivity
        // 0.55 Hz pulse from the unwrapped clock, so it never jumps when `time` wraps.
        val pulseWave = 0.5f + 0.5f * sin((timeD * PULSE_HZ % 1.0) * 2.0 * PI).toFloat()
        this.time = time
        this.timeD = timeD
        this.pulse = pulse * pulseWave
        this.swirl = swirl
        this.core = core * (0.75f + 0.25f * stateEnergy)
        this.glowEnabled = glowOn
        this.audio = audio.coerceAtMost(1.5f)
        this.energy = intensity * amplitude * preset.motion * 1.1f + audio * 0.85f
        this.brightness = (0.5f + 0.5f * intensity) * preset.brightness * (0.8f + 0.2f * stateEnergy) + audio * 0.15f
        this.glow = glow * preset.glow * (0.75f + 0.3f * stateEnergy + 0.25f * this.pulse + 0.3f * audio)
        this.glowRadius = glowRadius
    }

    /** Fully settled values for [state] and [config] at time [timeD]; used by previews and tests. */
    fun fillSettled(state: SiriAnimationState, config: SiriAnimationConfig, timeD: Double, audioLevel: Float = 0f) {
        fill(
            (timeD % TIME_WRAP).toFloat(), timeD, config.preset, state.amplitude, state.energy, state.pulse, state.swirl,
            config.intensity, config.glowIntensity, config.coreIntensity, if (config.isGlowEnabled) 1f else 0f,
            config.glowRadius, audioLevel, config.audioReactivity
        )
    }

    companion object {
        const val PULSE_HZ = 0.55
        /** Wrap period of [time]; kept for the band, whose motion only uses low-frequency phases. */
        const val TIME_WRAP = 10_000.0
    }
}
