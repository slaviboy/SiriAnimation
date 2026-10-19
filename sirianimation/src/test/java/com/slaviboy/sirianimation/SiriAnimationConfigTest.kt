package com.slaviboy.sirianimation

import org.junit.Assert.assertEquals
import org.junit.Test

class SiriAnimationConfigTest {

    @Test
    fun glowRadiusDefaultsToOne() {
        assertEquals(1f, SiriAnimationConfig.DEFAULT.glowRadius, 0f)
    }

    @Test
    fun glowRadiusAcceptsItsRange() {
        SiriAnimationConfig(glowRadius = 0.5f)
        SiriAnimationConfig(glowRadius = 2f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun glowRadiusRejectsTooSmall() {
        SiriAnimationConfig(glowRadius = 0.49f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun glowRadiusRejectsTooLarge() {
        SiriAnimationConfig(glowRadius = 2.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun glowRadiusRejectsNaN() {
        SiriAnimationConfig(glowRadius = Float.NaN)
    }
}
