package com.linuxwff.pigapp

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.linuxwff.pigapp.ui.PigApp
import com.linuxwff.pigapp.ui.PigTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PigAppSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersAHistoryMessage() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val relay = FakeRelay()
        val vm = AppViewModel(app, relay)
        compose.setContent {
            PigTheme(vm.ui.uiPack) { PigApp(vm) }
        }
        relay.emit(JSONObject()
            .put("type", "relay_hello")
            .put("protocol", "relay/1")
            .put("version", "test")
            .put("features", JSONArray())
            .put("cwd", "/tmp")
            .put("uiDir", "")
            .put("pigPid", 0))
        relay.emit(JSONObject()
            .put("type", "response")
            .put("id", "")
            .put("command", "get_state")
            .put("success", true)
            .put("data", JSONObject()
                .put("sessionFile", "/a.jsonl")
                .put("sessionId", "A")
                .put("messageCount", 1)
                .put("isStreaming", false)
                .put("live", false)))

        compose.waitUntil(5_000) {
            relay.sent.any { it.optString("type") == "get_messages" }
        }
        val id = relay.last("get_messages")!!.optString("id")
        relay.emit(JSONObject()
            .put("type", "response")
            .put("id", id)
            .put("command", "get_messages")
            .put("success", true)
            .put("sessionPath", "/a.jsonl")
            .put("data", JSONObject()
                .put("messages", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("timestamp", "2026-10-30T00:00:00Z")
                    .put("content", JSONArray().put(JSONObject()
                        .put("type", "text")
                        .put("text", "hello from history")))))
                .put("historyStart", 0)
                .put("historyTotal", 1)))

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("hello from history").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("hello from history").assertIsDisplayed()
    }
}
