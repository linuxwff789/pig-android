package com.linuxwff.pigapp

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppViewModelSessionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun idleUntil(predicate: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue("condition not reached", predicate())
    }

    private fun state(id: String, file: String, session: String, count: Int): JSONObject =
        JSONObject()
            .put("type", "response")
            .put("id", id)
            .put("command", "get_state")
            .put("success", true)
            .put("data", JSONObject()
                .put("sessionFile", file)
                .put("sessionId", session)
                .put("messageCount", count)
                .put("isStreaming", false)
                .put("live", false))

    private fun history(id: String, file: String): JSONObject =
        historyPage(id, file, start = 0, total = 0, text = "")

    private fun historyPage(
        id: String,
        file: String,
        start: Int,
        total: Int,
        text: String,
    ): JSONObject {
        val messages = JSONArray()
        if (text.isNotBlank()) {
            messages.put(JSONObject()
                .put("role", "user")
                .put("timestamp", "2026-10-30T00:00:00Z")
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "text")
                    .put("text", text))))
        }
        return JSONObject()
            .put("type", "response")
            .put("id", id)
            .put("command", "get_messages")
            .put("success", true)
            .put("sessionPath", file)
            .put("data", JSONObject()
                .put("messages", messages)
                .put("historyStart", start)
                .put("historyTotal", total))
    }

    @Test
    fun switchConfirmationDoesNotStrandTheHistoryRequest() {
        val relay = FakeRelay()
        val vm = AppViewModel(app, relay)

        // The constructor collects frames; no connect needed to drive state.
        relay.emit(state("", "/a.jsonl", "A", 1))
        idleUntil { vm.ui.sessionFile == "/a.jsonl" }

        vm.switchSession("/b.jsonl")
        val switch = relay.last("switch_session")!!
        relay.emit(JSONObject()
            .put("type", "response")
            .put("id", switch.optString("id"))
            .put("command", "switch_session")
            .put("success", true))
        idleUntil { vm.ui.historyLoading }

        val targetState = relay.last("get_state")!!.optString("id")
        val targetMsgs = relay.last("get_messages")!!.optString("id")
        relay.emit(state(targetState, "/b.jsonl", "B", 0))
        idleUntil { vm.ui.sessionFile == "/b.jsonl" }
        relay.emit(history(targetMsgs, "/b.jsonl"))

        idleUntil { !vm.ui.historyLoading }
        assertFalse(vm.ui.historyLoading)
    }

    @Test
    fun newSessionSettlesOnTheFreshFile() {
        val relay = FakeRelay()
        val vm = AppViewModel(app, relay)
        relay.emit(state("", "/a.jsonl", "A", 1))
        idleUntil { vm.ui.sessionFile == "/a.jsonl" }

        vm.newSession()
        val request = relay.last("new_session")!!
        relay.emit(JSONObject()
            .put("type", "response")
            .put("id", request.optString("id"))
            .put("command", "new_session")
            .put("success", true))
        val afterNew = relay.last("get_state")?.optString("id").orEmpty()
        relay.emit(state(afterNew, "/b.jsonl", "B", 0))

        idleUntil { vm.ui.sessionFile == "/b.jsonl" }
        assertFalse(vm.ui.historyLoading)
    }

    @Test
    fun restoreRequestsTheRememberedSession() {
        app.getSharedPreferences("pig", android.content.Context.MODE_PRIVATE).edit()
            .putString("lastSessionFile", "/saved.jsonl")
            .commit()
        val relay = FakeRelay()
        val vm = AppViewModel(app, relay)
        relay.emit(state("", "/old.jsonl", "old", 1))

        idleUntil {
            relay.sent.any {
                it.optString("type") == "switch_session" &&
                    it.optString("sessionPath") == "/saved.jsonl"
            }
        }
        assertTrue(vm.ui.status.isEmpty() || vm.ui.status == "restoring")
    }

    @Test
    fun olderHistoryPullsTwoHundredAtATime() {
        val relay = FakeRelay()
        val vm = AppViewModel(app, relay)
        relay.emit(state("", "/a.jsonl", "A", 1000))
        idleUntil { vm.ui.sessionFile == "/a.jsonl" }
        vm.reloadHistory()
        idleUntil { relay.last("get_messages") != null }

        var request = relay.last("get_messages")!!
        assertTrue(request.optInt("limit") == 200)
        relay.emit(historyPage(request.optString("id"), "/a.jsonl", 800, 1000, "m200"))
        idleUntil { !vm.ui.historyLoading }

        val limits = mutableListOf(200)
        for (expected in listOf(400, 600, 800, 1000)) {
            vm.loadOlderHistory()
            idleUntil {
                val last = relay.last("get_messages")
                last != null && last.optString("id") != request.optString("id")
            }
            request = relay.last("get_messages")!!
            limits += request.optInt("limit")
            assertEquals(expected, request.optInt("limit"))
            val start = (1000 - expected).coerceAtLeast(0)
            relay.emit(historyPage(request.optString("id"), "/a.jsonl", start, 1000, "m$expected"))
            idleUntil { !vm.ui.historyLoading }
        }
        assertEquals(listOf(200, 400, 600, 800, 1000), limits)
        assertFalse(vm.ui.historyHasOlder)

        // At the start of the transcript there is nothing more to request.
        vm.loadOlderHistory()
        idleUntil { true }
        assertEquals(request.optString("id"), relay.last("get_messages")!!.optString("id"))
    }
}
