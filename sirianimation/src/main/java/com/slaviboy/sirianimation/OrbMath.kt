package com.slaviboy.sirianimation

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Fast, allocation-free math for the CPU orb path. Pure Kotlin so JVM tests use the same code.
 * Accuracy is far below one 8-bit step after tone mapping; `OrbFieldTest` checks it against
 * `kotlin.math`.
 */
internal object OrbMath {
    const val TWO_PI = (2.0 * Math.PI).toFloat()
    private const val LUT_SIZE = 4096
    private val SIN_LUT = FloatArray(LUT_SIZE + 1) { kotlin.math.sin(it * TWO_PI / LUT_SIZE) }

    /** Table sine with linear interpolation. */
    fun sin(x: Float): Float {
        var u = x / TWO_PI
        u -= floor(u)
        val f = u * LUT_SIZE
        var i = f.toInt()
        // u can round up to exactly 1.0 for tiny negative inputs.
        if (i >= LUT_SIZE) i = LUT_SIZE - 1
        val a = SIN_LUT[i]
        return a + (SIN_LUT[i + 1] - a) * (f - i)
    }

    fun cos(x: Float): Float = sin(x + TWO_PI * 0.25f)

    /** exp via 2^x with a 5th-order polynomial; relative error about 2e-4. */
    fun exp(x: Float): Float {
        if (x < -80f) return 0f
        val t = (if (x > 80f) 80f else x) * 1.442695f
        val fi = floor(t)
        val f = t - fi
        val p = 1f + f * (0.6931472f + f * (0.2402265f + f * (0.05550411f + f * (0.009618129f + f * 0.0013333558f))))
        return java.lang.Float.intBitsToFloat((fi.toInt() + 127) shl 23) * p
    }

    /** Polynomial atan2, max error about 2e-4 rad (near |y| = |x|). Returns 0 for (0, 0). */
    fun atan2(y: Float, x: Float): Float {
        val ax = abs(x)
        val ay = abs(y)
        val mx = max(ax, ay)
        if (mx == 0f) return 0f
        val a = min(ax, ay) / mx
        val s = a * a
        var r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a
        if (ay > ax) r = 1.5707964f - r
        if (x < 0f) r = 3.1415927f - r
        return if (y < 0f) -r else r
    }

    /** GLSL `smoothstep`; requires `e0 < e1`. */
    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
