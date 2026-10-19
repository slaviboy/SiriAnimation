package com.slaviboy.sirianimation

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.min

/**
 * GPU orb renderer (API 33+). One AGSL fragment shader ([OrbShaderSource]) draws the folded
 * fan-sheets, core, glass and halo for every pixel of the orb's square.
 *
 * The shader is compiled once, in the constructor (a compile error throws
 * [IllegalArgumentException], which the engine turns into a fallback to [SiriOrbCpuRenderer]).
 * Each frame [OrbField.update] computes the animation on the CPU and only float uniforms change.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class SiriOrbShaderRenderer : SiriAnimationRenderer {

    private val shader = RuntimeShader(OrbShaderSource.AGSL)
    private val paint = Paint().apply { shader = this@SiriOrbShaderRenderer.shader }
    private val field = OrbField()
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var drawExtent = 1f

    override val needsIsolation: Boolean get() = false

    override fun configure(width: Int, height: Int, density: Float, palette: SiriPalette, config: SiriAnimationConfig) {
        cx = width / 2f
        cy = height / 2f
        radius = min(width, height) / 2f * config.orbSizeFraction
        if (radius <= 0f) return
        // orbSizeFraction <= 1, so the extent is >= 1; at 1 there is no room (and no halo).
        val extent = min(width, height) / 2f / radius
        field.setPalette(palette)
        field.setExtent(extent)
        // Only cover the pixels the halo can reach (at the largest glow radius), never beyond the view.
        drawExtent = (field.haloReach(MAX_GLOW_RADIUS, 1f / radius) + 2f / radius).coerceAtMost(extent).coerceAtLeast(1f)
        val s = shader
        s.setFloatUniform(OrbUniforms.CENTER, cx, cy)
        s.setFloatUniform(OrbUniforms.RADIUS, radius)
        s.setFloatUniform(OrbUniforms.EXTENT, extent)
        s.setFloatUniform(OrbUniforms.BODY, field.body)
        s.setFloatUniform(OrbUniforms.RIM_TOP, field.rimTop)
        s.setFloatUniform(OrbUniforms.RIM_SIDE, field.rimSide)
        s.setFloatUniform(OrbUniforms.RIM_BOTTOM, field.rimBottom)
        s.setFloatUniform(OrbUniforms.CORE_COL, field.coreCol)
        s.setFloatUniform(OrbUniforms.HALO_COL, field.haloCol)
        s.setFloatUniform(OrbUniforms.DEEP_COL, field.deepCol)
        s.setFloatUniform(OrbUniforms.GLOW_COL, field.glowCol)
    }

    override fun draw(canvas: Canvas, p: FrameParams) {
        if (radius <= 0f) return
        val f = field
        f.update(p)
        val s = shader
        s.setFloatUniform(OrbUniforms.BLADE_M, f.bm)
        s.setFloatUniform(OrbUniforms.BLADE_Z, f.bz)
        s.setFloatUniform(OrbUniforms.BLADE_S, f.bs)
        s.setFloatUniform(OrbUniforms.BLADE_W, f.bw)
        s.setFloatUniform(OrbUniforms.BLADE_P, f.bp)
        s.setFloatUniform(OrbUniforms.BLADE_A, f.ba)
        s.setFloatUniform(OrbUniforms.BLADE_B, f.bb)
        s.setFloatUniform(OrbUniforms.BLADE_T, f.bt)
        s.setFloatUniform(OrbUniforms.CORE, f.core)
        s.setFloatUniform(OrbUniforms.CORE_SHAPE, f.coreShape)
        s.setFloatUniform(OrbUniforms.LOOK, f.look)
        s.setFloatUniform(OrbUniforms.HALO, f.halo)
        // Re-assign so the Paint picks up the new uniform values.
        paint.shader = s
        val e = radius * drawExtent
        canvas.drawRect(cx - e, cy - e, cx + e, cy + e, paint)
    }

    override fun release() {
        // RuntimeShader holds no resources that need explicit release; keeping it avoids a recompile on re-attach.
    }

    private companion object {
        const val MAX_GLOW_RADIUS = 2f
    }
}
