package com.slaviboy.sirianimation

import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One rendered frame composited over the demo stage background. */
internal class OrbFrame(val w: Int, val h: Int, val cx: Float, val cy: Float, val radius: Float) {
    /** Opaque ARGB after compositing over [OrbRaster.BG]. */
    val argb = IntArray(w * h)
    /** Orb coverage alpha (0..1) before compositing. */
    val alpha = FloatArray(w * h)

    fun r(i: Int) = (argb[i] ushr 16) and 0xFF
    fun g(i: Int) = (argb[i] ushr 8) and 0xFF
    fun b(i: Int) = argb[i] and 0xFF
    fun luma(i: Int) = 0.2126f * r(i) + 0.7152f * g(i) + 0.0722f * b(i)
}

/** Renders [OrbField] on the JVM exactly like the shader (per pixel) or like the CPU renderer. */
internal object OrbRaster {
    /** Roughly the demo stage color behind the orb. */
    val BG = intArrayOf(8, 6, 18)
    const val ORB_FRACTION = 0.62f

    fun params(state: SiriAnimationState, timeD: Double, audio: Float = 0f, config: SiriAnimationConfig = SiriAnimationConfig.DEFAULT) =
        FrameParams().apply { fillSettled(state, config, timeD, audio) }

    fun newField(config: SiriAnimationConfig = SiriAnimationConfig.DEFAULT, fastMath: Boolean = false) =
        OrbField(fastMath).apply { setPalette(config.palette) }

    /** Per-pixel render, the same math as the AGSL shader. */
    fun renderFull(field: OrbField, p: FrameParams, radiusPx: Float): OrbFrame {
        val size = (ceil(2f * radiusPx / ORB_FRACTION / 2f) * 2f).toInt()
        val f = OrbFrame(size, size, size / 2f, size / 2f, radiusPx)
        field.setExtent(size / 2f / radiusPx)
        field.update(p)
        val fpx = 1f / radiusPx
        for (j in 0 until size) {
            for (i in 0 until size) {
                val x = (i + 0.5f - f.cx) / radiusPx
                val y = (j + 0.5f - f.cy) / radiusPx
                field.shade(x, y, fpx, shell = true, dither = ign(i, j))
                put(f, j * size + i, field.outR, field.outG, field.outB, field.outA)
            }
        }
        return f
    }

