package com.linuxwff.pigapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanWidgetTest {
    @Test
    fun parsesJsonStepsAndDoneFlags() {
        val spec = parsePlanWidget(
            "plan",
            listOf("""{"plan":{"title":"Review","steps":[{"text":"Read code","done":true},{"text":"Fix bugs","done":false}]}}"""),
        )
        assertEquals("Review", spec?.title)
        assertEquals(2, spec?.total)
        assertEquals(1, spec?.done)
        assertEquals("Read code", spec?.steps?.get(0)?.text)
        assertTrue(spec?.steps?.get(0)?.done == true)
        assertFalse(spec?.steps?.get(1)?.done == true)
    }

    @Test
    fun malformedJsonFallsBackToText() {
        assertNull(parsePlanWidget("plan", listOf("{not json")))
    }

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
