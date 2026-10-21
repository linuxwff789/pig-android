package com.linuxwff.pigapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-build only. Drive the app from a shell:
 *
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_STATE
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_PROMPT --es text "hello"
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_CONNECT
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_ABORT
 *   am broadcast -a com.linuxwff.pigapp.DEBUG_SWITCH --es path /path/to/session.jsonl
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
            "com.linuxwff.pigapp.DEBUG_STATE" -> Log.i(TAG, vm.ui.toString())
            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "PigDebug"
    }
}
