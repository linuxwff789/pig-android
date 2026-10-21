package com.linuxwff.pigapp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val token: String,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var job: Job? = null

    private val _events = MutableSharedFlow<JSONObject>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<JSONObject> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun connect() {
        if (job?.isActive == true) return
        job = scope.launch {
            var lastError: Exception? = null
            repeat(24) { attempt ->
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(host, port), 1500)
                    s.soTimeout = 0
                    socket = s
                    writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                    sendNow(JSONObject().put("type", "relay_auth").put("token", token))
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    _connected.value = true
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        _events.tryEmit(obj)
                    }
                } catch (e: Exception) {
                    lastError = e
                } finally {
                    _connected.value = false
                    runCatching { socket?.close() }
                    socket = null
                    writer = null
                }
                if (attempt < 23) delay(500)
            }
            lastError?.let {
                _events.tryEmit(
                    JSONObject()
                        .put("type", "relay_client_error")
                        .put("message", it.message ?: it.javaClass.simpleName),
                )
            }
        }
    }

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
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        _connected.value = false
    }
}
