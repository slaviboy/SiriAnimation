package com.slaviboy.sirianimation

import androidx.annotation.ColorInt

/**
 * Color palette used by both animation styles.
 *
 * @property colors Accent colors for energy lobes / wave layers (3 to 8 colors).
 * @property glowColor Color of the outer bloom / haze.
 * @property coreColor Color of the bright inner core.
 * @property rimColor Color of the orb's glassy rim.
 * @property bodyColor Base color of the orb's sphere body.
 */
data class SiriPalette(
    val colors: List<Int>,
    @param:ColorInt val glowColor: Int,
    @param:ColorInt val coreColor: Int = 0xFFFFFFFF.toInt(),
    @param:ColorInt val rimColor: Int = 0xFFC9B8FF.toInt(),
    @param:ColorInt val bodyColor: Int = 0xFF120D30.toInt()
) {
    init {
        require(colors.size in 3..8) { "SiriPalette needs between 3 and 8 colors, got ${colors.size}" }
    }
}

/**
 * Built-in looks. A preset defines a palette plus how bright, energetic and glowing it is.
 */
enum class SiriAnimationPreset(
    internal val brightness: Float,
    internal val motion: Float,
    internal val glow: Float
) {
    /** Balanced colors and motion inspired by the classic assistant look. */
    CLASSIC(1f, 1f, 1f),

    /** Stronger saturation and illumination. */
    VIBRANT(1.25f, 1.2f, 1.3f),

    /** Restrained movement and softer glow. */
    SUBTLE(0.75f, 0.6f, 0.65f),

    /** Uses [SiriAnimationConfig.customPalette]; falls back to [CLASSIC] colors when none is set. */
    CUSTOM(1f, 1f, 1f)
}

/** Ready-made palettes for each [SiriAnimationPreset]. */
object SiriAnimationPresets {

    /** Electric blue, cyan, turquoise, violet, magenta and pink. */
    @JvmField
    val CLASSIC_PALETTE = SiriPalette(
        colors = listOf(
            0xFFFF2E88.toInt(), // magenta pink
            0xFF2BD9FF.toInt(), // cyan
            0xFF6A3DFF.toInt(), // violet
            0xFF1F6BFF.toInt(), // electric blue
            0xFF35F0C2.toInt()  // turquoise
        ),
        glowColor = 0xFF5B3CFF.toInt(),
        rimColor = 0xFFCDB9FF.toInt(),
        bodyColor = 0xFF140F38.toInt()
    )

    /** Fully saturated, neon variant. */
    @JvmField
    val VIBRANT_PALETTE = SiriPalette(
        colors = listOf(
            0xFFFF1F7A.toInt(),
            0xFF00E5FF.toInt(),
            0xFF8A2BFF.toInt(),
            0xFF0F5BFF.toInt(),
            0xFF00FFB3.toInt(),
            0xFFFF4FD8.toInt()
        ),
        glowColor = 0xFF7A2CFF.toInt(),
        rimColor = 0xFFE0C8FF.toInt(),
        bodyColor = 0xFF160B3D.toInt()
    )

    /** Softer pastel variant. */
    @JvmField
    val SUBTLE_PALETTE = SiriPalette(
        colors = listOf(
            0xFFD86AA6.toInt(),
            0xFF6FC6DE.toInt(),
            0xFF7C6BD6.toInt(),
            0xFF4E7AD8.toInt(),
            0xFF6BD8BC.toInt()
        ),
        glowColor = 0xFF4A3F9E.toInt(),
        rimColor = 0xFFA99CD8.toInt(),
        bodyColor = 0xFF100D28.toInt()
    )

    /** Returns the palette for [preset], using [custom] for [SiriAnimationPreset.CUSTOM]. */
    @JvmStatic
    fun paletteFor(preset: SiriAnimationPreset, custom: SiriPalette? = null): SiriPalette = when (preset) {
        SiriAnimationPreset.CLASSIC -> CLASSIC_PALETTE
        SiriAnimationPreset.VIBRANT -> VIBRANT_PALETTE
        SiriAnimationPreset.SUBTLE -> SUBTLE_PALETTE
        SiriAnimationPreset.CUSTOM -> custom ?: CLASSIC_PALETTE
    }
}
