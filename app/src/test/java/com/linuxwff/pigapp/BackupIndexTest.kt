package com.linuxwff.pigapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupIndexTest {
    private fun full(name: String, ts: Long = 1) = BackupEntry(name, "full", null, ts, 100)
    private fun inc(name: String, base: String, ts: Long) = BackupEntry(name, "inc", base, ts, 50)

    @Test
    fun chainWalksBackToRoot() {
        val index = BackupIndex(listOf(full("F1"), inc("I1", "F1", 2), inc("I2", "I1", 3)))
        assertEquals(listOf("F1", "I1", "I2"), index.chainFor("I2").map { it.name })
    }

    @Test
    fun chainStopsAtAMissingLink() {
        val index = BackupIndex(listOf(full("F1"), inc("I2", "gone", 3)))
        // The caller checks first().kind and last().name; this one has no full root.
        val chain = index.chainFor("I2")
        assertEquals(listOf("I2"), chain.map { it.name })
        assertTrue(chain.first().kind != "full")
    }

    @Test
    fun jsonRoundTripKeepsTheChain() {
        val index = BackupIndex(listOf(full("F1"), inc("I1", "F1", 2)))
        val parsed = BackupIndex.from(index.toJson().toString())
        assertEquals(listOf("F1", "I1"), parsed.entries.map { it.name })
        assertEquals("F1", parsed.entries[1].base)
        assertEquals("inc", parsed.entries[1].kind)
    }

    @Test
    fun kindIsInferredForLegacyEntries() {
        val entry = BackupEntry.from(JSONObject().put("name", "pig-inc-20260101-030000.1.tar.gz"))
        assertEquals("inc", entry?.kind)
        val legacy = BackupEntry.from(JSONObject().put("name", "pig-backup-20260101-030000.tar.gz"))
        assertEquals("full", legacy?.kind)
    }

    @Test
    fun pruneKeepsEveryChainARecentArchiveBelongsTo() {
        // Chain A: F1,I1,I2. Chain B: F2. The two most recent archives span both
        // chains, so neither may lose its root.
        val index = BackupIndex(
            listOf(full("F1", 1), inc("I1", "F1", 2), inc("I2", "I1", 3), full("F2", 4)),
        )
        val (kept, removed) = WebDavClient.pruneChain(index, 2)
        assertEquals(4, kept.entries.size)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun pruneDropsAChainNoRecentArchiveBelongsTo() {
        // Chain A: F1,I1. Chain B: F2,I2,I3. The two newest archives are in B,
        // so A goes away as a whole.
        val index = BackupIndex(
            listOf(
                full("F1", 1), inc("I1", "F1", 2),
                full("F2", 3), inc("I2", "F2", 4), inc("I3", "I2", 5),
            ),
        )
        val (kept, removed) = WebDavClient.pruneChain(index, 2)
        assertEquals(listOf("F2", "I2", "I3"), kept.entries.map { it.name })
        assertEquals(listOf("F1", "I1"), removed.map { it.name })
    }

    @Test
    fun indexesDoNotAcceptPathTraversal() {
        assertTrue(WebDavClient.isBackupName("pig-inc-20260101-030000.123.tar.gz"))
        assertTrue(WebDavClient.isBackupName("pig-full-20260101-030000.tar.gz"))
        assertFalse(WebDavClient.isBackupName("../evil.tar.gz"))
        assertFalse(WebDavClient.isBackupName("pig-index.json"))
    }
}
