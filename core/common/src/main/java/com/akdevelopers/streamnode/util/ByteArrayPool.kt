package com.akdevelopers.streamnode.util

/**
 * ByteArrayPool — lightweight, thread-safe pool for reusing byte arrays.
 *
 * Reduces GC pressure in the audio capture loop where the PCM read buffer
 * was otherwise re-allocated on every captureLoop invocation (i.e. on every
 * mic start, quality change, phone-call mode switch, or error-recovery restart).
 *
 * Design constraints:
 *  - Pool size capped at [MAX_POOL_SIZE] to avoid unbounded memory growth.
 *  - Over-sized buffers are accepted back (avoids size-fragmentation).
 *  - acquire() returns the first pooled buffer whose size >= minSize,
 *    so a 960-byte PCM buffer can satisfy a 480-byte request if needed.
 *  - Thread-safe via synchronized on the internal deque.
 *
 * Usage (captureLoop pattern):
 *   val buf = ByteArrayPool.acquire(config.frameBytes)
 *   try {
 *       while (isActive) { record.read(buf, ...); encode(buf) }
 *   } finally {
 *       ByteArrayPool.release(buf)   // safe: loop is done, buf no longer referenced
 *   }
 */
object ByteArrayPool {

    private const val MAX_POOL_SIZE = 8
    private val pool = ArrayDeque<ByteArray>(MAX_POOL_SIZE)

    /**
     * Acquire a buffer of at least [minSize] bytes.
     * Returns a pooled instance if one with size >= [minSize] is available;
     * otherwise allocates a fresh ByteArray.
     */
    fun acquire(minSize: Int): ByteArray {
        synchronized(pool) {
            val it = pool.iterator()
            while (it.hasNext()) {
                val buf = it.next()
                if (buf.size >= minSize) {
                    it.remove()
                    return buf
                }
            }
        }
        return ByteArray(minSize)
    }

    /**
     * Return [buf] to the pool for future reuse.
     * MUST NOT be called while [buf] is still reachable by any other object
     * (e.g. a WebSocket send queue that hasn't flushed yet).
     * Safe to call after captureLoop exits because the loop holds the only reference.
     */
    fun release(buf: ByteArray) {
        synchronized(pool) {
            if (pool.size < MAX_POOL_SIZE) pool.addLast(buf)
        }
    }
}
