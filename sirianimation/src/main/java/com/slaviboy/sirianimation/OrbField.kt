package com.slaviboy.sirianimation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin model of the orb, shared by both orb renderers and the JVM preview tests.
 *
 * The interior is a few large "folded fan-sheets": smooth, flat fans of light (disc sectors)
 * lying in tilted planes through a wandering hot core. Each fan has
 * one crisp, bright fold edge and a soft trailing side. Seen through an analytic glass sphere,
 * the tilted planes foreshorten into curved petals and, near edge-on, into thin bright lines.
 *
 * - [update] turns [FrameParams] into per-frame values, packed exactly like the AGSL uniforms of
 *   [OrbShaderSource]. The GPU renderer uploads them verbatim and the CPU renderer shades with
 *   them, so both paths animate identically. All time dependence lives here (double precision,
 *   phases wrapped); the shader has no time uniform.
 * - [shade] is the per-pixel mirror of the AGSL `main`. Stages are tagged `[F1]`..`[F14]` in both.
 *
 * Not thread-safe. Allocation-free after construction.
 */
internal class OrbField(private val fastMath: Boolean = true) {

    // ---- Static values (configure time). Colors are linear (sRGB squared) unless noted. ----
    private val pal = FloatArray(MAX_COLORS * 3)
    private var palCount = 3
    val body = FloatArray(3)
    val rimTop = FloatArray(3)
    val rimSide = FloatArray(3)
    val rimBottom = FloatArray(3)
    val coreCol = FloatArray(3)
    val haloCol = FloatArray(3)
    val deepCol = FloatArray(3)
    /** Outer glow color, sRGB (the halo is composited over the host background, not tone mapped). */
    val glowCol = FloatArray(3)
    var extent = 1.6f
        private set

    // ---- Per-frame values, packed like the AGSL uniform arrays (float4[BLADES] each). ----
    /** Screen-to-plane inverse 2x2: a = x*qx + y*qy, b = z*qx + w*qy. */
    val bm = FloatArray(BLADES * 4)
    /** e1.z, e2.z, 1 / n.z, gain. */
    val bz = FloatArray(BLADES * 4)
    /** Signed angular width W, twist, curl, radius R. */
    val bs = FloatArray(BLADES * 4)
    /** Bend amplitude, bend frequency, bend phase, unused. */
    val bw = FloatArray(BLADES * 4)
    /** Apex x, y, z, tail start. */
    val bp = FloatArray(BLADES * 4)
    /** Fold-side color rgb (linear), unused. */
    val ba = FloatArray(BLADES * 4)
    /** Trailing-side color rgb (linear), edge softness. */
    val bb = FloatArray(BLADES * 4)
    /** Screen direction of the blade tip x, y, rim light from it, unused. */
    val bt = FloatArray(BLADES * 4)
    /** Core x, y, z, hot strength. */
    val core = FloatArray(4)
    /** Hot falloff, halo falloff, halo strength, exposure. */
    val coreShape = FloatArray(4)
    /** Rim level, fresnel strength, body level, unused. */
    val look = FloatArray(4)
    /** Glow strength, glow radius, unused, unused. */
    val halo = FloatArray(4)

    // ---- Per-pixel scratch and output. ----
    private val kZ = FloatArray(BLADES)
    private val kA = FloatArray(BLADES)
    private val kR = FloatArray(BLADES)
    private val kG = FloatArray(BLADES)
    private val kB = FloatArray(BLADES)
    private var tR = 0f
    private var tG = 0f
    private var tB = 0f

    /** Premultiplied sRGB output of [shade], `0..1`. */
    var outR = 0f
        private set
    var outG = 0f
        private set
    var outB = 0f
        private set
    var outA = 0f
        private set

