package com.linuxwff.pigapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun fourPartVersionsCompareNumeric() {
        assertTrue(UpdateChecker.isNewer("1.1.70.1", "1.1.69.1"))
        assertTrue(UpdateChecker.isNewer("1.1.70", "1.1.69.9"))
        assertFalse(UpdateChecker.isNewer("1.1.69.1", "1.1.69.1"))
        assertFalse(UpdateChecker.isNewer("1.1.68.2", "1.1.69.1"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertTrue(UpdateChecker.isNewer("1.1.70", "1.1.69.1"))
        assertFalse(UpdateChecker.isNewer("1.1.69", "1.1.69.0"))
    }

    @Test
    fun aFinalReleaseIsNewerThanItsReleaseCandidate() {
        assertTrue(UpdateChecker.isNewer("1.2.3", "1.2.3-rc1"))
        assertFalse(UpdateChecker.isNewer("1.2.3-rc1", "1.2.3"))
    }

    @Test
    fun dotsAreNotStringCompared() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
    }
}
