package com.linuxwff.pigapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Debug-build only. Drive the app from a shell:
 *
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_STATE
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_PROMPT --es text "hello"
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_CONNECT
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_ABORT
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_SWITCH --es path /path/to/session.jsonl
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_WINDOW --es json '{"id":"demo",...}'
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_CLOSE_WINDOW --es id demo
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_FORM --es window demo --es field name --es value hello
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_WINDOW_ACTION --es window demo --es action ext:uitest.submit
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_EVENT --es json '{"type":"extension_ui_request","method":"setWidget",...}'
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_SET_MODEL --es provider deepseek --es model deepseek-flash
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_THINKING --es level high
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_CONTEXT --es provider eu --es model foo --ei tokens 128000
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_PACK --es id <packId|"">
 *
 * Exported without a permission on purpose; this class is only compiled into
 * debug builds (app/src/debug).
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val vm = AppViewModel.current
        if (vm == null) {
            Log.w(TAG, "no live AppViewModel for ${intent.action}")
            return
        }
        when (intent.action) {
            "com.linuxwff.pigapp.DEBUG_CONNECT" -> {
                Log.i(TAG, "reconnect requested")
                vm.reconnect()
            }
            "com.linuxwff.pigapp.DEBUG_PROMPT" -> {
                val text = intent.getStringExtra("text") ?: ""
                Log.i(TAG, "prompt: $text")
                vm.sendPrompt(text)
            }
            "com.linuxwff.pigapp.DEBUG_ABORT" -> vm.abort()
            "com.linuxwff.pigapp.DEBUG_SWITCH_CONFIRM" -> vm.confirmSwitch()
            "com.linuxwff.pigapp.DEBUG_SWITCH_CANCEL" -> vm.cancelSwitch()
            "com.linuxwff.pigapp.DEBUG_WINDOW" -> {
                val json = intent.getStringExtra("json") ?: ""
                Log.i(TAG, "inject window: ${json.take(120)}")
                vm.injectWindow(json)
            }
            "com.linuxwff.pigapp.DEBUG_CLOSE_WINDOW" -> {
                val id = intent.getStringExtra("id") ?: ""
                Log.i(TAG, "close window: $id")
                vm.closeWindow(id)
            }
            "com.linuxwff.pigapp.DEBUG_FORM" -> {
                vm.setFormValue(
                    intent.getStringExtra("window") ?: "",
                    intent.getStringExtra("field") ?: "",
                    intent.getStringExtra("value") ?: "",
                )
            }
            "com.linuxwff.pigapp.DEBUG_WINDOW_ACTION" -> {
                val windowId = intent.getStringExtra("window") ?: ""
                val action = intent.getStringExtra("action") ?: ""
                Log.i(TAG, "window action: $windowId $action")
                vm.handleWindowAction(windowId, action)
            }
            "com.linuxwff.pigapp.DEBUG_OPEN_WINDOW" -> {
                val id = intent.getStringExtra("id") ?: ""
                Log.i(TAG, "open window: $id")
                vm.openWindowById(id)
            }
            "com.linuxwff.pigapp.DEBUG_NAV" -> {
                val id = intent.getStringExtra("id") ?: ""
                Log.i(TAG, "nav: $id")
                val item = vm.ui.uiPack.nav.firstOrNull { it.id == id }
                if (item != null) vm.openNavItem(item) else Log.w(TAG, "no nav item $id")
            }
            "com.linuxwff.pigapp.DEBUG_MODELS" -> vm.openModelPicker()
            "com.linuxwff.pigapp.DEBUG_SET_MODEL" -> {
                val provider = intent.getStringExtra("provider") ?: ""
                val model = intent.getStringExtra("model") ?: ""
                Log.i(TAG, "set model: $provider/$model")
                vm.selectModelById(provider, model)
            }
            "com.linuxwff.pigapp.DEBUG_PACK" -> {
                val id = intent.getStringExtra("id") ?: ""
                Log.i(TAG, "select pack: '$id'")
                vm.refreshLocalPacks()
                vm.selectPack(id)
            }
            "com.linuxwff.pigapp.DEBUG_CONTEXT" -> {
                val provider = intent.getStringExtra("provider") ?: ""
                val model = intent.getStringExtra("model") ?: ""
                val tokens = intent.getIntExtra("tokens", 0)
                Log.i(TAG, "context window: $provider/$model = $tokens")
                vm.setContextWindow(provider, model, tokens)
            }
            "com.linuxwff.pigapp.DEBUG_THINKING" -> {
                val level = intent.getStringExtra("level") ?: ""
                Log.i(TAG, "thinking: $level")
                vm.setThinkingLevel(level)
            }
            "com.linuxwff.pigapp.DEBUG_NOTIF_CLEAR" -> {
                vm.clearNotifications()
                Log.i(TAG, "notifications cleared")
            }
            "com.linuxwff.pigapp.DEBUG_PLAN" -> {
                // Render one sample plan widget through the same path a real
                // extension uses, and log what the parser made of it.
                val form = intent.getStringExtra("form") ?: "json"
                if (form == "dismiss") {
                    vm.dismissWidget("plan")
                    Log.i(TAG, "plan widget dismissed")
                    return
                }
                if (form == "clear") {
                    Log.i(TAG, "plan widget cleared")
                    vm.handleDebugEvent(
                        org.json.JSONObject()
                            .put("type", "extension_ui_request")
                            .put("method", "setWidget")
                            .put("widgetKey", "plan")
                            .put("widgetLines", org.json.JSONArray())
                            .toString(),
                    )
                    return
                }
                val lines = if (form == "text") {
                    listOf(
                        "Plan: 让 pin 立刻生效",
                        "2/3",
                        "☑ 查清 models.json 的读取时机",
                        "☑ 加 relay_restart",
                        "☐ 写文档",
                    )
                } else {
                    listOf(
                        """{"plan":{"title":"让 pin 立刻生效","steps":["查清 models.json 的读取时机","加 relay_restart","写文档"]}}""",
                    )
                }
                com.linuxwff.pigapp.ui.parsePlanWidget("plan", lines)?.let {
                    Log.i(TAG, "plan parse: title=${it.title} steps=${it.steps}")
                } ?: Log.w(TAG, "plan parse: not a plan widget")
                val widgets = org.json.JSONArray()
                lines.forEach { widgets.put(it) }
                vm.handleDebugEvent(
                    org.json.JSONObject()
                        .put("type", "extension_ui_request")
                        .put("method", "setWidget")
                        .put("widgetKey", "plan")
                        .put("widgetLines", widgets)
                        .put("widgetPlacement", "aboveEditor")
                        .toString(),
                )
            }
            "com.linuxwff.pigapp.DEBUG_EVENT" -> {
                val json = intent.getStringExtra("json") ?: ""
                Log.i(TAG, "event: ${json.take(120)}")
                vm.handleDebugEvent(json)
            }
            "com.linuxwff.pigapp.DEBUG_SWITCH" -> {
                val path = intent.getStringExtra("path") ?: ""
                Log.i(TAG, "switch session: $path")
                vm.switchSession(path)
            }
            "com.linuxwff.pigapp.DEBUG_ANSWER" -> {
                vm.answer(
                    value = intent.getStringExtra("value"),
                    confirmed = if (intent.hasExtra("confirmed")) intent.getBooleanExtra("confirmed", false) else null,
                    cancelled = intent.getBooleanExtra("cancelled", false),
                )
            }
            "com.linuxwff.pigapp.DEBUG_STATE" -> {
                // Never dump the whole transcript through logcat: on a long
                // session the multi-hundred-KB string blocks the main thread
                // and triggers an ANR. Log a summary and write the full state
                // file off the main thread.
                val state = vm.ui
                val summary = "connected=${state.connected} busy=${state.busy} " +
                    "model=${state.model} sessionId=${state.sessionId} " +
                    "messages=${state.messages.size} context=${state.contextPercent}% " +
                    "used=${state.contextTokens}/${state.contextWindow} " +
                    "actualWindow=${state.contextWindowActual} " +
                    "pendingRestart=${state.pendingAgentRestart} " +
                    "extStatuses=${state.extensionStatuses} " +
                    "pack=${state.activePackId.ifBlank { "builtin" }} " +
                    "packName=${state.uiPack.name} localPacks=${state.localPacks.size} " +
                    "windows=${state.windows.size} windowError=${state.windowError} " +
                    "error=${state.error} toast=${state.toast}"
                Log.i(TAG, summary)
                setResultData(summary)
                Thread {
                    runCatching {
                        val dir = context.getExternalFilesDir(null) ?: context.filesDir
                        File(dir, "state.txt").writeText(state.toString())
                    }.onFailure { Log.w(TAG, "state file: ${it.message}") }
                }.start()
            }
            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "PigDebug"
    }
}