    fun setPalette(p: SiriPalette) {
        val c = p.colors
        palCount = min(c.size, MAX_COLORS)
        for (i in 0 until palCount) putLinear(pal, i * 3, c[i])
        putLinear(body, 0, p.bodyColor)
        putLinear(coreCol, 0, p.coreColor)
        putSrgb(glowCol, 0, p.glowColor)
        val c0 = c[0]
        val c1 = c[1 % c.size]
        val c2 = c[2 % c.size]
        val c3 = c[3 % c.size]
        putLinear(rimTop, 0, mixArgb(p.rimColor, c0, 0.35f))
        putLinear(rimSide, 0, mixArgb(p.rimColor, c2, 0.45f))
        putLinear(rimBottom, 0, mixArgb(p.rimColor, mixArgb(c3, c1, 0.45f), 0.55f))
        putLinear(deepCol, 0, mixArgb(c2, c3, 0.5f))
        // Colored halo around the hot core: white leaning toward the palette average.
        var ar = 0f
        var ag = 0f
        var ab = 0f
        for (i in 0 until palCount) {
            ar += pal[i * 3]; ag += pal[i * 3 + 1]; ab += pal[i * 3 + 2]
        }
        val inv = 1f / palCount
        haloCol[0] = coreCol[0] * 0.45f + ar * inv * 0.55f
        haloCol[1] = coreCol[1] * 0.45f + ag * inv * 0.55f
        haloCol[2] = coreCol[2] * 0.45f + ab * inv * 0.55f
    }

    /** Copies all static and per-frame values from [other] (no allocation), so this field shades the same frame. */
    fun copyFrom(other: OrbField) {
        other.pal.copyInto(pal)
        palCount = other.palCount
        extent = other.extent
        other.body.copyInto(body); other.rimTop.copyInto(rimTop); other.rimSide.copyInto(rimSide)
        other.rimBottom.copyInto(rimBottom); other.coreCol.copyInto(coreCol); other.haloCol.copyInto(haloCol)
        other.deepCol.copyInto(deepCol); other.glowCol.copyInto(glowCol)
        other.bm.copyInto(bm); other.bz.copyInto(bz); other.bs.copyInto(bs); other.bw.copyInto(bw)
        other.bp.copyInto(bp); other.ba.copyInto(ba); other.bb.copyInto(bb); other.bt.copyInto(bt)
        other.core.copyInto(core); other.coreShape.copyInto(coreShape); other.look.copyInto(look)
        other.halo.copyInto(halo)
    }

    /** Half size of the drawing area relative to the orb radius (the halo must end inside it). */
    fun setExtent(extent: Float) {
        this.extent = extent
    }