    /**
     * Approximates [SiriOrbCpuRenderer]: interior at [texels] upscaled bilinearly inside an
     * anti-aliased circle, full-resolution halo, and the rim drawn on top additively.
     */
    fun renderCpuSim(field: OrbField, p: FrameParams, radiusPx: Float, texels: Int = 96): OrbFrame {
        val size = (ceil(2f * radiusPx / ORB_FRACTION / 2f) * 2f).toInt()
        val f = OrbFrame(size, size, size / 2f, size / 2f, radiusPx)
        field.setExtent(size / 2f / radiusPx)
        field.update(p)
        val margin = 1.04f
        val tex = IntArray(texels * texels)
        field.renderInterior(tex, texels, margin)
        val fpx = 1f / radiusPx
        val w = max(OrbField.RIM_W, fpx)
        val level = OrbField.RIM_LEVEL * (0.8f + 0.2f * p.brightness)
        for (j in 0 until size) {
            for (i in 0 until size) {
                val x = (i + 0.5f - f.cx) / radiusPx
                val y = (j + 0.5f - f.cy) / radiusPx
                val r = sqrt(x * x + y * y)
                // Halo (strength scaled linearly, like the gradient + paint alpha).
                var cr = 0f
                var cg = 0f
                var cb = 0f
                var a = 0f
                if (field.halo[0] > 0f) {
                    val ga = (field.halo[0] * field.haloProfile(max(r, 1f - 2f * fpx), field.halo[1], fpx)).coerceIn(0f, 1f)
                    cr = field.glowCol[0] * ga; cg = field.glowCol[1] * ga; cb = field.glowCol[2] * ga; a = ga
                }
                val cov = (1f - OrbMath.smoothstep(1f - 1f * fpx, 1f + 0f * fpx, r))
                if (cov > 0f) {
                    // Bilinear texture lookup.
                    val u = (x + margin) / (2f * margin) * texels - 0.5f
                    val v = (y + margin) / (2f * margin) * texels - 0.5f
                    val c = bilinear(tex, texels, u, v)
                    var ir = ((c ushr 16) and 0xFF) / 255f
                    var ig = ((c ushr 8) and 0xFF) / 255f
                    var ib = (c and 0xFF) / 255f
                    // Rim line added on top (ADD blend in the renderer).
                    val d = (r - 1f + 1.3f * w) / w
                    val ring = kotlin.math.exp(-d * d)
                    if (ring > 0.01f && r > 0f) {
                        val dx = x / r
                        val dy = y / r
                        val lit = max(-0.55f * dx - 0.835f * dy, 0f)
                        var tip = 0f
                        for (k in 0 until OrbField.BLADES) {
                            val o = 4 * k
                            val dot = dx * field.bt[o] + dy * field.bt[o + 1]
                            if (dot > cos(Math.toRadians(22.0)).toFloat()) tip += field.bt[o + 2] * 0.55f
                            if (dot > cos(Math.toRadians(9.0)).toFloat()) tip += field.bt[o + 2] * 0.45f
                        }
                        val rc = field.rimTintArgb(dx, dy, OrbField.RIM_LEVEL * (0.45f + 0.3f * lit + tip))
                        val k = ring * (level / OrbField.RIM_LEVEL).coerceAtMost(1f)
                        ir += ((rc ushr 16) and 0xFF) / 255f * k
                        ig += ((rc ushr 8) and 0xFF) / 255f * k
                        ib += (rc and 0xFF) / 255f * k
                    }
                    cr = cr * (1f - cov) + min(ir, 1f) * cov
                    cg = cg * (1f - cov) + min(ig, 1f) * cov
                    cb = cb * (1f - cov) + min(ib, 1f) * cov
                    a = a * (1f - cov) + cov
                }
                put(f, j * size + i, cr, cg, cb, a)
            }
        }
        return f
    }

    private fun bilinear(tex: IntArray, n: Int, u: Float, v: Float): Int {
        val x0 = floor(u).toInt().coerceIn(0, n - 1)
        val y0 = floor(v).toInt().coerceIn(0, n - 1)
        val x1 = (x0 + 1).coerceAtMost(n - 1)
        val y1 = (y0 + 1).coerceAtMost(n - 1)
        val fx = (u - floor(u)).coerceIn(0f, 1f)
        val fy = (v - floor(v)).coerceIn(0f, 1f)
        var out = 0xFF shl 24
        for (s in intArrayOf(16, 8, 0)) {
            fun c(i: Int) = ((tex[i] ushr s) and 0xFF).toFloat()
            val top = c(y0 * n + x0) * (1 - fx) + c(y0 * n + x1) * fx
            val bot = c(y1 * n + x0) * (1 - fx) + c(y1 * n + x1) * fx
            out = out or (((top * (1 - fy) + bot * fy) + 0.5f).toInt().coerceIn(0, 255) shl s)
        }
        return out
    }

    private fun put(f: OrbFrame, i: Int, r: Float, g: Float, b: Float, a: Float) {
        f.alpha[i] = a
        val k = 1f - a
        f.argb[i] = (0xFF shl 24) or
            (q(r + BG[0] / 255f * k) shl 16) or (q(g + BG[1] / 255f * k) shl 8) or q(b + BG[2] / 255f * k)
    }

