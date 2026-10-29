package com.linuxwff.pigapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanWidgetTest {
    @Test
    fun parsesCheckboxText() {
        val spec = parsePlanWidget("todos", listOf("Plan: Ship", "- [x] Write", "- [ ] Test"))
        assertEquals("Ship", spec?.title)
        assertEquals(2, spec?.total)
        assertEquals(1, spec?.done)
    }

    @Test
    fun ignoresNonPlanKeys() {
        assertNull(parsePlanWidget("speed", listOf("- [x] not a plan")))
    }

    @Test
    fun keysAreReservedCaseInsensitively() {
        assertTrue(isPlanWidgetKey("Plan"))
        assertTrue(isPlanWidgetKey(" plan-todos "))
        assertFalse(isPlanWidgetKey("planning"))
    }
}