    /** Per-frame animation. Energy, swirl, pulse and audio only scale amplitudes, never `t`. */
    fun update(p: FrameParams) {
        val t = p.timeD + T0
        val e = p.energy.coerceIn(0f, 1.6f).toDouble()
        val audio = p.audio.toDouble()
        val pulse = p.pulse.toDouble()
        val swirl = p.swirl.toDouble()
        val wob = 0.8 + 0.2 * e

        val ccx = CORE_X0 + (0.10 * sin(0.37 * t) + 0.05 * sin(0.83 * t + 1.0)) * wob
        val ccy = CORE_Y0 + (0.08 * sin(0.29 * t + 1.0) + 0.04 * sin(0.71 * t + 2.0)) * wob
        val ccz = 0.18 + 0.08 * sin(0.23 * t + 2.0)
        core[0] = ccx.toFloat()
        core[1] = ccy.toFloat()
        core[2] = ccz.toFloat()
        core[3] = (CORE_HOT * p.core * (0.85 + 0.3 * pulse + 0.4 * audio)).toFloat()
        coreShape[0] = (CORE_HOT_K / (1.0 + 0.6 * pulse + 0.8 * audio)).toFloat()
        coreShape[1] = (CORE_HALO_K / (1.0 + 0.4 * pulse + 0.6 * audio)).toFloat()
        coreShape[2] = CORE_HALO * p.core
        coreShape[3] = EXPOSURE * p.brightness

        for (k in 0 until BLADES) {
            val o = 4 * k
            val kd = k.toDouble()
            // Orientation R = Rz(axis) * Rx(tilt) * Rz(beta): orthonormal and continuous, n.z = cos(tilt) > 0.
            val axis = AX0[k] + AX_D[k] * t + 0.9 * wob * sin(AX_F[k] * t + 1.9 * kd)
            val tilt = (TILT_MID[k] + TILT_AMP[k] * wob * sin(TILT_F[k] * t + 2.3 * kd) +
                0.1 * sin(TILT_F[k] * 1.618 * t + kd)).coerceIn(TILT_MIN, TILT_MAX)
            val beta = BETA0[k] + SPIN[k] * t + 0.7 * wob * sin(BETA_F[k] * t + 0.7 * kd)
            val ca = cos(axis)
            val sa = sin(axis)
            val ct = cos(tilt)
            val st = sin(tilt)
            val cb = cos(beta)
            val sb = sin(beta)
            val e1x = cb * ca - sb * ct * sa
            val e1y = cb * sa + sb * ct * ca
            val e1z = sb * st
            val e2x = -sb * ca - cb * ct * sa
            val e2y = -sb * sa + cb * ct * ca
            val e2z = cb * st
            val nz = ct
            bm[o] = (e2y / nz).toFloat()
            bm[o + 1] = (-e2x / nz).toFloat()
            bm[o + 2] = (-e1y / nz).toFloat()
            bm[o + 3] = (e1x / nz).toFloat()
            bz[o] = e1z.toFloat()
            bz[o + 1] = e2z.toFloat()
            bz[o + 2] = (1.0 / nz).toFloat()
            val tiltGain = min(1.0 + TILT_GAIN * (1.0 / nz - 1.0), GAIN_MAX)
            bz[o + 3] = (WEIGHT[k] * tiltGain * (0.9 + 0.1 * sin(0.41 * t + kd)) * (1.0 + 0.25 * audio)).toFloat()

            val w = (abs(W0[k]) + 0.25 * sin(0.27 * t + 1.3 * kd) + 0.3 * e).coerceIn(0.5, 4.4)
            val twist = TW0[k] * (1.0 + 0.25 * sin(0.19 * t + kd)) + sign(TW0[k]) * 0.6 * swirl
            val curl = CURL0[k] * (1.0 + 0.6 * swirl)
            val radius = R0[k] * (1.0 + 0.06 * sin(0.33 * t + 2.0 * kd) + 0.08 * audio + 0.06 * pulse + 0.04 * e)
            bs[o] = (sign(W0[k]) * w).toFloat()
            bs[o + 1] = twist.toFloat()
            bs[o + 2] = curl.toFloat()
            bs[o + 3] = radius.toFloat()
            bw[o] = (0.05 + 0.05 * e + 0.04 * audio).toFloat()
            bw[o + 1] = (3.5 + 0.5 * kd).toFloat()
            bw[o + 2] = wrap(0.8 * t + 1.9 * kd)
            bw[o + 3] = 0f

            // Each fan's apex sits a little off the core, so the structure never pivots on one pin.
            val oa = OFF0[k] + (if (k % 2 == 0) 0.13 else -0.11) * t
            val ax = ccx + OFF_R[k] * cos(oa)
            val ay = ccy + OFF_R[k] * sin(oa)
            bp[o] = ax.toFloat()
            bp[o + 1] = ay.toFloat()
            bp[o + 2] = (ccz + 0.5 * OFF_R[k] * sin(0.7 * oa + kd)).toFloat()
            bp[o + 3] = TAIL[k]

            val f = START[k] + t / COLOR_PERIOD
            palLerp(f, ba, o)
            palLerp(f + 1.0, bb, o)
            // The trailing side only leans toward the next hue, so fans stay saturated, not muddy.
            for (ch in 0 until 3) bb[o + ch] = ba[o + ch] + (bb[o + ch] - ba[o + ch]) * TAIL_HUE
            ba[o + 3] = 0f
            bb[o + 3] = SOFT[k]

            // Where the fan's middle reaches toward the rim; lights the glass there.
            val rt = 0.75 * radius
            val phm = 0.5 * bs[o] + twist * rt + curl * rt * rt
            val la = rt * cos(phm)
            val lb = rt * sin(phm)
            val tx = ax + la * e1x + lb * e2x
            val ty = ay + la * e1y + lb * e2y
            val len = sqrt(tx * tx + ty * ty)
            if (len > 1e-4) {
                bt[o] = (tx / len).toFloat()
                bt[o + 1] = (ty / len).toFloat()
            } else {
                bt[o] = 0f
                bt[o + 1] = -1f
            }
            bt[o + 2] = (TIP_GAIN * WEIGHT[k] * smooth(0.2, 0.55, len)).toFloat()
            bt[o + 3] = 0f
        }

        look[0] = RIM_LEVEL * (0.8f + 0.2f * p.brightness)
        look[1] = FRESNEL
        look[2] = GLASS_BODY
        look[3] = 0f
        halo[0] = p.glow * p.glowEnabled
        halo[1] = p.glowRadius
        halo[2] = 0f
        halo[3] = 0f
    }

