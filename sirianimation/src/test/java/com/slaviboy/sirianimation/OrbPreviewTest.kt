package com.slaviboy.sirianimation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Renders the orb on the JVM with the exact per-pixel shader math and checks look metrics.
 * Output: `sirianimation/build/orb-preview/` (`contact_sheet.png` = states x times,
 * `cpu_sheet.png` = compatibility renderer, `metrics.csv`).
 * Run with `./gradlew :sirianimation:testDebugUnitTest --rerun`.
 *
 * Thresholds are regression guards around the tuned look. For reference, the classic orb
 * (animated GIF and a photo, R ~150 px) measured: dark area 16–40 %, p95 luma 215–237, largest hue
 * bucket 31–49 %, halo +12 luma at 1.02R falling to ~0 by 1.7R, rim brighter than the halo all around.
 */
class OrbPreviewTest {

    private val outDir = File(System.getProperty("orb.preview.dir") ?: "build/orb-preview")

    private class Row(val name: String, val state: SiriAnimationState, val audio: Float = 0f)

    private val rows = listOf(
        Row("idle", SiriAnimationState.IDLE),
        Row("listening", SiriAnimationState.LISTENING),
        Row("thinking", SiriAnimationState.THINKING),
        Row("speaking", SiriAnimationState.SPEAKING),
        Row("speaking_audio", SiriAnimationState.SPEAKING, 0.8f)
    )
    private val times = doubleArrayOf(0.0, 2.5, 6.0, 11.0, 19.0, 37.0)

    @Test
    fun previewAndLookMetrics() {
        outDir.mkdirs()
        val field = OrbRaster.newField()
        val failures = ArrayList<String>()
        val csv = StringBuilder("row,t,").append(OrbMetrics.HEADER).append('\n')
        val sheet = ArrayList<List<OrbFrame>>()
        val cpuSheet = ArrayList<List<OrbFrame>>()
        val meanByRow = HashMap<String, Float>()
        val all = ArrayList<OrbMetrics>()
        for (row in rows) {
            val frames = ArrayList<OrbFrame>()
            val cpuFrames = ArrayList<OrbFrame>()
            var meanSum = 0f
            for (t in times) {
                val p = OrbRaster.params(row.state, t, row.audio)
                val f = OrbRaster.renderFull(field, p, RADIUS)
                frames += f
                OrbRaster.write(File(outDir, "orb_${row.name}_t$t.png"), f)
                val m = OrbMetrics(f)
                all += m
                meanSum += m.meanLuma
                csv.append(row.name).append(',').append(t).append(',').append(m.csv()).append('\n')
                checkFrame(failures, m, "${row.name} t=$t")
                if (row.audio == 0f && t <= 6.0) cpuFrames += OrbRaster.renderCpuSim(field, p, RADIUS)
            }
            meanByRow[row.name] = meanSum / times.size
            sheet += frames
            if (cpuFrames.isNotEmpty()) cpuSheet += cpuFrames
        }
        OrbRaster.contactSheet(File(outDir, "contact_sheet.png"), sheet, 2)
        OrbRaster.contactSheet(File(outDir, "cpu_sheet.png"), cpuSheet, 2)
        File(outDir, "metrics.csv").writeText(csv.toString())

        // Averages over all frames: the look as a whole, not any single moment.
        val avgSat = all.map { it.saturated }.average()
        val avgHue = all.map { it.biggestHue }.average()
        val avgGrad = all.map { it.gradP995 }.average()
        val avgDark = all.map { it.darkFraction }.average()
        if (avgSat < 0.1) failures += "average saturation $avgSat"
        if (avgHue > 0.55) failures += "average largest hue bucket $avgHue (one hue dominates)"
        if (avgGrad < 15.0) failures += "average gradient p99.5 $avgGrad (too soft)"
        if (avgDark !in 0.2..0.4) failures += "average dark fraction $avgDark"

        val idle = meanByRow.getValue("idle")
        val listening = meanByRow.getValue("listening")
        val speaking = meanByRow.getValue("speaking")
        val loud = meanByRow.getValue("speaking_audio")
        if (!(idle < listening && listening <= speaking * 1.03f && speaking < loud)) {
            failures += "state brightness order idle=$idle listening=$listening speaking=$speaking audio=$loud"
        }
        assertTrue("Look metrics failed (see ${outDir.absolutePath}):\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun checkFrame(failures: MutableList<String>, m: OrbMetrics, label: String) {
        fun need(ok: Boolean, what: String) {
            if (!ok) failures += "$label: $what"
        }
        need(m.nanFree, "NaN")
        // Coherent dark glass must remain, but the blades must not vanish.
        need(m.darkFraction in 0.12f..0.5f, "dark fraction ${m.darkFraction}")
        // Bright, luminous highlights without blowing out to white.
        need(m.p95 >= 140f && m.p99 >= 190f, "highlights p95=${m.p95} p99=${m.p99}")
        need(m.nearWhite <= 0.1f, "near-white ${m.nearWhite}")
        need(m.centerLuma >= 170f, "center luma ${m.centerLuma}")
        need(m.huesOver10 >= 2, "hue buckets ${m.huesOver10}")
        need(m.gradP995 >= 10f, "crisp edges (gradient p99.5) ${m.gradP995}")
        need(m.coreOffset <= 0.45f, "core offset ${m.coreOffset}")
        // Glass: faint halo that is gone before the view edge, rim always brighter than the halo.
        need(m.halo102 in 6f..22f, "halo at 1.02R ${m.halo102}")
        need(m.halo150 <= 3f, "halo at 1.5R ${m.halo150}")
        need(m.haloEdge <= 1.5f, "halo at view edge ${m.haloEdge}")
        need(m.rimOverHalo >= 0.9f, "rim over halo ${m.rimOverHalo}")
    }

    private companion object {
        const val RADIUS = 150f
    }
}
