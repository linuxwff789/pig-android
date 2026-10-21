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
            "com.linuxwff.pigapp.DEBUG_MODELS" -> vm.openModelPicker()
            "com.linuxwff.pigapp.DEBUG_SET_MODEL" -> {
                val provider = intent.getStringExtra("provider") ?: ""
                val model = intent.getStringExtra("model") ?: ""
                Log.i(TAG, "set model: $provider/$model")
                vm.selectModelById(provider, model)
            }
            "com.linuxwff.pigapp.DEBUG_THINKING" -> {
                val level = intent.getStringExtra("level") ?: ""
                Log.i(TAG, "thinking: $level")
                vm.setThinkingLevel(level)
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
                val text = vm.ui.toString()
                Log.i(TAG, text)
                setResultData(text)
                runCatching {
                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                    File(dir, "state.txt").writeText(text)
                }.onFailure { Log.w(TAG, "state file: ${it.message}") }
            }
            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "PigDebug"
    }
}