    private fun q(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** Interleaved-gradient noise, as in the shader. */
    fun ign(i: Int, j: Int): Float {
        val fx = i + 0.5f
        val fy = j + 0.5f
        val d = fx * 0.06711056f + fy * 0.00583715f
        val a = 52.9829189f * (d - floor(d))
        return a - floor(a)
    }

    fun write(file: File, f: OrbFrame) = PngWriter.write(file, f.w, f.h, f.argb)

    /** Grid of frames, each downscaled by an integer box filter. */
    fun contactSheet(file: File, rows: List<List<OrbFrame>>, down: Int) {
        val cw = rows[0][0].w / down
        val ch = rows[0][0].h / down
        val cols = rows.maxOf { it.size }
        val w = cols * cw
        val h = rows.size * ch
        val out = IntArray(w * h)
        for ((ri, row) in rows.withIndex()) for ((ci, fr) in row.withIndex()) {
            for (y in 0 until ch) for (x in 0 until cw) {
                var sr = 0
                var sg = 0
                var sb = 0
                for (dy in 0 until down) for (dx in 0 until down) {
                    val i = (y * down + dy) * fr.w + (x * down + dx)
                    sr += fr.r(i); sg += fr.g(i); sb += fr.b(i)
                }
                val n = down * down
                out[(ri * ch + y) * w + ci * cw + x] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
            }
        }
        PngWriter.write(file, w, h, out)
    }
}

/** Look metrics measured the same way as on the reference images (8-bit sRGB, Rec.709 luma). */
internal class OrbMetrics(f: OrbFrame) {
    var darkFraction = 0f
    var p95 = 0f
    var p99 = 0f
    var nearWhite = 0f
    var saturated = 0f
    var biggestHue = 0f
    var huesOver10 = 0
    var sharp20 = 0f
    var sharp40 = 0f
    /** 99.5th percentile of the luma gradient (per px) inside the disc: high when crisp edges exist. */
    var gradP995 = 0f
    var centerLuma = 0f
    var outerLuma = 0f
    var coreOffset = 0f
    var meanLuma = 0f
    var halo102 = 0f
    var halo120 = 0f
    var halo150 = 0f
    var haloEdge = 0f
    var rimOverHalo = 0f
    var nanFree = true

    init {
        val disc = ArrayList<Int>()
        val n = f.w * f.h
        for (i in 0 until n) {
            val x = (i % f.w + 0.5f - f.cx) / f.radius
            val y = (i / f.w + 0.5f - f.cy) / f.radius
            if (x * x + y * y < 0.96f * 0.96f) disc.add(i)
            if (f.alpha[i].isNaN()) nanFree = false
        }
        val lum = FloatArray(disc.size) { f.luma(disc[it]) }
        val sorted = lum.sortedArray()
        fun pct(p: Float) = sorted[((sorted.size - 1) * p).toInt()]
        val p5 = pct(0.05f)
        p95 = pct(0.95f)
        p99 = pct(0.99f)
        meanLuma = lum.average().toFloat()
        darkFraction = lum.count { it <= p5 + 10f }.toFloat() / lum.size
        nearWhite = disc.count { min(f.r(it), min(f.g(it), f.b(it))) >= 215 }.toFloat() / disc.size

        var midCount = 0
        var satCount = 0
        val buckets = IntArray(12)
        var lit = 0
        for ((k, i) in disc.withIndex()) {
            val r = f.r(i); val g = f.g(i); val b = f.b(i)
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val s = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            val l = lum[k]
            if (l in 60f..230f) {
                midCount++
                if (s > 0.7f) satCount++
            }
            if (l >= 80f && s > 0.3f) {
                lit++
                buckets[(hue(r, g, b) / 30f).toInt().coerceIn(0, 11)]++
            }
        }
        saturated = if (midCount == 0) 0f else satCount.toFloat() / midCount
        biggestHue = if (lit == 0) 1f else buckets.max().toFloat() / lit
        huesOver10 = if (lit == 0) 0 else buckets.count { it.toFloat() / lit >= 0.1f }

        var s20 = 0
        var s40 = 0
        val grads = FloatArray(disc.size)
        var gn = 0
        for (i in disc) {
            val x = i % f.w
            val y = i / f.w
            if (x + 1 >= f.w || y + 1 >= f.h) continue
            val l = f.luma(i)
            val gx = f.luma(i + 1) - l
            val gy = f.luma(i + f.w) - l
            val g = sqrt(gx * gx + gy * gy)
            if (g > 20f) s20++
            if (g > 40f) s40++
            grads[gn++] = g
        }
        sharp20 = s20.toFloat() / disc.size
        sharp40 = s40.toFloat() / disc.size
        val gs = grads.copyOf(gn).sortedArray()
        gradP995 = if (gn == 0) 0f else gs[((gn - 1) * 0.995f).toInt()]

        centerLuma = ringMean(f, 0f, 0.1f)
        outerLuma = ringMean(f, 0.8f, 0.9f)

        // Core offset: centroid of the brightest 1% of the disc.
        val thr = pct(0.99f)
        var sx = 0f
        var sy = 0f
        var c = 0
        for ((k, i) in disc.withIndex()) if (lum[k] >= thr) {
            sx += i % f.w + 0.5f - f.cx; sy += i / f.w + 0.5f - f.cy; c++
        }
        coreOffset = if (c == 0) 0f else sqrt((sx / c) * (sx / c) + (sy / c) * (sy / c)) / f.radius

        val bgL = 0.2126f * OrbRaster.BG[0] + 0.7152f * OrbRaster.BG[1] + 0.0722f * OrbRaster.BG[2]
        halo102 = ringSample(f, 1.02f) - bgL
        halo120 = ringSample(f, 1.2f) - bgL
        halo150 = ringSample(f, 1.5f) - bgL
        // Two pixels inside the drawing area's inscribed circle.
        haloEdge = ringSample(f, (f.w / 2f - 2f) / f.radius) - bgL

        // Rim must outshine the halo just outside it at (almost) every angle.
        var ok = 0
        for (a in 0 until 360) {
            val ang = Math.toRadians(a.toDouble())
            var peak = 0f
            var rr = 0.97f
            while (rr <= 1.0f) { peak = max(peak, sampleAt(f, rr, ang)); rr += 0.25f / f.radius }
            var out = 0f
            var cnt = 0
            rr = 1.01f
            while (rr <= 1.04f) { out += sampleAt(f, rr, ang); cnt++; rr += 0.5f / f.radius }
            if (peak >= out / cnt + 10f) ok++
        }
        rimOverHalo = ok / 360f
    }

