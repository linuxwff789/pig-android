package com.linuxwff.pigapp

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * Line-delimited JSON over loopback TCP.
 *
 * The relay speaks the same framing as `pig --mode rpc`: one JSON object per
 * line. Pig's own frames pass through untouched; relay control frames use the
 * `relay_` type prefix.
 */
class RelayClient(
    private val host: String,
    private val port: Int,
    token: String,
    /**
     * When false the client connects once and stops. A background worker must
     * not keep retrying: the relay accepts one client, so a retry loop here
     * would kick the app, which reconnects and kicks the worker, forever.
     */
    private val autoReconnect: Boolean = true,
) {
    /**
     * Shared secret for `relay_auth`. Mutable because an app-data restore can
     * leave the in-memory copy behind the one stored in prefs; the ViewModel
     * swaps it and reconnects instead of showing a dead relay.
     */
    @Volatile
    var token: String = token
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Frames must reach the socket in the order they were produced
    // (clear_queue before the re-queued messages, prompt before get_state).
    // Dispatchers.IO is multi-threaded, so send() used to race itself.
    private val sendScope =
        CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var job: Job? = null
    private var generation = 0

    // A channel buffers events until the ViewModel collector is scheduled.
    // SharedFlow without replay dropped the relay hello/pack when the
    // collector started a few milliseconds late.
    private val eventChannel = Channel<JSONObject>(Channel.UNLIMITED)
    val events: Flow<JSONObject> = eventChannel.receiveAsFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // ---- Backup/restore transfers ----------------------------------------
    // The relay streams a gzipped tar as base64 chunks. They are intercepted
    // here instead of going through `events`: a 65 MB session tree would flood
    // the ViewModel's state updates and the diagnostic log. Only one transfer
    // runs at a time, on the same connection the transcript uses.
    private val transferMutex = Mutex()
    private var exportWrite: ((ByteArray) -> Unit)? = null
    private var exportProgress: ((Long) -> Unit)? = null
    private var exportSent = 0L
    private var exportDone: CompletableDeferred<JSONObject>? = null
    private var restoreReady: CompletableDeferred<JSONObject>? = null
    private var restoreDone: CompletableDeferred<JSONObject>? = null
    /** id of the transfer that owns the fields above; late frames are dropped. */
    @Volatile
    private var activeTransferId: String? = null

    fun connect() {
        if (job?.isActive == true) return
        AppLog.event("relay", "connecting to $host:$port")
        val gen = ++generation
        job = scope.launch {
            var lastError: Exception? = null
            var attempt = 0
            var everConnected = false
            while (isActive && gen == generation) {
                var s: Socket? = null
                try {
                    s = Socket()
                    s.connect(InetSocketAddress(host, port), 1500)
                    if (gen != generation) return@launch
                    AppLog.event("relay", "tcp connected")
                    s.soTimeout = 0
                    socket = s
                    writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                    sendNow(JSONObject().put("type", "relay_auth").put("token", token))
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    while (isActive && gen == generation) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val obj = runCatching { JSONObject(line) }.getOrNull()
                        if (obj == null) {
                            AppLog.frame("<-?", line)
                            continue
                        }
                        // A bad token still gets a TCP connect; only the relay
                        // hello proves the handshake succeeded.
                        if (obj.optString("type") == "relay_hello") {
                            _connected.value = true
                            everConnected = true
                            attempt = 0
                        }
                        if (handleTransferFrame(obj)) continue
                        AppLog.frame("<-", obj)
                        eventChannel.trySend(obj)
                    }
                } catch (e: Exception) {
                    lastError = e
                    // Retries are silent by design; keep the first failure (and
                    // then every twentieth) so an export still shows why.
                    if (attempt < 1 || attempt % 20 == 0) {
                        AppLog.event(
                            "relay",
                            "socket error #${attempt + 1}: ${e.javaClass.simpleName}: ${e.message}",
                        )
                    }
                } finally {
                    // Only the current generation may touch the shared socket
                    // or flip the connected flag; an older connect attempt must
                    // not kill the replacement socket.
                    if (gen == generation) {
                        _connected.value = false
                        if (socket === s) {
                            socket = null
                            writer = null
                        }
                        // Waiting for a chunk/done frame that can never arrive
                        // used to hang for the full 10 min timeout while
                        // holding transferMutex and maintenanceBusy.
                        failTransfers("连接已断开")
                    }
                    runCatching { s?.close() }
                }
                if (gen != generation) return@launch
                if (!autoReconnect) return@launch
                attempt++
                // Show the setup hint only when the relay never came up; a
                // dropped connection after a successful handshake is retried
                // silently by the ViewModel's auto-reconnect loop.
                if (attempt == 24 && !everConnected) {
                    val error = lastError
                    eventChannel.trySend(
                        JSONObject()
                            .put("type", "relay_client_error")
                            .put("message", error?.message ?: error?.javaClass?.simpleName ?: "connect failed"),
                    )
                }
                val backoff = when {
                    attempt < 10 -> 500L
                    attempt < 40 -> 1500L
                    else -> 3000L
                }
                delay(backoff)
            }
        }
    }

    /**
     * Streams a backup of `~/.pig` into [out]. Returns the relay's
     * `relay_backup_done` frame (name, checksum, entries).
     */
    suspend fun exportBackup(
        out: OutputStream,
        includeSessions: Boolean,
        includeUi: Boolean,
        onProgress: (sent: Long) -> Unit,
    ): JSONObject = transferMutex.withLock {
        val id = "backup-${System.nanoTime()}"
        val done = CompletableDeferred<JSONObject>()
        activeTransferId = id
        exportDone = done
        exportWrite = { bytes -> out.write(bytes) }
        exportProgress = onProgress
        exportSent = 0
        try {
            if (!sendNow(
                JSONObject()
                    .put("type", "relay_backup")
                    .put("id", id)
                    .put("sessions", includeSessions)
                    .put("ui", includeUi),
            )
            ) {
                error("连接已断开")
            }
            val result = withTimeout(TRANSFER_TIMEOUT_MS) { done.await() }
            if (result.optString("type") == "relay_backup_error") {
                error(result.optString("message", "backup failed"))
            }
            result
        } catch (e: Exception) {
            // Stop the relay's copy loop; it holds a writer goroutine until the
            // socket goes away otherwise.
            runCatching { sendNow(JSONObject().put("type", "relay_backup_cancel").put("id", id)) }
            throw e
        } finally {
            exportDone = null
            exportWrite = null
            exportProgress = null
            if (activeTransferId == id) activeTransferId = null
        }
    }

    /**
     * Uploads [input] as a restore archive. [size] may be -1 when the content
     * resolver does not know it; the relay then only enforces the hard limit.
     */
    suspend fun restoreBackup(
        input: InputStream,
        name: String,
        size: Long,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): JSONObject = transferMutex.withLock {
        val id = "restore-${System.nanoTime()}"
        val ready = CompletableDeferred<JSONObject>()
        val done = CompletableDeferred<JSONObject>()
        activeTransferId = id
        restoreReady = ready
        restoreDone = done
        try {
            if (!sendNow(
                JSONObject()
                    .put("type", "relay_restore_begin")
                    .put("id", id)
                    .put("name", name)
                    .put("size", size),
            )
            ) {
                error("连接已断开")
            }
            val accepted = withTimeout(READY_TIMEOUT_MS) { ready.await() }
            if (accepted.optString("type") == "relay_restore_error") {
                error(accepted.optString("message", "restore rejected"))
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CHUNK_BYTES)
            var sent = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val queued = sendNow(
                    JSONObject()
                        .put("type", "relay_restore_chunk")
                        .put("id", id)
                        .put("data", Base64.getEncoder().encodeToString(chunk)),
                )
                if (!queued) error("连接已断开")
                sent += read
                onProgress(sent, size)
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sendNow(
                JSONObject()
                    .put("type", "relay_restore_end")
                    .put("id", id)
                    .put("sha256", sha),
            )) {
                error("连接已断开")
            }
            val result = withTimeout(TRANSFER_TIMEOUT_MS) { done.await() }
            if (result.optString("type") == "relay_restore_error") {
                error(result.optString("message", "restore failed"))
            }
            result
        } catch (e: Exception) {
            // A failed/timed-out upload leaves the relay's transfer.busy set;
            // it only clears when the client goes away. Force a reconnect so
            // the next backup is not refused with "another transfer is running".
            runCatching { close() }
            throw e
        } finally {
            restoreReady = null
            restoreDone = null
            if (activeTransferId == id) activeTransferId = null
        }
    }

    /** Intercepts backup/restore frames so they never reach `events`. */
    private fun handleTransferFrame(obj: JSONObject): Boolean {
        val type = obj.optString("type")
        if (type.startsWith("relay_backup") || type.startsWith("relay_restore")) {
            // A transfer that timed out or was cancelled can still have frames
            // in flight. Appending them to the next archive produced a
            // truncated file reported as success.
            val id = obj.optString("id")
            val mine = activeTransferId
            if (mine == null || (id.isNotBlank() && id != mine)) return true
        }
        when (type) {
            "relay_backup_start" -> return true
            "relay_backup_chunk" -> {
                val bytes = try {
                    Base64.getDecoder().decode(obj.optString("data"))
                } catch (e: IllegalArgumentException) {
                    exportDone?.completeExceptionally(IOException("bad backup chunk: ${e.message}"))
                    runCatching { sendNow(JSONObject().put("type", "relay_backup_cancel")) }
                    return true
                }
                try {
                    exportWrite?.invoke(bytes)
                    exportSent += bytes.size
                    exportProgress?.invoke(exportSent)
                } catch (e: Exception) {
                    exportDone?.completeExceptionally(
                        IOException("cannot write backup: ${e.message ?: e.toString()}"),
                    )
                    runCatching { sendNow(JSONObject().put("type", "relay_backup_cancel")) }
                }
                return true
            }
            "relay_backup_done", "relay_backup_error" -> {
                val done = exportDone ?: return false
                done.complete(obj)
                return true
            }
            "relay_restore_ready" -> {
                val ready = restoreReady ?: return false
                ready.complete(obj)
                return true
            }
            "relay_restore_done", "relay_restore_error" -> {
                val done = restoreDone ?: return false
                done.complete(obj)
                return true
            }
        }
        return false
    }

    val isConnecting: Boolean get() = job?.isActive == true

    fun send(obj: JSONObject) {
        sendScope.launch { sendNow(obj) }
    }

    private companion object {
        const val CHUNK_BYTES = 48 * 1024
        const val READY_TIMEOUT_MS = 60_000L
        const val TRANSFER_TIMEOUT_MS = 10 * 60_000L
    }

    @Synchronized
    private fun sendNow(obj: JSONObject): Boolean {
        val w = writer
        if (w == null) {
            AppLog.event("relay", "drop (not connected): ${obj.optString("type")}")
            return false
        }
        AppLog.frame("->", obj)
        return try {
            w.write(obj.toString())
            w.write("\n")
            w.flush()
            true
        } catch (e: Exception) {
            Log.w("RelayClient", "send failed", e)
            false
        }
    }

    /** Wakes every transfer waiting for a frame that cannot arrive anymore. */
    private fun failTransfers(reason: String) {
        val error = IOException(reason)
        exportDone?.completeExceptionally(error)
        restoreReady?.completeExceptionally(error)
        restoreDone?.completeExceptionally(error)
    }

    fun close() {
        AppLog.event("relay", "close requested")
        // A transfer waiting for its completion frame must not hang for the
        // full timeout when the socket goes away under it.
        failTransfers("连接已断开")
        generation++
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        writer = null
        _connected.value = false
    }
}
