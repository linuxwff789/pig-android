package com.linuxwff.pigapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSwitchTest {
    @Test
    fun onlyMatchesThePendingTarget() {
        assertTrue(isSwitchConfirmation("/sessions/a.jsonl", "/sessions/a.jsonl"))
        assertFalse(isSwitchConfirmation("/sessions/a.jsonl", "/sessions/b.jsonl"))
        assertFalse(isSwitchConfirmation(null, "/sessions/a.jsonl"))
        assertFalse(isSwitchConfirmation("/sessions/a.jsonl", ""))
        assertFalse(isSwitchConfirmation("", "/sessions/a.jsonl"))
    }
}
