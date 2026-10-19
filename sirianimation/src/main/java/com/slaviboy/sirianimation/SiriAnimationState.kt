package com.slaviboy.sirianimation

/**
 * Optional visual presentation state. States only change how the animation looks;
 * the library never records audio or performs speech recognition.
 *
 * Transitions between states are smoothed automatically.
 */
enum class SiriAnimationState(
    internal val speed: Float,
    internal val amplitude: Float,
    internal val energy: Float,
    internal val pulse: Float,
    internal val swirl: Float
) {
    /** Slow, subtle motion. */
    IDLE(speed = 0.45f, amplitude = 0.28f, energy = 0.55f, pulse = 0f, swirl = 0f),

    /** Gentle pulses and moderate movement. */
    LISTENING(speed = 0.85f, amplitude = 0.55f, energy = 0.8f, pulse = 1f, swirl = 0.1f),

    /** Evolving, swirling fluid patterns. */
    THINKING(speed = 1.25f, amplitude = 0.45f, energy = 0.85f, pulse = 0.25f, swirl = 1f),

    /** Strong energy and pronounced movement. */
    SPEAKING(speed = 1.45f, amplitude = 1f, energy = 1f, pulse = 0.45f, swirl = 0.3f)
}
