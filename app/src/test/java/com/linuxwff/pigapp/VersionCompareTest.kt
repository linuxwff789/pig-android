package com.linuxwff.pigapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionCompareTest {
    @Test
    fun comparesFourPartVersions() {
        assertEquals(1, compareVersionNames("1.1.70.5", "1.1.70.4"))
        assertEquals(-1, compareVersionNames("1.1.69.9", "1.1.70.0"))
        assertEquals(0, compareVersionNames("1.1.70", "1.1.70.0"))
    }

    @Test
    fun comparesNumericSegmentsNotStrings() {
        assertEquals(1, compareVersionNames("1.10.0", "1.9.0"))
    }

    @Test
    fun rejectsNonNumericVersions() {
        assertNull(compareVersionNames("dev-abc1234", "1.1.70"))
        assertNull(compareVersionNames("", "1.1.70"))
        assertNull(compareVersionNames("1.1.70-rc1", "1.1.70"))
    }
}
