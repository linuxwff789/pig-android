package com.linuxwff.pigapp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

internal class FakeRelay : RelayConnection {
    override var token: String = "test"
    private val channel = Channel<JSONObject>(Channel.UNLIMITED)
    override val events: Flow<JSONObject> = channel.receiveAsFlow()
    private val state = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = state
    override val isConnecting: Boolean = false
    val sent = mutableListOf<JSONObject>()

    override fun connect() {
        state.value = true
    }
    override fun send(obj: JSONObject) {
        sent += obj
    }
    override fun close() {
        state.value = false
    }
    override suspend fun exportBackup(
        out: OutputStream,
        includeSessions: Boolean,
        includeUi: Boolean,
        onProgress: (Long) -> Unit,
    ): JSONObject = JSONObject()
    override suspend fun restoreBackup(
        input: InputStream,
        name: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
    ): JSONObject = JSONObject()

    fun emit(obj: JSONObject) {
        channel.trySend(obj)
    }
    fun last(type: String): JSONObject? = sent.lastOrNull { it.optString("type") == type }
}