    /**
     * Shades one point. ([x], [y]) are orb-normalized (radius 1, +y down); [fpx] is the size of one
     * output pixel in the same units. With [shell] false only the interior is produced (no rim
     * line, silhouette or halo) for the CPU renderer, which draws those at full resolution.
     * [dither] in `0..1` adds the shader's ±0.5 LSB dither; pass 0.5 for none.
     */
    fun shade(x: Float, y: Float, fpx: Float, shell: Boolean, dither: Float = 0.5f) {
        // [F1] Disc coordinates and a weak glass lens.
        val r2 = x * x + y * y
        val r = sqrt(r2)
        val dirX: Float
        val dirY: Float
        if (r > 0.0001f) {
            dirX = x / r
            dirY = y / r
        } else {
            dirX = 0f
            dirY = -1f
        }
        var cR = 0f
        var cG = 0f
        var cB = 0f
        var alpha = 0f
        if (r < 1f) {
            val lens = 1f - LENS * r2
            val px = x * lens
            val py = y * lens
            val rin2 = px * px + py * py
            for (k in 0 until BLADES) blade(k, px, py, rin2, fpx)

            // [F8] Soft O(N^2) occlusion: blades in front hide part of the ones behind.
            var eR = 0f
            var eG = 0f
            var eB = 0f
            var trans = 1f
            for (i in 0 until BLADES) {
                // A blade without light neither shows nor hides anything (kA == 0 exactly then).
                if (kA[i] == 0f) continue
                var tr = 1f
                for (j in 0 until BLADES) {
                    if (j != i && kA[j] != 0f) tr *= 1f - OCCLUSION * kA[j] * ss(-OCC_SOFT, OCC_SOFT, kZ[j] - kZ[i])
                }
                eR += kR[i] * tr
                eG += kG[i] * tr
                eB += kB[i] * tr
                trans *= 1f - kA[i]
            }

            // [F9] Core: white-hot point plus a colored halo.
            val qx = px - core[0]
            val qy = py - core[1]
            val d2 = qx * qx + qy * qy
            val hot = core[3] * ex(-d2 * coreShape[0])
            val hl = coreShape[2] * ex(-d2 * coreShape[1])
            eR += coreCol[0] * hot + haloCol[0] * hl
            eG += coreCol[1] * hot + haloCol[1] * hl
            eB += coreCol[2] * hot + haloCol[2] * hl

            // [F10] Hue-preserving tone map; only very hot regions bleach to white.
            val exposure = coreShape[3]
            toneMap(eR * exposure, eG * exposure, eB * exposure)

            // [F11] Glass body (hidden behind dense blades) and a faint inner fresnel.
            val zf = sqrt(max(1f - r2, 0f))
            val et = 1f - zf
            val fres = et * et * et * look[1]
            rimTint(dirX, dirY)
            val bodyK = look[2] * (0.6f + 0.4f * zf) * (1f - BODY_HIDE * (1f - trans))
            cR = body[0] * bodyK + tR + rtR * fres
            cG = body[1] * bodyK + tG + rtG * fres
            cB = body[2] * bodyK + tB + rtB * fres

            if (shell) {
                // [F12] Thin rim line, lit from the top-left and where blades reach the glass.
                val w = max(RIM_W, fpx)
                val d = (r - 1f + 1.3f * w) / w
                val ring = ex(-d * d)
                var tip = 0f
                for (k in 0 until BLADES) {
                    val o = 4 * k
                    tip += bt[o + 2] * ex((dirX * bt[o] + dirY * bt[o + 1] - 1f) / RIM_SPREAD)
                }
                val lit = max(-0.55f * dirX - 0.835f * dirY, 0f)
                val rl = ring * look[0] * (0.45f + 0.3f * lit + tip)
                cR += rtR * rl
                cG += rtG * rl
                cB += rtB * rl
                // [F13] Faint specular crescent near the top-left of the glass.
                val cd = (1f - r - 0.04f) / 0.025f
                val sp = SPEC * ex(-cd * cd) * lit * lit * lit
                cR += sp
                cG += sp
                cB += sp
            }
            cR = sqrt(max(cR, 0f)).coerceAtMost(1f)
            cG = sqrt(max(cG, 0f)).coerceAtMost(1f)
            cB = sqrt(max(cB, 0f)).coerceAtMost(1f)

            // [F14] Anti-aliased silhouette.
            alpha = if (shell) 1f - ss(1f - 1.5f * fpx, 1f, r) else 1f
            cR *= alpha
            cG *= alpha
            cB *= alpha
        }
        // [F14] Outer halo behind the orb, gone before the edge of the drawing area.
        if (shell && halo[0] > 0f && r > 1f - 2f * fpx) {
            val ga = (halo[0] * haloProfile(r, halo[1], fpx)).coerceIn(0f, HALO_MAX)
            val k = ga * (1f - alpha)
            cR += glowCol[0] * k
            cG += glowCol[1] * k
            cB += glowCol[2] * k
            alpha += k
        }
        val dn = (dither - 0.5f) / 255f
        outR = (cR + dn).coerceIn(0f, alpha)
        outG = (cG + dn).coerceIn(0f, alpha)
        outB = (cB + dn).coerceIn(0f, alpha)
        outA = alpha
    }

