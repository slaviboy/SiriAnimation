package com.slaviboy.sirianimation

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import kotlin.math.floor
import kotlin.math.sin

/** Common contract for the two styles. */
internal interface SiriAnimationRenderer {
    /**
     * Whether the engine must draw this renderer into its own layer even at full opacity
     * (true when it uses blend modes other than SRC_OVER that must not touch content behind the view).
     */
    val needsIsolation: Boolean get() = true

    /** Called when size, palette or layout-affecting config changes. May allocate. */
    fun configure(width: Int, height: Int, density: Float, palette: SiriPalette, config: SiriAnimationConfig)

    /** Draws one frame. Must not allocate. */
    fun draw(canvas: Canvas, p: FrameParams)

    /** Frees resources; the renderer may be configured again later. */
    fun release() {}
}

/** Allocation-free helpers shared by the renderers. */
internal object RenderUtils {

    /** Smooth 1D value noise in `-1..1`. */
    fun noise(x: Float): Float {
        val i = floor(x)
        val f = x - i
        val u = f * f * (3f - 2f * f)
        val a = hash(i)
        return a + (hash(i + 1f) - a) * u
    }

    private fun hash(n: Float): Float {
        val s = sin(n * 127.1f + 311.7f) * 43758.547f
        return (s - floor(s)) * 2f - 1f
    }

    /** Builds a closed, smooth path through [count] points using quadratic midpoint interpolation. */
    fun smoothClosedPath(path: Path, xs: FloatArray, ys: FloatArray, count: Int) {
        path.rewind()
        if (count < 3) return
        path.moveTo((xs[count - 1] + xs[0]) * 0.5f, (ys[count - 1] + ys[0]) * 0.5f)
        for (i in 0 until count) {
            val n = if (i + 1 == count) 0 else i + 1
            path.quadTo(xs[i], ys[i], (xs[i] + xs[n]) * 0.5f, (ys[i] + ys[n]) * 0.5f)
        }
        path.close()
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(alpha255(alpha), Color.red(color), Color.green(color), Color.blue(color))

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }

    fun alpha255(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).toInt()
}
