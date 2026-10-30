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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

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

    private fun historyWith(
        id: String,
        file: String,
        start: Int,
        total: Int,
        vararg messages: JSONObject,
    ): JSONObject {
        val array = JSONArray()
        messages.forEach { array.put(it) }
        return JSONObject()
            .put("type", "response")
            .put("id", id)
            .put("command", "get_messages")
            .put("success", true)
            .put("sessionPath", file)
            .put("data", JSONObject()
                .put("messages", array)
                .put("historyStart", start)
                .put("historyTotal", total))
    }

    private fun message(role: String, text: String, timestamp: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("timestamp", timestamp)
            .put("content", JSONArray().put(JSONObject()
                .put("type", "text")
                .put("text", text)))

    @Test
    fun showsHistorySpinnerWhileSwitching() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val relay = FakeRelay()
        val vm = AppViewModel(app, relay)
        compose.setContent { PigTheme(vm.ui.uiPack) { PigApp(vm) } }
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
        compose.waitUntil(5_000) { relay.sent.any { it.optString("type") == "get_messages" } }
        val first = relay.last("get_messages")!!
        relay.emit(historyWith(first.optString("id"), "/a.jsonl", 0, 1,
            message("user", "first", "2026-10-30T00:00:00Z")))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("first").fetchSemanticsNodes().isNotEmpty()
        }

        vm.switchSession("/b.jsonl")
        val switch = relay.last("switch_session")!!
        relay.emit(JSONObject()
            .put("type", "response")
            .put("id", switch.optString("id"))
            .put("command", "switch_session")
            .put("success", true))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("正在读取历史会话…").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun placesNoticeBetweenItsTurn() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val base = Instant.parse("2026-10-30T00:00:00Z").toEpochMilli()
        val dir = File(app.filesDir, "notices")
        dir.mkdirs()
        File(dir, "c.jsonl").writeText(
            JSONObject().put("text", "old error").put("ts", base + 500).toString() + "\n",
        )
        val relay = FakeRelay()
        val vm = AppViewModel(app, relay)
        compose.setContent { PigTheme(vm.ui.uiPack) { PigApp(vm) } }
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
                .put("sessionFile", "/c.jsonl")
                .put("sessionId", "C")
                .put("messageCount", 2)
                .put("isStreaming", false)
                .put("live", false)))
        compose.waitUntil(5_000) { relay.sent.any { it.optString("type") == "get_messages" } }
        val id = relay.last("get_messages")!!.optString("id")
        relay.emit(historyWith(id, "/c.jsonl", 0, 2,
            message("user", "question", "2026-10-30T00:00:00Z"),
            message("assistant", "assistant reply", "2026-10-30T00:00:01Z")))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("old error").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("assistant reply").fetchSemanticsNodes().isNotEmpty()
        }
        val question = compose.onNodeWithText("question").fetchSemanticsNode().boundsInRoot.top
        val notice = compose.onNodeWithText("old error").fetchSemanticsNode().boundsInRoot.top
        val reply = compose.onNodeWithText("assistant reply").fetchSemanticsNode().boundsInRoot.top
        assertTrue("notice should follow its question", question < notice)
        assertTrue("notice should precede the answer", notice < reply)
    }
}
