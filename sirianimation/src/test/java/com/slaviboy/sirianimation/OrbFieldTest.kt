package com.slaviboy.sirianimation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class OrbFieldTest {

    private val config = SiriAnimationConfig.DEFAULT

    @Test
    fun bladeBasisStaysOrthonormalAndFacesTheViewer() {
        val field = OrbRaster.newField()
        val p = FrameParams()
        for (state in SiriAnimationState.entries) {
            var t = 0.0
            while (t < 600.0) {
                p.fillSettled(state, config, t, audioLevel = if (state == SiriAnimationState.SPEAKING) 1f else 0f)
                field.update(p)
                for (k in 0 until OrbField.BLADES) {
                    val o = 4 * k
                    val nz = 1f / field.bz[o + 2]
                    // Reconstruct the plane basis from the uploaded inverse matrix.
                    val e1x = field.bm[o + 3] * nz
                    val e1y = -field.bm[o + 2] * nz
                    val e2x = -field.bm[o + 1] * nz
                    val e2y = field.bm[o] * nz
                    val e1z = field.bz[o]
                    val e2z = field.bz[o + 1]
                    val l1 = sqrt(e1x * e1x + e1y * e1y + e1z * e1z)
                    val l2 = sqrt(e2x * e2x + e2y * e2y + e2z * e2z)
                    val dot = e1x * e2x + e1y * e2y + e1z * e2z
                    assertEquals("|e1| t=$t k=$k", 1f, l1, 1e-3f)
                    assertEquals("|e2| t=$t k=$k", 1f, l2, 1e-3f)
                    assertEquals("e1.e2 t=$t k=$k", 0f, dot, 1e-3f)
                    assertTrue("n.z=$nz too small (edge-on) t=$t k=$k", nz >= cos(1.45f) - 1e-4f)
                    assertTrue("non-finite uniforms", field.bm.all { it.isFinite() } && field.bs.all { it.isFinite() })
                }
                t += 0.37
            }
        }
    }

    @Test
    fun uniformsChangeContinuouslyEvenWhileTheStateChanges() {
        val field = OrbRaster.newField()
        val p = FrameParams()
        val prev = HashMap<String, FloatArray>()
        val dt = 1.0 / 120.0
        var t = 0.0
        var step = 0
        while (t < 120.0) {
            // Flip between calm and energetic every 2 s with a fast ease, plus audio bursts.
            val phase = (t / 2.0).toInt() % 2
            val ease = ((t % 2.0) / 0.3).coerceAtMost(1.0).toFloat()
            val a = if (phase == 0) SiriAnimationState.IDLE else SiriAnimationState.SPEAKING
            val b = if (phase == 0) SiriAnimationState.SPEAKING else SiriAnimationState.IDLE
            // Speech-like audio that fades in with SPEAKING and out with IDLE (the engine smooths it too).
            val envelope = if (phase == 1) ease else 1f - ease
            p.fill(
                0f, t, config.preset, lerp(b.amplitude, a.amplitude, ease), lerp(b.energy, a.energy, ease),
                lerp(b.pulse, a.pulse, ease), lerp(b.swirl, a.swirl, ease), 0.8f, 1f, 1f, 1f, 1f,
                (0.5f + 0.5f * sin(t * 9.0).toFloat()) * envelope, 1f
            )
            field.update(p)
            val arrays = mapOf(
                "bm" to field.bm, "bz" to field.bz, "bs" to field.bs, "bw" to field.bw, "bp" to field.bp,
                "ba" to field.ba, "bb" to field.bb, "bt" to field.bt, "core" to field.core, "coreShape" to field.coreShape
            )
            for ((name, arr) in arrays) {
                val old = prev[name]
                if (old != null) {
                    for (i in arr.indices) {
                        var d = abs(arr[i] - old[i])
                        // The rim uses tip direction x weight; the direction may swing when its weight is ~0.
                        if (name == "bt" && i % 4 < 2) {
                            val o = i - i % 4
                            d = abs(arr[i] * arr[o + 2] - old[i] * old[o + 2])
                        }
                        // Phases are uploaded wrapped to [0, 2pi).
                        if (name == "bw" && i % 4 >= 2) d = minOf(d, abs(d - 2f * PI.toFloat()))
                        val limit = 0.1f + 0.1f * max(abs(arr[i]), abs(old[i]))
                        assertTrue("$name[$i] jumped by $d at t=$t (step $step)", d <= limit)
                    }
                }
                prev[name] = arr.copyOf()
            }
            t += dt
            step++
        }
    }

    @Test
    fun fastMathMatchesExactMath() {
        val p = OrbRaster.params(SiriAnimationState.SPEAKING, 11.0, 0.5f)
        val n = 96
        val fast = IntArray(n * n)
        val exact = IntArray(n * n)
        OrbRaster.newField(fastMath = true).apply { setExtent(1.6f); update(p) }.renderInterior(fast, n, 1.04f)
        OrbRaster.newField(fastMath = false).apply { setExtent(1.6f); update(p) }.renderInterior(exact, n, 1.04f)
        var sum = 0.0
        var worst = 0
        for (i in fast.indices) for (s in intArrayOf(16, 8, 0)) {
            val d = abs(((fast[i] ushr s) and 0xFF) - ((exact[i] ushr s) and 0xFF))
            sum += d
            worst = maxOf(worst, d)
        }
        val mean = sum / (fast.size * 3)
        assertTrue("mean channel difference $mean", mean <= 1.5)
        assertTrue("max channel difference $worst", worst <= 12)
    }

    @Test
    fun motionIsSmoothAndNeverStatic() {
        val field = OrbRaster.newField(fastMath = true)
        val n = 64
        for (state in listOf(SiriAnimationState.IDLE, SiriAnimationState.SPEAKING)) {
            val diffs = ArrayList<Float>()
            var prev: IntArray? = null
            var t = 0.0
            // 20 s of engine time sampled at 30 fps.
            while (t < 20.0) {
                val img = IntArray(n * n)
                field.update(OrbRaster.params(state, t))
                field.renderInterior(img, n, 1.04f)
                prev?.let { diffs += meanAbs(it, img) }
                prev = img
                t += 1.0 / 30.0
            }
            val sorted = diffs.sorted()
            val median = sorted[sorted.size / 2]
            val worst = sorted.last()
            assertTrue("$state: no visible motion (median ${median})", median > 0.05f)
            assertTrue("$state: pop between frames (worst $worst vs median $median)", worst <= 4f * median + 0.5f)
        }
        // Frames 5 s apart look clearly different.
        val a = IntArray(n * n)
        val b = IntArray(n * n)
        field.update(OrbRaster.params(SiriAnimationState.IDLE, 3.0)); field.renderInterior(a, n, 1.04f)
        field.update(OrbRaster.params(SiriAnimationState.IDLE, 8.0)); field.renderInterior(b, n, 1.04f)
        assertTrue("static pattern", meanAbs(a, b) > 3f)
    }

    @Test
    fun frameLoopDoesNotAllocate() {
        // Reflection: java.lang.management is not on the Android unit-test compile classpath.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocated = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        val field = OrbRaster.newField(fastMath = true)
        val p = FrameParams()
        val pixels = IntArray(64 * 64)
        repeat(50) { p.fillSettled(SiriAnimationState.SPEAKING, config, it / 60.0); field.update(p); field.renderInterior(pixels, 64, 1.04f) }
        val before = allocated.invoke(bean, id) as Long
        for (i in 0 until 200) {
            p.fill(0f, i / 60.0, config.preset, 1f, 1f, 0.4f, 0.3f, 0.8f, 1f, 1f, 1f, 1f, 0.5f, 1f)
            field.update(p)
            field.renderInterior(pixels, 64, 1.04f)
        }
        val bytes = (allocated.invoke(bean, id) as Long) - before
        assertTrue("frame loop allocated $bytes bytes", bytes < 4096)
    }

    @Test
    fun rowPoolProducesTheSameImageAsOneThread() {
        val field = OrbRaster.newField(fastMath = true).apply { setExtent(1.6f) }
        val pool = OrbRowPool(3)
        val n = 80
        val parallel = IntArray(n * n)
        val single = IntArray(n * n)
        try {
            for (frame in 0 until 120) {
                field.update(OrbRaster.params(SiriAnimationState.SPEAKING, frame / 60.0, audio = (frame % 7) / 7f))
                pool.render(field, parallel, n, 1.04f)
                field.renderInterior(single, n, 1.04f)
                assertTrue("frame $frame differs", parallel.contentEquals(single))
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun frameParamsMappingIsUnchangedForTheBand() {
        // Golden values from the original engine mapping (CLASSIC, intensity 0.8, LISTENING, audio 0.4).
        val p = FrameParams()
        val state = SiriAnimationState.LISTENING
        val time = 3.3f
        p.fill(time, time.toDouble(), SiriAnimationPreset.CLASSIC, state.amplitude, state.energy, state.pulse, state.swirl,
            0.8f, 1f, 1f, 1f, 1f, 0.4f, 1f)
        val audio = 0.4f
        val pulseWave = 0.5f + 0.5f * sin(time * 2f * PI.toFloat() * 0.55f)
        assertEquals(state.pulse * pulseWave, p.pulse, 1e-4f)
        assertEquals(1f * (0.75f + 0.25f * state.energy), p.core, 1e-5f)
        assertEquals(0.8f * state.amplitude * 1f * 1.1f + audio * 0.85f, p.energy, 1e-5f)
        assertEquals((0.5f + 0.5f * 0.8f) * 1f * (0.8f + 0.2f * state.energy) + audio * 0.15f, p.brightness, 1e-5f)
        assertEquals(1f * 1f * (0.75f + 0.3f * state.energy + 0.25f * p.pulse + 0.3f * audio), p.glow, 1e-5f)
        assertEquals(time, p.time, 0f)
    }

    private fun meanAbs(a: IntArray, b: IntArray): Float {
        var s = 0L
        for (i in a.indices) for (sh in intArrayOf(16, 8, 0)) s += abs(((a[i] ushr sh) and 0xFF) - ((b[i] ushr sh) and 0xFF))
        return s.toFloat() / (a.size * 3)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