    private fun ringMean(f: OrbFrame, r0: Float, r1: Float): Float {
        var s = 0f
        var c = 0
        for (i in 0 until f.w * f.h) {
            val x = (i % f.w + 0.5f - f.cx) / f.radius
            val y = (i / f.w + 0.5f - f.cy) / f.radius
            val r = sqrt(x * x + y * y)
            if (r >= r0 && r < r1) { s += f.luma(i); c++ }
        }
        return if (c == 0) 0f else s / c
    }

    private fun ringSample(f: OrbFrame, r: Float): Float {
        var s = 0f
        for (a in 0 until 360) s += sampleAt(f, r, Math.toRadians(a.toDouble()))
        return s / 360f
    }

    private fun sampleAt(f: OrbFrame, r: Float, ang: Double): Float {
        val x = (f.cx + r * f.radius * cos(ang)).toInt().coerceIn(0, f.w - 1)
        val y = (f.cy + r * f.radius * sin(ang)).toInt().coerceIn(0, f.h - 1)
        return f.luma(y * f.w + x)
    }

    private fun hue(r: Int, g: Int, b: Int): Float {
        val h = Math.toDegrees(atan2(sqrt(3.0) * (g - b), 2.0 * r - g - b)).toFloat()
        return if (h < 0f) h + 360f else h
    }

    fun csv(): String = listOf(
        darkFraction, p95, p99, nearWhite, saturated, biggestHue, huesOver10.toFloat(), sharp20, sharp40,
        gradP995, centerLuma, outerLuma, coreOffset, meanLuma, halo102, halo120, halo150, haloEdge, rimOverHalo
    ).joinToString(",") { "%.3f".format(java.util.Locale.ROOT, it) }

    companion object {
        const val HEADER = "dark,p95,p99,nearWhite,saturated,biggestHue,hues10,sharp20,sharp40,grad995,center,outer,coreOff,mean,halo102,halo120,halo150,haloEdge,rimOverHalo"
    }
}

internal fun meanAbsLumaDiff(a: OrbFrame, b: OrbFrame): Float {
    var s = 0.0
    for (i in 0 until a.w * a.h) s += abs(a.luma(i) - b.luma(i))
    return (s / (a.w * a.h)).toFloat()
}
