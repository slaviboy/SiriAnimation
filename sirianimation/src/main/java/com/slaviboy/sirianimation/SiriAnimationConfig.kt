package com.slaviboy.sirianimation

/**
 * Immutable configuration shared by [SiriAnimationView] and the [SiriAnimation] composable.
 * Use [copy] to change individual values. Out-of-range values throw [IllegalArgumentException].
 *
 * @property intensity Overall visual energy, `0..1`. Default `0.8`.
 * @property speed Animation speed multiplier, `0..3`. Default `1.0`.
 * @property glowIntensity Bloom / glow strength, `0..2`. Default `1.0`.
 * @property isAnimated When `false`, a static frame is shown. Default `true`.
 * @property preset Color and motion preset. Default [SiriAnimationPreset.CLASSIC].
 * @property customPalette Palette used with [SiriAnimationPreset.CUSTOM].
 * @property orbSizeFraction Orb diameter as a fraction of the drawing area's smaller side, `0.2..1`.
 *   The remaining space is used for the glow. Default `0.6`.
 * @property bandHeightFraction Height of the band region as a fraction of the drawing area height, `0.05..1`. Default `0.6`.
 * @property bandVerticalBias Vertical position of the band center: `0` top, `0.5` center, `1` bottom. Default `0.5`.
 * @property audioReactivity How strongly the audio level affects the animation, `0..2`. Default `1.0`.
 * @property coreIntensity Strength of the orb's internal light source, `0..2`. Default `1.0`.
 * @property isGlowEnabled Whether the outer glow is drawn (eased when toggled). Default `true`.
 * @property glowRadius How far the orb's outer glow spreads, `0.5..2`. It is limited by the space
 *   around the orb (see [orbSizeFraction]). Eased when changed. The band ignores it. Default `1.0`.
 */
data class SiriAnimationConfig(
    val intensity: Float = 0.8f,
    val speed: Float = 1f,
    val glowIntensity: Float = 1f,
    val isAnimated: Boolean = true,
    val preset: SiriAnimationPreset = SiriAnimationPreset.CLASSIC,
    val customPalette: SiriPalette? = null,
    val orbSizeFraction: Float = 0.6f,
    val bandHeightFraction: Float = 0.6f,
    val bandVerticalBias: Float = 0.5f,
    val audioReactivity: Float = 1f,
    val coreIntensity: Float = 1f,
    val isGlowEnabled: Boolean = true,
    val glowRadius: Float = 1f
) {
    init {
        require(intensity in 0f..1f) { "intensity must be in 0..1, was $intensity" }
        require(speed in 0f..3f) { "speed must be in 0..3, was $speed" }
        require(glowIntensity in 0f..2f) { "glowIntensity must be in 0..2, was $glowIntensity" }
        require(orbSizeFraction in 0.2f..1f) { "orbSizeFraction must be in 0.2..1, was $orbSizeFraction" }
        require(bandHeightFraction in 0.05f..1f) { "bandHeightFraction must be in 0.05..1, was $bandHeightFraction" }
        require(bandVerticalBias in 0f..1f) { "bandVerticalBias must be in 0..1, was $bandVerticalBias" }
        require(audioReactivity in 0f..2f) { "audioReactivity must be in 0..2, was $audioReactivity" }
        require(coreIntensity in 0f..2f) { "coreIntensity must be in 0..2, was $coreIntensity" }
        require(glowRadius in 0.5f..2f) { "glowRadius must be in 0.5..2, was $glowRadius" }
    }

    /** The palette actually used for rendering. */
    val palette: SiriPalette get() = SiriAnimationPresets.paletteFor(preset, customPalette)

    companion object {
        /** Default configuration. */
        @JvmField
        val DEFAULT = SiriAnimationConfig()

        /** A band near the bottom of a full-screen assistant layout. */
        @JvmField
        val ASSISTANT_BAND = SiriAnimationConfig(bandHeightFraction = 0.28f, bandVerticalBias = 0.86f)
    }
}
