package com.linuxwff.pigapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Upload and restore of whole WebDAV backup chains.
 *
 * The relay owns the file system, so it decides what changed and writes an
 * archive that contains only those files. The app owns the chain: it reads
 * `pig-index.json`, tells the relay which archive it already has, uploads the
 * new one, then appends it to the index. A restore walks the index back to the
 * chain root, downloads every link concurrently and hands them to the relay
 * oldest first.
 */
object WebDavBackupFlow {
    /**
     * Chain length after which the next upload is a new full archive. A long
     * chain costs one extra index lookup per link, and each restore has to
     * apply every link in order.
     */
    const val MAX_CHAIN = 7

    /** One backup or restore at a time, shared by the UI and the worker. */
    private val transferMutex = Mutex()

    data class Uploaded(
        val name: String,
        val kind: String,
        val bytes: Long,
        val pruned: Int,
    )

    data class Restored(
        val entries: Int,
        val backup: String,
        val warning: String,
        val settingsFixed: Int,
        val chain: Int,
    )

    suspend fun upload(
        relay: RelayConnection,
        config: WebDavConfig,
        cacheDir: File,
    ): Uploaded = transferMutex.withLock {
        withContext(Dispatchers.IO) {
            val index = WebDavClient.readIndex(config) ?: BackupIndex()
            val newest = index.entries.lastOrNull()
            val chain = if (newest == null) emptyList() else index.chainFor(newest.name)
            val chainComplete = newest != null && chain.isNotEmpty() &&
                chain.first().kind == "full" && chain.last().name == newest.name
            // A missing index, an incomplete chain or one that grew too long
            // starts a new root. The relay also falls back to a full archive
            // when it does not recognise the base, so a lost relay state cannot
            // produce a broken chain.
            val forceFull = newest == null || !chainComplete || chain.size >= MAX_CHAIN
            val temp = File.createTempFile("pig-upload-", WebDavClient.SUFFIX, cacheDir)
            try {
                val done = temp.outputStream().use { out ->
                    relay.exportBackup(
                        out,
                        includeSessions = true,
                        includeUi = true,
                        incremental = !forceFull,
                        base = if (forceFull) null else newest?.name,
                        forceFull = forceFull,
                    ) { }
                }
                val name = done.optString("name")
                if (name.isBlank() || !WebDavClient.isBackupName(name)) {
                    error("relay 没有返回可用的备份文件名")
                }
                val entry = BackupEntry(
                    name = name,
                    kind = done.optString("kind", "full").ifBlank { "full" },
                    base = done.optString("base").ifBlank { null },
                    ts = System.currentTimeMillis(),
                    bytes = temp.length(),
                    sha256 = done.optString("sha256"),
                    entries = done.optInt("entries"),
                    deleted = done.optInt("deleted"),
                )
                WebDavClient.upload(config, name, temp)
                val (kept, removed) = WebDavClient.pruneChain(index.with(entry), config.keep)
                // The index is written before the old archives are deleted: an
                // index that still names a deleted archive cannot be restored,
                // while an archive the index no longer names only wastes space.
                try {
                    WebDavClient.writeIndex(config, kept)
                } catch (error: Throwable) {
                    // Do not leave an archive nobody's index mentions.
                    runCatching { WebDavClient.delete(config, name) }
                    throw error
                }
                var pruned = 0
                for (old in removed) {
                    val ok = runCatching { WebDavClient.delete(config, old.name) }.isSuccess
                    if (ok) pruned++
                }
                AppLog.event(
                    "webdav",
                    "upload ok: $name kind=${entry.kind} bytes=${entry.bytes} pruned=$pruned",
                )
                Uploaded(name, entry.kind, entry.bytes, pruned)
            } finally {
                temp.delete()
            }
        }
    }

    /**
     * Restores [name] and every archive it depends on. Downloads run four at a
     * time; applying them has to stay serial because each link builds on the
     * tree the previous one left.
     */
    suspend fun restore(
        relay: RelayConnection,
        config: WebDavConfig,
        name: String,
        cacheDir: File,
        concurrency: Int = 4,
        onProgress: (phase: String, done: Long, total: Long) -> Unit = { _, _, _ -> },
    ): Restored = transferMutex.withLock {
        withContext(Dispatchers.IO) {
            val index = WebDavClient.readIndex(config)
            var chain = index?.chainFor(name).orEmpty()
            if (chain.isEmpty()) {
                // Legacy archive or an index that predates this file: a full
                // archive is self-contained, an increment is not.
                if (name.startsWith(WebDavClient.INC_PREFIX)) {
                    throw IllegalStateException("缺少备份索引，无法恢复增量 $name")
                }
                chain = listOf(BackupEntry(name = name, kind = "full"))
            } else if (chain.first().kind != "full" || chain.last().name != name) {
                // A link in the middle is missing from the index (hand-deleted
                // or an interrupted prune). Applying what is left would produce
                // a tree that never existed.
                throw IllegalStateException(
                    "备份链不完整（${chain.joinToString { it.name }}），无法恢复 $name",
                )
            }
            val sizes = chain.associate { it.name to it.bytes }
            val files = WebDavClient.downloadMany(config, sizes, cacheDir, concurrency) { done, total ->
                onProgress("download", done, total)
            }
            var entries = 0
            var backupPath = ""
            var warning = ""
            var settingsFixed = 0
            try {
                chain.forEachIndexed { position, entry ->
                    val file = files[entry.name] ?: error("下载结果缺少 ${entry.name}")
                    val done = file.inputStream().use { input ->
                        relay.restoreBackup(
                            input,
                            entry.name,
                            file.length(),
                            // The first link stops the agents and writes the
                            // automatic backup; the rest only overwrite files.
                            skipSafetyBackup = position > 0,
                        ) { sent, total -> onProgress("restore", sent, total) }
                    }
                    entries += done.optInt("entries")
                    // Only the first link writes the automatic backup. Keep
                    // that path so "roll back" names a real file.
                    if (backupPath.isEmpty()) backupPath = done.optString("backup")
                    if (warning.isEmpty()) warning = done.optString("warning")
                    settingsFixed = maxOf(
                        settingsFixed,
                        done.optJSONArray("settingsFixed")?.length() ?: 0,
                    )
                }
            } finally {
                files.values.forEach { it.delete() }
            }
            AppLog.event("webdav", "restore ok: $name chain=${chain.size} entries=$entries")
            Restored(
                entries = entries,
                backup = backupPath,
                warning = warning,
                settingsFixed = settingsFixed,
                chain = chain.size,
            )
        }
    }
}
