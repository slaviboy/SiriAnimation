package com.slaviboy.sirianimation

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Compatibility orb renderer for API 23–32 (and the "force CPU" test switch). Uses only standard
 * Canvas APIs:
 *
 * - The interior (blades, core, glass body, fresnel) is shaded by the same [OrbField] as the GPU
 *   shader into a small bitmap, then drawn as an anti-aliased circle through a bilinear
 *   [BitmapShader]. Rows are shared between the UI thread and up to three workers
 *   ([OrbRowPool]); the resolution adapts to the measured cost (48–128 texels across).
 * - The thin rim line, the light where blades reach the rim, and the outer halo are drawn at full
 *   resolution with gradients and arcs, so the silhouette stays crisp.
 *
 * Differences from the GPU path: blade edges are softer (one texel), the halo has a fixed tint,
 * and rim highlights are approximated with arcs.
 */
internal class SiriOrbCpuRenderer : SiriAnimationRenderer {

    private val field = OrbField(fastMath = true)
    /** Spreads the interior rows over spare cores; created on configure, stopped on release. */
    private var pool: OrbRowPool? = null
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private val shaderMatrix = Matrix()
    private val interiorPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var maxTier = 0
    private var tier = 0
    private var n = TIERS[0]

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var haloRadiusPx = 0f
    private var haloBuiltFor = -1f
    private val haloColors = IntArray(HALO_STOPS)
    private val haloStops = FloatArray(HALO_STOPS)
    private var fpx = 1f
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; xfermode = add }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = add
    }
    private val rimRect = RectF()

    // Adaptive resolution. Tier switches wait for the next frame, so a recorded frame's bitmap never changes.
    private var pendingTier = -1
    private var emaNs = -1f
    private var framesAtTier = 0
    private var slowFrames = 0
    private var fastFrames = 0

    override val needsIsolation: Boolean get() = false

    override fun configure(width: Int, height: Int, density: Float, palette: SiriPalette, config: SiriAnimationConfig) {
        cx = width / 2f
        cy = height / 2f
        val r = min(width, height) / 2f * config.orbSizeFraction
        if (r <= 0f) {
            radius = 0f
            return
        }
        // orbSizeFraction <= 1, so the extent is >= 1; at 1 there is no room (and no halo).
        val extent = min(width, height) / 2f / r
        field.setPalette(palette)
        field.setExtent(extent)
        fpx = 1f / r

        if (pool == null) pool = OrbRowPool(OrbRowPool.defaultWorkers())
        if (r != radius || bitmap == null) {
            radius = r
            // More texels than about one per 3 px add nothing after bilinear upscaling.
            var cap = 0
            for (i in TIERS.indices) if (TIERS[i] <= 2f * r / 3f) cap = i
            maxTier = cap
            val maxN = TIERS[cap]
            val bmp = bitmap
            if (bmp == null || bmp.width != maxN) {
                bitmap = Bitmap.createBitmap(maxN, maxN, Bitmap.Config.ARGB_8888)
                pixels = IntArray(maxN * maxN)
            }
            interiorPaint.shader = BitmapShader(bitmap!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            pendingTier = -1
            setTier(min(if (learnedTier >= 0) learnedTier else START_TIER, maxTier))
        }
        // The center can move while the radius stays (e.g. only the longer side changed).
        updateInteriorMatrix()
        buildHalo(config.glowRadius)

        // Rim line: same position and width as the shader's gaussian ring, tinted by direction.
        val w = max(OrbField.RIM_W, fpx)
        rimPaint.strokeWidth = 1.3f * w * r
        val ringR = r * (1f - 1.3f * w)
        rimRect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        val rimColors = IntArray(RIM_STOPS + 1)
        for (i in 0..RIM_STOPS) {
            val ang = OrbMath.TWO_PI * i / RIM_STOPS
            val dx = cos(ang)
            val dy = sin(ang)
            val lit = max(-0.55f * dx - 0.835f * dy, 0f)
            rimColors[i] = field.rimTintArgb(dx, dy, RIM_LEVEL * (0.45f + 0.3f * lit))
        }
        rimPaint.shader = SweepGradient(cx, cy, rimColors, null)
        tipPaint.strokeWidth = rimPaint.strokeWidth
    }

    override fun draw(canvas: Canvas, p: FrameParams) {
        val bmp = bitmap ?: return
        if (radius <= 0f) return
        if (pendingTier >= 0) {
            setTier(pendingTier)
            pendingTier = -1
        }
        val f = field
        f.update(p)

        // 1. Halo behind the orb. Rebuilt only while glowRadius eases (the only allocation in this path).
        if (abs(p.glowRadius - haloBuiltFor) > 0.01f) buildHalo(p.glowRadius)
        val g = p.glow * p.glowEnabled
        if (g > 0.002f && haloRadiusPx > 0f) {
            haloPaint.alpha = RenderUtils.alpha255(g / HALO_REF)
            canvas.drawCircle(cx, cy, haloRadiusPx, haloPaint)
        }

        // 2. Interior, shaded at n x n texels and timed for the adaptive resolution.
        val t0 = System.nanoTime()
        val pl = pool
        if (pl != null) pl.render(f, pixels, n, MARGIN) else f.renderInterior(pixels, n, MARGIN)
        val ns = System.nanoTime() - t0
        bmp.setPixels(pixels, 0, n, 0, 0, n, n)
        canvas.drawCircle(cx, cy, radius, interiorPaint)
        adapt(ns)

        // 3. Rim line and the light where blades reach the glass.
        val level = 0.8f + 0.2f * p.brightness
        rimPaint.alpha = RenderUtils.alpha255(0.7f * level)
        canvas.drawArc(rimRect, 0f, 360f, false, rimPaint)
        for (k in 0 until OrbField.BLADES) {
            val o = 4 * k
            val e = f.bt[o + 2] * level
            if (e < 0.02f) continue
            val dx = f.bt[o]
            val dy = f.bt[o + 1]
            val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            tipPaint.color = f.rimTintArgb(dx, dy, RIM_LEVEL * min(e, 2f))
            tipPaint.alpha = 110
            canvas.drawArc(rimRect, deg - 22f, 44f, false, tipPaint)
            tipPaint.alpha = 90
            canvas.drawArc(rimRect, deg - 9f, 18f, false, tipPaint)
        }
    }

    /** The shader's radial profile sampled into a gradient; strength is applied through the paint alpha. */
    private fun buildHalo(glowRadius: Float) {
        haloBuiltFor = glowRadius
        val reach = field.haloReach(glowRadius, fpx)
        if (reach <= 1f + 2f * fpx) {
            // No room around the orb for a halo.
            haloRadiusPx = 0f
            haloPaint.shader = null
            return
        }
        haloRadiusPx = reach * radius
        val inner = (1f - 2f * fpx) / reach
        val g = field.glowCol
        for (i in 0 until HALO_STOPS) {
            val s = inner + (1f - inner) * i / (HALO_STOPS - 1)
            val a = (field.haloProfile(s * reach, glowRadius, fpx) * HALO_REF).coerceIn(0f, 1f)
            haloStops[i] = s
            haloColors[i] = argb(a, g[0], g[1], g[2])
        }
        haloStops[0] = 0f
        haloPaint.shader = RadialGradient(cx, cy, haloRadiusPx, haloColors, haloStops, Shader.TileMode.CLAMP)
    }

    /** Maps the n x n texel area onto the orb square (plus margin) at the current center. */
    private fun updateInteriorMatrix() {
        val s = 2f * radius * MARGIN / n
        shaderMatrix.setScale(s, s)
        shaderMatrix.postTranslate(cx - radius * MARGIN, cy - radius * MARGIN)
        interiorPaint.shader?.setLocalMatrix(shaderMatrix)
    }

    private fun setTier(i: Int) {
        val lastEmaNs = max(emaNs, 0f)
        tier = i
        n = TIERS[i]
        learnedTier = i
        // Texels outside the active n x n area are never sampled, so the bitmap needs no clearing.
        updateInteriorMatrix()
        emaNs = -1f
        framesAtTier = 0
        slowFrames = 0
        fastFrames = 0
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "CPU orb interior: ${n}px (previous tier cost ${(lastEmaNs / 1000).toInt()} us)")
    }

    /** Lowers the interior resolution when it is too slow; raises it again only after a sustained margin. */
    private fun adapt(ns: Long) {
        if (++framesAtTier <= WARMUP_FRAMES) return
        emaNs = if (emaNs < 0f) ns.toFloat() else emaNs + (ns - emaNs) * 0.1f
        if (emaNs > BUDGET_NS) {
            if (++slowFrames >= 8 && tier > 0) {
                pendingTier = tier - 1
                return
            }
        } else {
            slowFrames = 0
        }
        if (tier < maxTier) {
            val ratio = TIERS[tier + 1].toFloat() / n
            if (emaNs * ratio * ratio < 0.6f * BUDGET_NS) {
                if (++fastFrames >= 180) pendingTier = tier + 1
            } else {
                fastFrames = 0
            }
        }
    }

    override fun release() {
        pool?.shutdown()
        pool = null
        // No recycle(): the bitmap may still be referenced by a pending display list.
        interiorPaint.shader = null
        bitmap = null
        pixels = IntArray(0)
        radius = 0f
    }

    private fun argb(a: Float, r: Float, g: Float, b: Float): Int =
        (RenderUtils.alpha255(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)

    private fun ch(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    private companion object {
        const val TAG = "SiriAnimation"
        val TIERS = intArrayOf(48, 64, 96, 128)
        const val START_TIER = 1
        const val WARMUP_FRAMES = 10
        const val BUDGET_NS = 4_000_000f
        const val MARGIN = 1.04f
        const val HALO_STOPS = 12
        // Largest glow strength the gradient represents linearly (stop alphas stay <= 0.85).
        const val HALO_REF = 5f
        const val RIM_STOPS = 16
        const val RIM_LEVEL = OrbField.RIM_LEVEL

        /** Last tier that held its budget on this device; new renderers start there. */
        var learnedTier = -1
    }
}
