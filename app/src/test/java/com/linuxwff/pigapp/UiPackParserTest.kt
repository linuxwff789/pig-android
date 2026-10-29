package com.linuxwff.pigapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPackParserTest {
    @Test
    fun parsesMinimalPack() {
        val result = UiPackParser.parse(
            mapOf(
                "manifest.json" to """{"name":"Test pack","version":2}""",
                "theme/colors.json" to """{"primary":"#ff0000","background":"#101010"}""",
                "layout/chat.json" to """{"chat":{"assistantLabel":"AI","toolGroups":false}}""",
            ),
        )
        val pack = result.pack
        assertNotNull(pack)
        assertEquals("Test pack", pack?.name)
        assertEquals(2, pack?.version)
        assertEquals("AI", pack?.layout?.assistantLabel)
        assertEquals(false, pack?.layout?.toolGroups)
    }

    @Test
    fun rejectsMalformedManifest() {
        val result = UiPackParser.parse(mapOf("manifest.json" to "{not json"))
        assertNull(result.pack)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun missingManifestIsRejected() {
        assertNull(UiPackParser.parse(emptyMap()).pack)
    }
}
