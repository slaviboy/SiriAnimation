package com.slaviboy.sirianimation

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Shades the CPU orb's interior rows in parallel. A few worker threads and the calling thread
 * claim rows one at a time, so slow cores never hold up the frame, and [render] returns once
 * every row is done. Allocation-free per frame; workers park between frames.
 *
 * Rows are claimed with a compare-and-set on `(generation, row)`, so a worker that wakes up late
 * can never write into a newer frame. Each worker shades with its own [OrbField] copied from the
 * caller's field, which the caller does not modify while [render] runs.
 */
internal class OrbRowPool(workerCount: Int) {

    private val fields = Array(workerCount) { OrbField(fastMath = true) }
    private val threads = arrayOfNulls<Thread>(workerCount)
    /** `(generation << 32) | next row`. */
    private val cursor = AtomicLong()
    private val done = AtomicInteger()

    @Volatile
    private var generation = 0

    @Volatile
    private var quit = false

    @Volatile
    private var failed = false

    // The current job, published to the workers by the volatile write of [generation].
    private var source: OrbField? = null
    private var pixels = IntArray(0)
    private var n = 0
    private var margin = 1f

    /** Renders all [n] rows of [field]'s current frame into [pixels] (see [OrbField.renderInterior]). */
    fun render(field: OrbField, pixels: IntArray, n: Int, margin: Float) {
        if (fields.isEmpty() || failed || quit) {
            field.renderInterior(pixels, n, margin)
            return
        }
        ensureStarted()
        // Invalidate the cursor first, so a late worker can never claim a row with the new job fields.
        val g = generation + 1
        cursor.set((g.toLong() and 0xFFFFFFFFL) shl 32)
        done.set(0)
        source = field
        this.pixels = pixels
        this.n = n
        this.margin = margin
        generation = g
        for (t in threads) LockSupport.unpark(t)

        claimRows(field, g, pixels, n, margin)
        val start = System.nanoTime()
        while (done.get() < n) {
            if (failed || System.nanoTime() - start > STALL_NS) {
                // A worker died or stalled: finish this frame alone and stop using the pool.
                failed = true
                field.renderInterior(pixels, n, margin)
                return
            }
            Thread.yield()
        }
    }

    /** Stops the workers. The pool renders single-threaded afterwards. */
    fun shutdown() {
        quit = true
        for (t in threads) LockSupport.unpark(t)
    }

    private fun claimRows(field: OrbField, g: Int, pixels: IntArray, n: Int, margin: Float) {
        while (true) {
            val c = cursor.get()
            if ((c ushr 32).toInt() != g) return
            val row = (c and 0xFFFFFFFFL).toInt()
            if (row >= n) return
            if (cursor.compareAndSet(c, c + 1)) {
                field.renderRows(pixels, n, margin, row, row + 1)
                done.incrementAndGet()
            }
        }
    }

    private fun ensureStarted() {
        if (threads.isEmpty() || threads[0] != null) return
        for (i in threads.indices) {
            threads[i] = Thread({ workerLoop(i) }, "SiriOrbCpu-$i").apply {
                isDaemon = true
                // Maps to Android's display priority, like the render thread's helpers.
                priority = Thread.NORM_PRIORITY + 2
                start()
            }
        }
    }

    private fun workerLoop(index: Int) {
        val local = fields[index]
        var seen = 0
        while (!quit) {
            val g = generation
            if (g == seen) {
                LockSupport.park(this)
                continue
            }
            seen = g
            val src = source ?: continue
            try {
                local.copyFrom(src)
                claimRows(local, g, pixels, n, margin)
            } catch (t: Throwable) {
                failed = true
                return
            }
        }
    }

    companion object {
        private const val STALL_NS = 250_000_000L

        /** Workers to add to the calling thread: one per spare core, at most three. */
        fun defaultWorkers(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(0, 3)
    }
}
