package com.linuxwff.pigapp

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryOrderTest {
    @Test
    fun noticesArePlacedByTimestamp() {
        val items = listOf(
            ChatMsg("u1", Role.USER, text = "ask", at = 100),
            ChatMsg("a1", Role.ASSISTANT, text = "answer", at = 200),
            ChatMsg("u2", Role.USER, text = "again", at = 400),
        )
        val notices = listOf(
            ChatMsg("n300", Role.NOTICE, text = "second", isError = true, at = 300),
            ChatMsg("n150", Role.NOTICE, text = "first", isError = true, at = 150),
            ChatMsg("n500", Role.NOTICE, text = "last", isError = true, at = 500),
        )
        val merged = mergeNotices(items, notices)
        assertEquals(
            listOf("u1", "n150", "a1", "n300", "u2", "n500"),
            merged.map { it.id },
        )
    }

    @Test
    fun noticesAlreadyInHistoryAreNotDuplicated() {
        val items = listOf(ChatMsg("a1", Role.NOTICE, "boom", isError = true, at = 200))
        val notices = listOf(
            ChatMsg("n1", Role.NOTICE, text = "boom", isError = true, at = 200),
            ChatMsg("n2", Role.NOTICE, text = "kept", isError = true, at = 300),
        )
        assertEquals(listOf("a1", "n2"), mergeNotices(items, notices).map { it.id })
    }

    @Test
    fun unknownTimestampGoesLast() {
        val items = listOf(ChatMsg("a1", Role.ASSISTANT, "x", at = 200))
        val notices = listOf(ChatMsg("n0", Role.NOTICE, "old", isError = true, at = 0))
        assertEquals(listOf("a1", "n0"), mergeNotices(items, notices).map { it.id })
        assertEquals(0L, parseMessageTime("not a timestamp"))
        assertEquals(1_789_948_800_000L, parseMessageTime("2026-09-21T00:00:00Z"))
    }
    @Test
    fun pagedWindowHidesNoticesFromEarlierTurns() {
        val notices = listOf(
            ChatMsg("old", Role.NOTICE, "old", isError = true, at = 100),
            ChatMsg("same", Role.NOTICE, "same", isError = true, at = 200),
            ChatMsg("new", Role.NOTICE, "new", isError = true, at = 300),
        )
        assertEquals(
            listOf("same", "new"),
            noticesForWindow(notices, historyStart = 10, firstMessageAt = 200).map { it.id },
        )
        assertEquals(notices, noticesForWindow(notices, historyStart = 0, firstMessageAt = 200))
    }
    @Test
    fun olderPageTargetUsesTotalWithoutOverflow() {
        assertEquals(4369, olderHistoryTarget(40, 4369))
        assertEquals(80, olderHistoryTarget(40, 0))
        assertEquals(Int.MAX_VALUE, olderHistoryTarget(Int.MAX_VALUE - 10, 0))
        assertEquals(Int.MAX_VALUE, olderHistoryTarget(40, Int.MAX_VALUE))
    }
}
