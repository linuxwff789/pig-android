package com.linuxwff.pigapp

import org.json.JSONObject

/**
 * One archive in a WebDAV backup chain.
 *
 * A `full` archive is a chain root; every `inc` archive names the archive it
 * was based on in [base]. Restoring one increment therefore means restoring the
 * whole chain from its root, oldest first.
 */
data class BackupEntry(
    val name: String,
    /** `full` or `inc`. */
    val kind: String,
    val base: String? = null,
    /** Creation time in epoch milliseconds, used for display and pruning. */
    val ts: Long = 0L,
    val bytes: Long = 0L,
    val sha256: String = "",
    val entries: Int = 0,
    val deleted: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("kind", kind)
        if (!base.isNullOrBlank()) put("base", base)
        put("ts", ts)
        put("bytes", bytes)
        if (sha256.isNotBlank()) put("sha256", sha256)
        put("entries", entries)
        put("deleted", deleted)
    }

    companion object {
        fun from(obj: JSONObject): BackupEntry? {
            val name = obj.optString("name")
            if (name.isBlank()) return null
            return BackupEntry(
                name = name,
                kind = obj.optString("kind").ifBlank {
                    if (name.startsWith(WebDavClient.INC_PREFIX)) "inc" else "full"
                },
                base = obj.optString("base").ifBlank { null },
                ts = obj.optLong("ts", 0L),
                bytes = obj.optLong("bytes", 0L),
                sha256 = obj.optString("sha256"),
                entries = obj.optInt("entries", 0),
                deleted = obj.optInt("deleted", 0),
            )
        }
    }
}

/**
 * The chain index stored next to the archives as `pig-index.json`.
 *
 * WebDAV cannot tell an increment from a full archive by its bytes, so the
 * index is what lets a restore walk `base` from the newest archive back to the
 * root. Losing it does not corrupt anything, but an increment is then
 * unrestorable: the app refuses rather than applying a partial chain.
 */
data class BackupIndex(val entries: List<BackupEntry> = emptyList()) {
    /** The chain that restores [name], oldest first; empty when unknown. */
    fun chainFor(name: String): List<BackupEntry> {
        val byName = entries.associateBy { it.name }
        var current = byName[name] ?: return emptyList()
        val out = ArrayList<BackupEntry>()
        val seen = HashSet<String>()
        while (seen.add(current.name)) {
            out.add(current)
            val base = current.base ?: break
            current = byName[base] ?: break
        }
        out.reverse()
        return out
    }

    fun with(entry: BackupEntry): BackupIndex =
        copy(entries = entries.filterNot { it.name == entry.name } + entry)

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("entries", org.json.JSONArray().apply { entries.forEach { put(it.toJson()) } })
    }

    companion object {
        fun from(body: String): BackupIndex {
            val root = JSONObject(body)
            val array = root.optJSONArray("entries") ?: return BackupIndex()
            val out = ArrayList<BackupEntry>(array.length())
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { BackupEntry.from(it)?.let(out::add) }
            }
            return BackupIndex(out.sortedBy { it.ts })
        }
    }
}
