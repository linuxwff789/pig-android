package com.linuxwff.pigapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFenceTest {
    @Test
    fun fourBackticksContainThreeBackticks() {
        // A ```` block showing ``` markdown is the common case; closing at the
        // first ``` cut the block in half.
        val text = "````\n```\ninner\n```\n````"
        val segments = splitCodeFences(text)
        assertEquals(1, segments.size)
        val code = segments[0] as MarkdownSegment.Code
        assertTrue("code = ${code.code}", code.code.contains("```"))
        assertTrue(code.code.contains("inner"))
    }

    @Test
    fun backtickInsideTildeFenceIsContent() {
        val text = "~~~\n```\nstill code\n~~~"
        val segments = splitCodeFences(text)
        assertEquals(1, segments.size)
        assertEquals("```\nstill code", (segments[0] as MarkdownSegment.Code).code)
    }

    @Test
    fun fourSpacesOfIndentDoNotCloseTheFence() {
        val text = "```\n    ```\nstill code\n```"
        val segments = splitCodeFences(text)
        assertEquals(1, segments.size)
        assertTrue((segments[0] as MarkdownSegment.Code).code.contains("still code"))
    }

    @Test
    fun languageIsKept() {
        val segments = splitCodeFences("```kotlin\nval x = 1\n```\nafter")
        assertEquals(2, segments.size)
        assertEquals("kotlin", (segments[0] as MarkdownSegment.Code).language)
        assertEquals("val x = 1", (segments[0] as MarkdownSegment.Code).code)
        assertEquals("after", (segments[1] as MarkdownSegment.Text).text)
    }
}
