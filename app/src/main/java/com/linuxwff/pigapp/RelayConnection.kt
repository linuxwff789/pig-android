package com.linuxwff.pigapp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * The part of [RelayClient] the ViewModel uses. Small on purpose: tests can
 * drive session/history state without opening a loopback socket, while the
 * production client still owns reconnects and backup transfer framing.
 */
interface RelayConnection {
    var token: String
    val events: Flow<JSONObject>
    val connected: StateFlow<Boolean>
    val isConnecting: Boolean

    fun connect()
    fun send(obj: JSONObject)
    fun close()

    suspend fun exportBackup(
        out: OutputStream,
        includeSessions: Boolean,
        includeUi: Boolean,
        onProgress: (sent: Long) -> Unit,
    ): JSONObject

    suspend fun restoreBackup(
        input: InputStream,
        name: String,
        size: Long,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): JSONObject
}
