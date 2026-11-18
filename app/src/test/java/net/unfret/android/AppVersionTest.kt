package net.unfret.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun aPreReleaseSuffixIsABeta() {
        assertTrue(isBeta("0.1.0-beta.1"))
        assertTrue(isBeta("1.2.3-rc.2"))
    }

    @Test
    fun aPlainVersionIsNot() {
        assertFalse(isBeta("0.1.0"))
        assertFalse(isBeta("10.20.30"))
    }
}