    /** Halo alpha at radius [r] for glow strength 1 (before the [HALO_MAX] clamp). */
    fun haloProfile(r: Float, glowRadius: Float, fpx: Float): Float {
        val rmax = haloReach(glowRadius, fpx)
        // No room between the orb and the edge of the drawing area: no halo.
        if (rmax <= 1f + 2f * fpx) return 0f
        val d = max(r - 1f, 0f)
        val fade = 1f - ss(1f + 0.5f * (rmax - 1f), rmax, r)
        return HALO_PEAK * ex(-d / (HALO_LEN * glowRadius)) * fade
    }

    /** Outer radius (orb radii) beyond which [haloProfile] is zero; it stays 2 px inside the drawing area. */
    fun haloReach(glowRadius: Float, fpx: Float): Float = min(1f + HALO_REACH * glowRadius, extent - 2f * fpx)

    // Rim tint scratch (linear).
    private var rtR = 0f
    private var rtG = 0f
    private var rtB = 0f

    /** Rim color by screen direction (+y down): top, sides, bottom. Linear; writes rtR/G/B. */
    fun rimTint(dx: Float, dy: Float) {
        val wt = max(-dy, 0f)
        val wb = max(dy, 0f)
        val ws = 0.8f * abs(dx)
        val inv = 1f / (wt + wb + ws + 0.0001f)
        rtR = (rimTop[0] * wt + rimBottom[0] * wb + rimSide[0] * ws) * inv
        rtG = (rimTop[1] * wt + rimBottom[1] * wb + rimSide[1] * ws) * inv
        rtB = (rimTop[2] * wt + rimBottom[2] * wb + rimSide[2] * ws) * inv
    }

    /** Rim tint as sRGB ARGB at the given direction (for the CPU renderer's gradients). */
    fun rimTintArgb(dx: Float, dy: Float, level: Float): Int {
        rimTint(dx, dy)
        return packOpaque(sqrt(rtR * level), sqrt(rtG * level), sqrt(rtB * level))
    }

    /** Fills [pixels] (n x n, opaque ARGB) with the interior over `[-margin, margin]^2` (CPU renderer). */
    fun renderInterior(pixels: IntArray, n: Int, margin: Float) = renderRows(pixels, n, margin, 0, n)

    /** Rows [rowStart, rowEnd) of [renderInterior]; lets several threads (each with its own field) share a frame. */
    fun renderRows(pixels: IntArray, n: Int, margin: Float, rowStart: Int, rowEnd: Int) {
        val inv = 2f * margin / n
        // Footprint of one output pixel is one texel here: edges adapt to it.
        val fp = inv
        for (j in rowStart until rowEnd) {
            val y = (j + 0.5f) * inv - margin
            val row = j * n
            for (i in 0 until n) {
                var px = (i + 0.5f) * inv - margin
                var py = y
                val r2 = px * px + py * py
                if (r2 >= OUTSIDE_R2) {
                    pixels[row + i] = 0
                    continue
                }
                // Texels on or past the edge reuse the edge value so bilinear filtering never pulls in black.
                if (r2 > EDGE_R2) {
                    val k = sqrt(EDGE_R2 / r2)
                    px *= k
                    py *= k
                }
                shade(px, py, fp, shell = false)
                pixels[row + i] = packOpaque(outR, outG, outB)
            }
        }
    }

