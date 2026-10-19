package com.slaviboy.sirianimation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Keeps the AGSL shader and the Kotlin model ([OrbField]) in sync. */
class OrbShaderContractTest {

    private val body = OrbShaderSource.BODY

    @Test
    fun uniformSizesMatchTheKotlinArrays() {
        val field = OrbField()
        val arrays = mapOf(
            OrbUniforms.BODY to field.body, OrbUniforms.RIM_TOP to field.rimTop, OrbUniforms.RIM_SIDE to field.rimSide,
            OrbUniforms.RIM_BOTTOM to field.rimBottom, OrbUniforms.CORE_COL to field.coreCol,
            OrbUniforms.HALO_COL to field.haloCol, OrbUniforms.DEEP_COL to field.deepCol, OrbUniforms.GLOW_COL to field.glowCol,
            OrbUniforms.BLADE_M to field.bm, OrbUniforms.BLADE_Z to field.bz, OrbUniforms.BLADE_S to field.bs,
            OrbUniforms.BLADE_W to field.bw, OrbUniforms.BLADE_P to field.bp, OrbUniforms.BLADE_A to field.ba,
            OrbUniforms.BLADE_B to field.bb, OrbUniforms.BLADE_T to field.bt, OrbUniforms.CORE to field.core,
            OrbUniforms.CORE_SHAPE to field.coreShape, OrbUniforms.LOOK to field.look, OrbUniforms.HALO to field.halo
        )
        for (u in OrbUniforms.ALL) {
            val arr = arrays[u.name] ?: continue // scalar / float2 uniforms are set with explicit values
            assertEquals("float count of ${u.name}", u.total, arr.size)
        }
        assertEquals("every array uniform is mapped", arrays.size, OrbUniforms.ALL.count { it.total >= 3 })
    }

    @Test
    fun bodyUsesEveryUniformAndConstantButDeclaresNone() {
        for (u in OrbUniforms.ALL) assertTrue("${u.name} unused", Regex("\\b${u.name}\\b").containsMatchIn(body))
        for ((name, _) in OrbField.SHARED) assertTrue("$name unused", Regex("\\b$name\\b").containsMatchIn(body))
        assertFalse("uniforms are generated", Regex("^\\s*uniform\\b", RegexOption.MULTILINE).containsMatchIn(body))
        assertFalse("float uniforms only", body.contains("layout("))
        assertFalse("no time uniform: animation lives in OrbField", body.contains("uTime"))
    }

    @Test
    fun preludeIsLocaleIndependent() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("bg-BG")) // comma decimal separator
            val prelude = OrbShaderSource.prelude()
            for (line in prelude.lines().filter { it.startsWith("const float") }) {
                assertTrue("bad literal: $line", Regex("^const float [A-Z_0-9]+ = -?\\d+\\.\\d{6};$").matches(line))
            }
            assertTrue(prelude.contains("const int BLADES = ${OrbField.BLADES};"))
            assertTrue(prelude.contains("uniform float4 uBM[${OrbField.BLADES}];"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun stagesAppearInTheSameOrderInBothImplementations() {
        assertInOrder("AGSL", body, 1..14)
        // In Kotlin the per-blade stages live in blade(), the rest in shade().
        val kotlin = File("src/main/java/com/slaviboy/sirianimation/OrbField.kt").readText()
        assertInOrder("OrbField.blade", kotlin, 2..7)
        assertInOrder("OrbField.shade", kotlin, listOf(1) + (8..14))
    }

    private fun assertInOrder(name: String, src: String, stages: Iterable<Int>) {
        var last = -1
        for (i in stages) {
            val at = src.indexOf("[F$i]", if (i == 1) 0 else maxOf(last, 0))
            assertTrue("$name is missing [F$i] (or it is out of order)", at > last)
            last = at
        }
    }
}
