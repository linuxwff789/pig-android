package com.linuxwff.pigapp

import android.util.Log
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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

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
) {
    /**
     * Shared secret for `relay_auth`. Mutable because an app-data restore can
     * leave the in-memory copy behind the one stored in prefs; the ViewModel
     * swaps it and reconnects instead of showing a dead relay.
     */
    @Volatile
    var token: String = token
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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

    fun connect() {
        if (job?.isActive == true) return
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
                    s.soTimeout = 0
                    socket = s
                    writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                    sendNow(JSONObject().put("type", "relay_auth").put("token", token))
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    _connected.value = true
                    everConnected = true
                    attempt = 0
                    while (isActive && gen == generation) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        eventChannel.trySend(obj)
                    }
                } catch (e: Exception) {
                    lastError = e
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
                    }
                    runCatching { s?.close() }
                }
                if (gen != generation) return@launch
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

    val isConnecting: Boolean get() = job?.isActive == true

    fun send(obj: JSONObject) {
        scope.launch { sendNow(obj) }
    }

    @Synchronized
    private fun sendNow(obj: JSONObject) {
        val w = writer ?: return
        try {
            w.write(obj.toString())
            w.write("\n")
            w.flush()
        } catch (e: Exception) {
            Log.w("RelayClient", "send failed", e)
        }
    }

    fun close() {
        generation++
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        writer = null
        _connected.value = false
    }
}