    // [F2]..[F7] One fan blade at the lensed interior point (px, py).
    private fun blade(k: Int, px: Float, py: Float, rin2: Float, fpx: Float) {
        val o = 4 * k
        // [F2] Ray/plane hit in the plane's own coordinates (inverse 2x2 precomputed in update()).
        val qx = px - bp[o]
        val qy = py - bp[o + 1]
        val a = bm[o] * qx + bm[o + 1] * qy
        val b = bm[o + 2] * qx + bm[o + 3] * qy
        val zh = bp[o + 2] + a * bz[o] + b * bz[o + 1]
        kZ[k] = zh
        kA[k] = 0f
        kR[k] = 0f
        kG[k] = 0f
        kB[k] = 0f
        // [F3] Polar coordinates in the plane.
        val rho = sqrt(a * a + b * b)
        val radius = bs[o + 3]
        if (rho >= radius) return
        // Cheap early out (CPU only, same result): the hit lies outside the sphere, see [F7].
        val hr2 = rin2 + zh * zh
        if (hr2 >= INSIDE_HI * INSIDE_HI) return
        var phi = at2(b, if (rho > 0.00001f) a else 1f)
        // [F4] Curved arms: twist, curl and a travelling bend.
        phi -= bs[o + 1] * rho + bs[o + 2] * rho * rho + bw[o] * sn(bw[o + 1] * rho + bw[o + 2])
        // [F5] Sector profile: x = 0 on the crisp fold edge, 1 on the soft trailing edge.
        val w = bs[o]
        val aW = abs(w)
        var u = (phi - 0.5f * w) / TAU
        u -= floor(u + 0.5f)
        val xx = 0.5f + u * TAU / w
        val xp = max(xx, 0f)
        val tw = abs(bs[o + 1]) + 2f * abs(bs[o + 2]) * rho + abs(bw[o] * bw[o + 1])
        val grad = sqrt(1f / max(rho * rho, 0.000001f) + tw * tw)
        val back = ss(BACK_LO, BACK_HI, core[2] - zh)
        val fp = bz[o + 2] * fpx
        val sig = (bb[o + 3] * (1f + DOF * back) + 0.75f * fp) * grad / aW
        val edge = ss(-sig, sig, xx)
        val tail = 1f - ss(bp[o + 3], 1f + sig, xx)
        val fold = 1f / sqrt(1f + xp / FOLD_LAMBDA)
        var shape = edge * tail * (BLADE_LEVEL + FOLD_LEVEL * fold)
        val cbl = ss(0f, CORE_BLEND, rho)
        shape = CORE_FILL + (shape - CORE_FILL) * cbl
        val xc = min(xp, 1f)
        val rk = radius * (1f - PETAL * xc * xc)
        val radial = (1f - ss(rk * (1f - RAD_SOFT), rk, rho)) * (FILL_IN + (1f - FILL_IN) * ex(-RAD_DECAY * rho))
        // [F6] Gain (weight, tilt foreshortening, audio) is precomputed: bz[o + 3].
        // [F7] Fade where the plane leaves the sphere; parts behind the core are dimmer, bluer, softer.
        val hr = sqrt(hr2)
        val inside = 1f - ss(INSIDE_LO, INSIDE_HI, hr)
        val intensity = shape * radial * inside * bz[o + 3] * (1f - DEPTH_DIM * back)
        if (intensity <= 0f) return
        val mixAB = (0.6f * xp + 0.5f * rho / radius).coerceIn(0f, 1f)
        val dt = DEPTH_TINT * back
        var cr = ba[o] + (bb[o] - ba[o]) * mixAB
        var cg = ba[o + 1] + (bb[o + 1] - ba[o + 1]) * mixAB
        var cb = ba[o + 2] + (bb[o + 2] - ba[o + 2]) * mixAB
        cr += (deepCol[0] - cr) * dt
        cg += (deepCol[1] - cg) * dt
        cb += (deepCol[2] - cb) * dt
        // Fans run white-hot where they converge on the core.
        val wh = WHITEN * ex(-rho / WHITEN_R)
        cr += (coreCol[0] - cr) * wh
        cg += (coreCol[1] - cg) * wh
        cb += (coreCol[2] - cb) * wh
        kR[k] = cr * intensity
        kG[k] = cg * intensity
        kB[k] = cb * intensity
        kA[k] = 1f - ex(-OPACITY_K * intensity)
    }

    private fun toneMap(r: Float, g: Float, b: Float) {
        val m = max(max(r, g), b)
        val tm = 1f - ex(-m)
        val k = tm / max(m, 0.0001f)
        val bleach = 1f - ex(-max(m - WHITE_POINT, 0f) * BLEACH)
        tR = r * k + (tm - r * k) * bleach
        tG = g * k + (tm - g * k) * bleach
        tB = b * k + (tm - b * k) * bleach
    }

