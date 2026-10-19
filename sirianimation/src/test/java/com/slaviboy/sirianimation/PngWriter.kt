package com.slaviboy.sirianimation

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Minimal RGBA PNG encoder (java.util.zip only), so previews need no AWT/ImageIO. */
internal object PngWriter {

    fun write(file: File, w: Int, h: Int, argb: IntArray) {
        file.parentFile?.mkdirs()
        val raw = ByteArray(h * (1 + w * 4))
        var k = 0
        for (y in 0 until h) {
            raw[k++] = 0
            for (x in 0 until w) {
                val c = argb[y * w + x]
                raw[k++] = (c ushr 16).toByte()
                raw[k++] = (c ushr 8).toByte()
                raw[k++] = c.toByte()
                raw[k++] = (c ushr 24).toByte()
            }
        }
        val z = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out, Deflater(6)).use { it.write(raw) }
        }.toByteArray()
        DataOutputStream(FileOutputStream(file)).use { o ->
            o.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            chunk(o, "IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(6).put(0).put(0).put(0).array())
            chunk(o, "IDAT", z)
            chunk(o, "IEND", ByteArray(0))
        }
    }

    private fun chunk(o: DataOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        o.writeInt(data.size)
        o.write(t)
        o.write(data)
        o.writeInt(CRC32().apply { update(t); update(data) }.value.toInt())
    }
}
