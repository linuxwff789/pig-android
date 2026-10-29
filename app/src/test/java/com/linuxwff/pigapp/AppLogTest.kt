package com.linuxwff.pigapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test
    fun redactsSecretsTokensAndKeyShapes() {
        val out = AppLog.redact(
            """{"token":"tok-123","apiKey":"sk-abcdefgh12345678","note":"--token deadbeef"}""",
        )
        assertFalse(out.contains("tok-123"))
        assertFalse(out.contains("sk-abcdefgh12345678"))
        assertFalse(out.contains("deadbeef"))
        assertTrue(out.contains("***"))
    }
}
