package com.linuxwff.pigapp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

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
        // pig's session file stores epoch milliseconds on the message object.
        assertEquals(1_793_340_742_501L, parseMessageTime("1793340742501"))
        assertEquals(1_793_340_742_000L, parseMessageTime("1793340742"))
    }
    @Test
    fun chatTimeLabelFollowsAge() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 10, 31, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
            ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("09:05", formatChatTime(at(2026, 10, 31, 9, 5), now, zone))
        assertEquals("03-02 07:08", formatChatTime(at(2026, 3, 2, 7, 8), now, zone))
        assertEquals("2025-12-31 23:59", formatChatTime(at(2025, 12, 31, 23, 59), now, zone))
        assertEquals("", formatChatTime(0, now, zone))
    }

    @Test
    fun toolDurationLabelIsCompact() {
        assertEquals("", formatToolDuration(0))
        assertEquals("132ms", formatToolDuration(132))
        assertEquals("1.2s", formatToolDuration(1_234))
        assertEquals("1m 05s", formatToolDuration(65_000))
        assertEquals("1h 02m", formatToolDuration(3_720_000))
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
        assertEquals(400, olderHistoryTarget(200, 4369))
        assertEquals(400, olderHistoryTarget(200, 0))
        assertEquals(150, olderHistoryTarget(0, 150))
        assertEquals(Int.MAX_VALUE, olderHistoryTarget(Int.MAX_VALUE - 10, 0))
        assertEquals(240, olderHistoryTarget(40, Int.MAX_VALUE))
    }
}
