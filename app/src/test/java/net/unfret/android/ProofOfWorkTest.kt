package net.unfret.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ProofOfWorkTest {
    @Test
    fun `a solution has the asked number of leading zero bits`() {
        for (threads in listOf(1, 3)) {
            val solution = ProofOfWork.solve("1900000000.feedface.12.mac", 12, threads)
            assertTrue(solution.length <= 32 && solution.all { it.isDigit() })
            val digest = MessageDigest.getInstance("SHA-256").digest("1900000000.feedface.12.mac:$solution".toByteArray())
            // 12 bits: the first byte and the top half of the second are zero.
            assertTrue(digest[0].toInt() == 0 && (digest[1].toInt() and 0xf0) == 0)
            assertTrue(ProofOfWork.solves("1900000000.feedface.12.mac", solution, 12))
        }
    }

    @Test
    fun `the check agrees with the server's`() {
        // Whatever SHA-256("c:0") starts with, it solves exactly as many bits as it has leading zeros.
        val first = MessageDigest.getInstance("SHA-256").digest("c:0".toByteArray())[0].toInt() and 0xff
        val zeros = Integer.numberOfLeadingZeros(first) - 24
        assertTrue(ProofOfWork.solves("c", "0", zeros))
        assertFalse(ProofOfWork.solves("c", "0", zeros + 1))
    }

    @Test(expected = InterruptedException::class)
    fun `an interrupted search stops`() {
        Thread.currentThread().interrupt()
        ProofOfWork.solve("never", 32, 2)
    }
}
