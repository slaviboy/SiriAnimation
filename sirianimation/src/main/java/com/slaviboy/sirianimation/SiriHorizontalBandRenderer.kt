package com.slaviboy.sirianimation

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import com.slaviboy.sirianimation.RenderUtils.alpha255
import com.slaviboy.sirianimation.RenderUtils.noise
import com.slaviboy.sirianimation.RenderUtils.withAlpha
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Renders the horizontal light band: several translucent, mirrored "lens" ribbons whose
 * lobes wander, swell and fade independently, blended additively into a bright center
 * with soft glow above and below.
 */
internal class SiriHorizontalBandRenderer : SiriAnimationRenderer {

    private var width = 0f
    private var centerY = 0f
    private var maxAmp = 0f
    private var minThickness = 0f
    private var layerCount = 0
    private var colors = IntArray(0)

    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = add }
    private val hazePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = add }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = add }
    private var hazeRadius = 0f
    private var hazeScaleY = 1f
    private var lineHalf = 1f

    private val sampleX = FloatArray(SAMPLES)
    private val sampleU = FloatArray(SAMPLES)
    private val attenuation = FloatArray(SAMPLES)
    private val upper = FloatArray(SAMPLES)
    private val xs = FloatArray(SAMPLES * 2)
    private val ys = FloatArray(SAMPLES * 2)
    private val path = Path()

    override fun configure(width: Int, height: Int, density: Float, palette: SiriPalette, config: SiriAnimationConfig) {
        this.width = width.toFloat()
        val bandHeight = height * config.bandHeightFraction
        val half = bandHeight / 2f
        centerY = (height * config.bandVerticalBias).coerceIn(half, height - half)
        // The widest glow pass is 2.2x the ribbon; its faint tail may softly reach the band edge.
        maxAmp = half / 1.75f
        minThickness = 0.7f * density

        for (i in 0 until SAMPLES) {
            val s = i / (SAMPLES - 1f)
            sampleX[i] = s * width
            val u = s * 2f - 1f
            sampleU[i] = u
            // Classic bell attenuation: bright center, tapering to nothing at the edges.
            val x = u * 2f
            attenuation[i] = (4f / (4f + x * x * x * x)).pow(4)
        }

        layerCount = min(palette.colors.size, MAX_LAYERS)
        colors = IntArray(layerCount) { palette.colors[it] }

        hazeRadius = width * 0.48f
        hazeScaleY = if (hazeRadius > 0f) (half * 0.9f) / hazeRadius else 1f
        hazePaint.shader = RadialGradient(
            width / 2f, centerY, maxOf(hazeRadius, 1f),
            intArrayOf(withAlpha(palette.glowColor, 0.55f), withAlpha(palette.glowColor, 0.18f), 0),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        lineHalf = maxOf(0.6f * density, 0.6f)
        linePaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(0, withAlpha(palette.colors[1], 0.35f), withAlpha(palette.coreColor, 0.9f), withAlpha(palette.colors[0], 0.35f), 0),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        corePaint.shader = RadialGradient(
            width / 2f, centerY, maxOf(width * 0.18f, 1f),
            intArrayOf(withAlpha(palette.coreColor, 0.85f), withAlpha(palette.coreColor, 0.2f), 0),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas, p: FrameParams) {
        if (width <= 0f || maxAmp <= 0f) return
        val t = p.time
        val e = p.energy
        val ampScale = (0.07f + 0.93f * e).coerceAtMost(1.35f)
        val cxMid = width / 2f

        // Soft haze behind the band.
        hazePaint.alpha = alpha255(0.5f * p.glow * p.brightness * (0.55f + 0.45f * min(e, 1f)))
        canvas.save()
        canvas.scale(1f, hazeScaleY * (0.7f + 0.5f * min(e, 1.2f)), cxMid, centerY)
        canvas.drawCircle(cxMid, centerY, hazeRadius, hazePaint)
        canvas.restore()

        for (layer in 0 until layerCount) {
            buildRibbon(layer, t, ampScale)
            layerPaint.color = colors[layer]
            // Glow passes: the same ribbon stretched vertically, fainter.
            layerPaint.alpha = alpha255(0.09f * p.glow * p.brightness)
            canvas.save()
            canvas.scale(1f, 2.2f, cxMid, centerY)
            canvas.drawPath(path, layerPaint)
            canvas.restore()
            layerPaint.alpha = alpha255(0.2f * p.glow * p.brightness)
            canvas.save()
            canvas.scale(1f, 1.45f, cxMid, centerY)
            canvas.drawPath(path, layerPaint)
            canvas.restore()
            layerPaint.alpha = alpha255(0.6f * p.brightness)
            canvas.drawPath(path, layerPaint)
        }

        // Thin luminous support line and bright core.
        linePaint.alpha = alpha255(0.75f * p.brightness)
        canvas.drawRect(0f, centerY - lineHalf, width, centerY + lineHalf, linePaint)
        corePaint.alpha = alpha255(p.brightness * (0.22f + 0.35f * min(e, 1.2f) + 0.2f * p.pulse))
        canvas.save()
        canvas.scale(1f, (maxAmp * (0.6f + 0.9f * min(e, 1.2f))) / (width * 0.18f), cxMid, centerY)
        canvas.drawCircle(cxMid, centerY, width * 0.18f, corePaint)
        canvas.restore()
    }

    /** Fills [path] with a mirrored ribbon made of two wandering lobes for [layer]. */
    private fun buildRibbon(layer: Int, t: Float, ampScale: Float) {
        val seed = layer * 2.917f + 0.5f
        val dir = if (layer % 2 == 0) 1f else -1f

        // Two independent lobes per layer.
        val c0 = 0.5f * noise(t * 0.11f + seed)
        val w0 = 0.26f + 0.16f * (0.5f + 0.5f * noise(t * 0.19f + seed * 2f))
        val a0 = 0.35f + 0.65f * (0.5f + 0.5f * noise(t * 0.47f + seed * 3f))
        val f0 = 1.6f + 1.2f * (0.5f + 0.5f * noise(t * 0.07f + seed * 4f))
        val ph0 = t * (1.5f + 0.27f * layer) * dir + seed

        val c1 = 0.55f * noise(t * 0.09f + seed + 17f)
        val w1 = 0.2f + 0.2f * (0.5f + 0.5f * noise(t * 0.23f + seed * 5f))
        val a1 = 0.25f + 0.75f * (0.5f + 0.5f * noise(t * 0.39f + seed * 6f))
        val f1 = 2.2f + 1.4f * (0.5f + 0.5f * noise(t * 0.05f + seed * 7f))
        val ph1 = -t * (1.15f + 0.2f * layer) * dir + seed * 3f

        val lowerScale = 0.82f + 0.18f * noise(t * 0.5f + seed * 8f)
        val pi = PI.toFloat()

        for (i in 0 until SAMPLES) {
            val u = sampleU[i]
            val d0 = (u - c0) / w0
            val d1 = (u - c1) / w1
            var v = a0 * exp(-d0 * d0) * abs(sin(f0 * u * pi + ph0)) +
                a1 * exp(-d1 * d1) * abs(sin(f1 * u * pi + ph1))
            if (v > 1.2f) v = 1.2f
            val y = maxOf(maxAmp * ampScale * attenuation[i] * v, minThickness * attenuation[i])
            upper[i] = y
            xs[i] = sampleX[i]
            ys[i] = centerY - y
        }
        // Lower half, traversed right to left.
        for (i in 0 until SAMPLES) {
            val src = SAMPLES - 1 - i
            xs[SAMPLES + i] = sampleX[src]
            ys[SAMPLES + i] = centerY + upper[src] * lowerScale
        }
        RenderUtils.smoothClosedPath(path, xs, ys, SAMPLES * 2)
    }

    private companion object {
        const val SAMPLES = 96
        const val MAX_LAYERS = 6
    }
}
