package com.slaviboy.sirianimation

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * Classic View host for the Siri-style animations. Works from XML or code.
 *
 * ```kotlin
 * val siriView = SiriAnimationView(context).apply {
 *     style = SiriAnimationStyle.ORB
 *     state = SiriAnimationState.LISTENING
 * }
 * siriView.setStyle(SiriAnimationStyle.HORIZONTAL_BAND) // crossfades
 * ```
 *
 * The view draws no background; place it on a dark surface. Frames are only produced while the
 * view is attached, visible and playing (or while a transition is easing in).
 * For Jetpack Compose use the [SiriAnimation] composable instead.
 */
class SiriAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val engine = SiriAnimationEngine()
    private var lastFrameNanos = 0L

    /** Current style. Setting it crossfades to the new style; see [setStyle]. */
    var style: SiriAnimationStyle
        get() = engine.style
        set(value) = setStyle(value, animate = true)

    /** Current visual state. Setting it eases smoothly into the new state; see [setState]. */
    var state: SiriAnimationState
        get() = engine.state
        set(value) = setState(value, animate = true)

    /** Full configuration. Intensity and glow changes are smoothed; layout changes apply immediately. */
    var config: SiriAnimationConfig
        get() = engine.config
        set(value) {
            engine.setConfig(value)
            kick()
        }

    /** Whether the animation is playing. Pausing freezes the pattern without losing its position. */
    var isPlaying: Boolean = true
        private set

    /**
     * Forces the CPU compatibility orb renderer (used automatically below API 33) even on newer
     * devices. Intended for testing and visual comparison.
     */
    var forceCompatibilityRenderer: Boolean
        get() = engine.forceCompatibilityRenderer
        set(value) {
            engine.forceCompatibilityRenderer = value
            invalidate()
        }

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.SiriAnimationView, defStyleAttr, 0)
            try {
                val d = SiriAnimationConfig.DEFAULT
                engine.setConfig(
                    SiriAnimationConfig(
                        intensity = a.getFloat(R.styleable.SiriAnimationView_siri_intensity, d.intensity),
                        speed = a.getFloat(R.styleable.SiriAnimationView_siri_speed, d.speed),
                        glowIntensity = a.getFloat(R.styleable.SiriAnimationView_siri_glowIntensity, d.glowIntensity),
                        isAnimated = a.getBoolean(R.styleable.SiriAnimationView_siri_animated, d.isAnimated),
                        preset = SiriAnimationPreset.entries[a.getInt(R.styleable.SiriAnimationView_siri_preset, d.preset.ordinal)],
                        orbSizeFraction = a.getFloat(R.styleable.SiriAnimationView_siri_orbSizeFraction, d.orbSizeFraction),
                        bandHeightFraction = a.getFloat(R.styleable.SiriAnimationView_siri_bandHeightFraction, d.bandHeightFraction),
                        bandVerticalBias = a.getFloat(R.styleable.SiriAnimationView_siri_bandVerticalBias, d.bandVerticalBias),
                        audioReactivity = a.getFloat(R.styleable.SiriAnimationView_siri_audioReactivity, d.audioReactivity),
                        coreIntensity = a.getFloat(R.styleable.SiriAnimationView_siri_coreIntensity, d.coreIntensity),
                        isGlowEnabled = a.getBoolean(R.styleable.SiriAnimationView_siri_glowEnabled, d.isGlowEnabled),
                        glowRadius = a.getFloat(R.styleable.SiriAnimationView_siri_glowRadius, d.glowRadius)
                    )
                )
                engine.setStyle(SiriAnimationStyle.entries[a.getInt(R.styleable.SiriAnimationView_siri_style, 0)], animate = false)
                engine.setState(SiriAnimationState.entries[a.getInt(R.styleable.SiriAnimationView_siri_state, 0)], animate = false)
            } finally {
                a.recycle()
            }
        }
    }

    /** Switches style, crossfading over ~450 ms when [animate] is true. */
    fun setStyle(style: SiriAnimationStyle, animate: Boolean = true) {
        engine.setStyle(style, animate && isLaidOut)
        kick()
    }

    /** Changes the visual state, easing smoothly when [animate] is true. */
    fun setState(state: SiriAnimationState, animate: Boolean = true) {
        engine.setState(state, animate)
        kick()
    }

    /**
     * Feeds a normalized audio level (`0..1`, e.g. RMS of your own microphone pipeline).
     * Safe to call at any rate from the main thread. The library never records audio itself.
     */
    fun setAudioLevel(level: Float) {
        // Feeding the same level every frame (e.g. silence) must not keep a paused view rendering.
        if (engine.setAudioLevel(level)) kick()
    }

    /** Resumes the animation from where it was paused, without jumps. */
    fun play() {
        isPlaying = true
        kick()
    }

    /** Pauses the animation; the current frame stays visible. */
    fun pause() {
        isPlaying = false
    }

    /** Convenience to update the configuration with [copy]-style lambdas: `update { it.copy(speed = 2f) }`. */
    inline fun updateConfig(block: (SiriAnimationConfig) -> SiriAnimationConfig) {
        config = block(config)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.setSize(w, h, resources.displayMetrics.density)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Resources are released on detach; restore them when re-attached with the same size.
        if (width > 0 && height > 0) engine.setSize(width, height, resources.displayMetrics.density)
        kick()
    }

    override fun onDetachedFromWindow() {
        lastFrameNanos = 0L
        engine.release()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) kick() else lastFrameNanos = 0L
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) kick() else lastFrameNanos = 0L
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        val running = isRunning
        engine.step(dt, advanceTime = running)
        engine.draw(canvas)
        if ((running || engine.isSettling) && canAnimate()) {
            postInvalidateOnAnimation()
        } else {
            lastFrameNanos = 0L
        }
    }

    private val isRunning: Boolean get() = isPlaying && engine.config.isAnimated

    private fun canAnimate(): Boolean = isAttachedToWindow && isShown && windowVisibility == VISIBLE

    /** Requests a frame; [onDraw] keeps the loop alive only while needed. */
    private fun kick() {
        if (canAnimate()) postInvalidateOnAnimation()
    }
}
