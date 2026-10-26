package com.linuxwff.pigapp

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostic log, kept because the interesting failures happen where no
 * logcat is reachable: a relay that refuses `RUN_COMMAND`, a pig that dies while
 * loading an extension, a pack the app rejected. Android will not let an app
 * read logcat, and Termux's own output lives in another app, so this records
 * what *this* side sees - every frame in both directions, connection events,
 * install outcomes, pack parsing and errors - and can export it to
 * Download/Pig.
 *
 * Secrets are redacted before anything is stored: the relay token, api keys and
 * `Authorization` values never reach the buffer or the file.
 */
object AppLog {
    private const val MAX_ENTRIES = 6000
    private const val FRAME_CHARS = 2400
    private const val FILE_BYTES = 2L * 1024 * 1024

    private val entries = ArrayDeque<String>()
    private val facts = LinkedHashMap<String, String>()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private var sink: Writer? = null
    private var sinkFile: File? = null
    private var writes = 0

    private val secretFields = Regex(
        "\"(token|apiKey|api_key|password|secret|authorization)\"\\s*:\\s*\"[^\"]*\"",
        RegexOption.IGNORE_CASE,
    )
    private val tokenArgument = Regex("(--token\\s+)\\S+")
    private val keyLike = Regex("\\b(sk-|ghp_|github_pat_)[A-Za-z0-9_-]{8,}")

    /** Opens the on-disk tail. Safe to call more than once. */
    fun attach(context: Context) {
        synchronized(lock) {
            if (sinkFile != null) return
            runCatching {
                val dir = File(context.filesDir, "logs").apply { mkdirs() }
                val file = File(dir, "app.log")
                sinkFile = file
                sink = OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)
                writeLocked("app", "session start · app ${appVersion(context)}")
            }.onFailure { Log.w("AppLog", "attach failed", it) }
        }
    }

    fun event(tag: String, message: String) = write(tag, message)

    fun frame(direction: String, frame: JSONObject) {
        val type = frame.optString("type")
        // A pack push and a message history are megabytes of JSON; stringifying
        // them only to cut 2400 chars wastes the little memory this app has.
        when {
            // One line per streamed chunk and one per keepalive would drown
            // everything else; the final `message_end` carries the result.
            type == "relay_pong" || type == "message_update" -> Unit
            type == "relay_ui_pack" -> {
                val names = frame.optJSONObject("files")?.keys()
                    ?.asSequence()?.joinToString(",").orEmpty()
                write(
                    direction,
                    "relay_ui_pack hash=${frame.optString("hash").take(12)} files=[$names]",
                )
            }
            type == "response" && frame.optString("command") == "get_messages" -> {
                val count = frame.optJSONObject("data")?.optJSONArray("messages")?.length() ?: -1
                write(direction, "response get_messages id=${frame.optString("id")} n=$count")
            }
            else -> write(direction, redact(frame.toString()).take(FRAME_CHARS))
        }
    }

    fun frame(direction: String, line: String) =
        write(direction, redact(line).take(FRAME_CHARS))

    /** A stable property shown in the export header (relay address, pack, model...). */
    fun fact(key: String, value: String) {
        synchronized(lock) { facts[key] = value }
    }

    fun redact(text: String): String {
        var out = secretFields.replace(text) { match -> "\"${match.groupValues[1]}\":\"***\"" }
        out = tokenArgument.replace(out) { match -> "${match.groupValues[1]}***" }
        out = keyLike.replace(out, "***")
        return out
    }

    fun snapshot(): String = synchronized(lock) { entries.joinToString("\n") }

    fun suggestedName(): String =
        "pig-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

    /** Header + buffer, ready to write to a file. */
    fun exportText(context: Context): String {
        val runtime = Runtime.getRuntime()
        val header = buildString {
            appendLine("Pig App ${appVersion(context)}")
            appendLine(
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
            )
            synchronized(lock) {
                facts.forEach { (key, value) -> appendLine("$key: $value") }
            }
            appendLine(
                "memory: ${(runtime.totalMemory() - runtime.freeMemory()) / 1048576} MB used / " +
                    "${runtime.maxMemory() / 1048576} MB max",
            )
            appendLine(
                "exported: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            )
            appendLine("entries: ${entries.size}")
        }
        return header + "\n" + snapshot() + "\n"
    }

    private fun write(tag: String, message: String) {
        synchronized(lock) { writeLocked(tag, message) }
    }

    private fun writeLocked(tag: String, message: String) {
        // Redact here rather than at every call site: a prompt or an error
        // string can carry a key just as easily as a frame can.
        val line = "${stamp.format(Date())} [$tag] ${redact(message)}"
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        runCatching {
            sink?.apply {
                write(line)
                write("\n")
                flush()
            }
        }
        writes++
        if (writes % 500 == 0) rotateIfNeeded()
    }

    /** Called with [lock] held. */
    private fun rotateIfNeeded() {
        val file = sinkFile ?: return
        if (file.length() < FILE_BYTES) return
        runCatching { sink?.close() }
        val previous = File(file.parentFile, "app.1.log")
        previous.delete()
        file.renameTo(previous)
        sink = runCatching {
            OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.versionCode})"
    }.getOrNull().orEmpty()
}