    /** Smoothly blends between palette entries; each blade drifts one entry per [COLOR_PERIOD] seconds. */
    private fun palLerp(f: Double, dst: FloatArray, o: Int) {
        val n = palCount
        var g = f % n
        if (g < 0) g += n
        val i0 = g.toInt() % n
        val i1 = (i0 + 1) % n
        // Plateau easing: colors stay pure most of the time and cross-fade in between.
        val k = smooth(0.4, 0.6, g - floor(g)).toFloat()
        for (ch in 0 until 3) dst[o + ch] = pal[i0 * 3 + ch] + (pal[i1 * 3 + ch] - pal[i0 * 3 + ch]) * k
    }

    private fun ss(e0: Float, e1: Float, x: Float): Float = OrbMath.smoothstep(e0, e1, x)
    private fun ex(x: Float): Float = if (fastMath) OrbMath.exp(x) else kotlin.math.exp(x)
    private fun sn(x: Float): Float = if (fastMath) OrbMath.sin(x) else kotlin.math.sin(x)
    private fun at2(y: Float, x: Float): Float = if (fastMath) OrbMath.atan2(y, x) else kotlin.math.atan2(y, x)

    companion object {
        const val BLADES = 5
        const val MAX_COLORS = 8

        // ---- Shading constants shared with the AGSL (emitted into its prelude by name). ----
        const val TAU = 6.2831855f
        const val LENS = 0.08f
        const val BLADE_LEVEL = 0.75f
        const val FOLD_LEVEL = 0.7f
        const val FOLD_LAMBDA = 0.03f
        const val CORE_BLEND = 0.07f
        const val CORE_FILL = 0.3f
        const val PETAL = 0.25f
        const val RAD_SOFT = 0.25f
        const val FILL_IN = 0.6f
        const val RAD_DECAY = 1.0f
        const val DOF = 2f
        const val BACK_LO = -0.05f
        const val BACK_HI = 0.4f
        const val DEPTH_DIM = 0.4f
        const val DEPTH_TINT = 0.3f
        const val WHITEN = 0.6f
        const val WHITEN_R = 0.08f
        const val INSIDE_LO = 0.82f
        const val INSIDE_HI = 0.98f
        const val OCCLUSION = 0.45f
        const val OCC_SOFT = 0.03f
        const val OPACITY_K = 1.6f
        const val WHITE_POINT = 2.2f
        const val BLEACH = 0.6f
        const val BODY_HIDE = 0.85f
        const val RIM_W = 0.012f
        const val RIM_SPREAD = 0.06f
        const val SPEC = 0.004f
        const val HALO_PEAK = 0.17f
        const val HALO_LEN = 0.18f
        const val HALO_REACH = 0.9f
        const val HALO_MAX = 0.6f

        /** Constants emitted into the AGSL prelude; names match the Kotlin constants. */
        @JvmField
        val SHARED: Array<Pair<String, Float>> = arrayOf(
            "TAU" to TAU, "LENS" to LENS, "BLADE_LEVEL" to BLADE_LEVEL, "FOLD_LEVEL" to FOLD_LEVEL,
            "FOLD_LAMBDA" to FOLD_LAMBDA, "CORE_BLEND" to CORE_BLEND, "CORE_FILL" to CORE_FILL, "PETAL" to PETAL,
            "RAD_SOFT" to RAD_SOFT, "FILL_IN" to FILL_IN, "RAD_DECAY" to RAD_DECAY,
            "DOF" to DOF, "BACK_LO" to BACK_LO, "BACK_HI" to BACK_HI, "DEPTH_DIM" to DEPTH_DIM,
            "DEPTH_TINT" to DEPTH_TINT, "WHITEN" to WHITEN, "WHITEN_R" to WHITEN_R, "INSIDE_LO" to INSIDE_LO, "INSIDE_HI" to INSIDE_HI, "OCCLUSION" to OCCLUSION,
            "OCC_SOFT" to OCC_SOFT, "OPACITY_K" to OPACITY_K, "WHITE_POINT" to WHITE_POINT, "BLEACH" to BLEACH,
            "BODY_HIDE" to BODY_HIDE, "RIM_W" to RIM_W, "RIM_SPREAD" to RIM_SPREAD, "SPEC" to SPEC,
            "HALO_PEAK" to HALO_PEAK, "HALO_LEN" to HALO_LEN, "HALO_REACH" to HALO_REACH, "HALO_MAX" to HALO_MAX
        )

        // ---- Animation constants (Kotlin only). ----
        /** Offset added to the engine clock: the composition at engine time 0 (and the static frame). */
        const val T0 = 7.0
        private const val CORE_X0 = -0.03
        private const val CORE_Y0 = -0.04
        private const val CORE_HOT = 1.6
        private const val CORE_HOT_K = 140.0
        private const val CORE_HALO_K = 14.0
        private const val CORE_HALO = 0.32f
        private const val EXPOSURE = 1.9f
        private const val TILT_MIN = 0.12
        private const val TILT_MAX = 1.45
        private const val TILT_GAIN = 0.55
        private const val GAIN_MAX = 3.5
        private const val COLOR_PERIOD = 14.0
        private const val TAIL_HUE = 0.4f
        private const val TIP_GAIN = 0.9
        const val RIM_LEVEL = 0.4f
        private const val FRESNEL = 0.18f
        private const val GLASS_BODY = 1.3f
        private const val EDGE_R2 = 0.985f
        private const val OUTSIDE_R2 = 1.2f

        // Per blade: 3 major and 2 minor fans, deliberately unequal so no symmetry shows.
        private val WEIGHT = doubleArrayOf(1.0, 0.8, 0.6, 0.38, 0.26)
        private val W0 = doubleArrayOf(1.9, -1.5, 3.6, -1.1, 0.9)
        private val TAIL = floatArrayOf(0.45f, 0.5f, 0.35f, 0.55f, 0.5f)
        private val R0 = doubleArrayOf(0.98, 0.92, 1.0, 0.85, 0.75)
        private val TW0 = doubleArrayOf(0.15, -0.35, 0.08, 0.6, -0.3)
        private val CURL0 = doubleArrayOf(0.12, -0.25, 0.05, -0.5, 0.45)
        private val TILT_MID = doubleArrayOf(0.85, 0.75, 1.05, 0.9, 0.7)
        private val TILT_AMP = doubleArrayOf(0.5, 0.45, 0.3, 0.5, 0.45)
        private val TILT_F = doubleArrayOf(0.53, 0.41, 0.29, 0.67, 0.47)
        private val AX0 = doubleArrayOf(0.3, 2.2, 4.1, 5.3, 1.2)
        private val AX_D = doubleArrayOf(0.05, -0.04, 0.03, -0.06, 0.07)
        private val AX_F = doubleArrayOf(0.31, 0.43, 0.23, 0.37, 0.59)
        private val BETA0 = doubleArrayOf(-1.2, 0.4, 2.6, 1.5, -2.4)
        private val SPIN = doubleArrayOf(0.11, -0.09, 0.06, -0.13, 0.15)
        private val BETA_F = doubleArrayOf(0.37, 0.29, 0.51, 0.44, 0.33)
        private val OFF_R = doubleArrayOf(0.05, 0.08, 0.04, 0.1, 0.07)
        private val OFF0 = doubleArrayOf(0.0, 2.1, 4.0, 1.0, 5.2)
        // Fractional offsets stagger the palette cross-fades so blades never all blend at once.
        private val START = doubleArrayOf(4.55, 0.2, 2.35, 3.65, 1.4)
        private val SOFT = floatArrayOf(0.004f, 0.006f, 0.008f, 0.005f, 0.006f)

        private const val INV255 = 1f / 255f

        private fun wrap(x: Double): Float {
            val tau = 2.0 * Math.PI
            val r = x % tau
            return (if (r < 0) r + tau else r).toFloat()
        }

        private fun smooth(e0: Double, e1: Double, x: Double): Double {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        /** Writes an ARGB color as linear-ish floats (sRGB squared). */
        fun putLinear(dst: FloatArray, o: Int, argb: Int) {
            val r = ((argb ushr 16) and 0xFF) * INV255
            val g = ((argb ushr 8) and 0xFF) * INV255
            val b = (argb and 0xFF) * INV255
            dst[o] = r * r
            dst[o + 1] = g * g
            dst[o + 2] = b * b
        }

        fun putSrgb(dst: FloatArray, o: Int, argb: Int) {
            dst[o] = ((argb ushr 16) and 0xFF) * INV255
            dst[o + 1] = ((argb ushr 8) and 0xFF) * INV255
            dst[o + 2] = (argb and 0xFF) * INV255
        }

        fun mixArgb(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = ((((a ushr s) and 0xFF) * (1f - t) + ((b ushr s) and 0xFF) * t) + 0.5f).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun packOpaque(r: Float, g: Float, b: Float): Int =
            (0xFF shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)

        private fun q(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
}
