package net.unfret.android

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The proof of work a free deep check needs (`GET /v1/analyze/challenge`, as the extension and the website solve it): a
 * decimal counter `n` with SHA-256(challenge + ":" + n) starting with `bits` zero bits. On average that takes 2^bits
 * hashes (20 bits: about a million), spread over the phone's cores. It is never made easier: the server checks it.
 */
object ProofOfWork {
    /** Whether SHA-256([challenge]:[solution]) starts with [bits] zero bits (the server's `pow_ok`). */
    fun solves(challenge: String, solution: String, bits: Int): Boolean =
        leadingZeroBits(MessageDigest.getInstance("SHA-256").digest("$challenge:$solution".toByteArray())) >= bits

    /**
     * Find a solution. [onProgress] is called now and then (from a worker thread) with how many counters were tried and
     * how many are expected (2^bits). Throws InterruptedException when the calling thread is interrupted (the screen
     * went away).
     */
    fun solve(
        challenge: String,
        bits: Int,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS),
        onProgress: (tried: Long, expected: Long) -> Unit = { _, _ -> },
    ): String {
        require(bits in 1..MAX_BITS) { "unexpected difficulty: $bits" }
        val expected = 1L shl bits
        val prefix = "$challenge:".toByteArray(Charsets.UTF_8)
        val found = AtomicReference<String?>(null)
        val tried = AtomicLong(0)
        val workers = (0 until threads).map { start ->
            Thread({
                val digest = MessageDigest.getInstance("SHA-256")
                var n = start.toLong()
                var sinceReport = 0
                while (found.get() == null) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                    digest.update(prefix)
                    if (leadingZeroBits(digest.digest(n.toString().toByteArray(Charsets.US_ASCII))) >= bits) {
                        found.compareAndSet(null, n.toString())
                        return@Thread
                    }
                    n += threads
                    if (++sinceReport == REPORT_EVERY) {
                        sinceReport = 0
                        onProgress(tried.addAndGet(REPORT_EVERY.toLong()), expected)
                    }
                }
            }, "unfret-pow-$start").apply { isDaemon = true }
        }
        workers.forEach { it.start() }
        try {
            workers.forEach { it.join() }
        } catch (e: InterruptedException) {
            workers.forEach { it.interrupt() }
            throw e
        }
        return found.get() ?: throw InterruptedException("stopped before a solution was found")
    }

    private fun leadingZeroBits(digest: ByteArray): Int {
        var bits = 0
        for (byte in digest) {
            val b = byte.toInt() and 0xff
            if (b != 0) return bits + Integer.numberOfLeadingZeros(b) - 24
            bits += 8
        }
        return bits
    }

    /** Well above the server's `deep_pow_bits` (20 by default); 32 at most. */
    private const val MAX_BITS = 32
    private const val MAX_THREADS = 4
    private const val REPORT_EVERY = 4096
}
