package com.linuxwff.pigapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Termux answers every `RUN_COMMAND` it accepts or rejects through the
 * `com.termux.RUN_COMMAND_PENDING_INTENT` extra. Without that extra the app is
 * blind: `startService()` succeeds even when Termux refuses to run anything
 * (a fresh install has `allow-external-apps=false`), so the setup dialog used
 * to sit there until a generic "connection refused" showed up minutes later.
 */
class RunCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLog.attach(context.applicationContext)
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        // Termux puts everything in one nested bundle: stdout/stderr/exitCode/
        // err/errmsg of the execution command.
        val result = intent.getBundleExtra(EXTRA_RESULT_BUNDLE) ?: Bundle()
        val exitCode = if (result.containsKey(EXTRA_EXIT_CODE)) {
            result.getInt(EXTRA_EXIT_CODE)
        } else {
            null
        }
        val outcome = RunCommandOutcome(
            kind = kind,
            exitCode = exitCode,
            stdout = result.getString(EXTRA_STDOUT).orEmpty(),
            stderr = result.getString(EXTRA_STDERR).orEmpty(),
            errmsg = result.getString(EXTRA_ERRMSG).orEmpty(),
        )
        Log.i("RunCommandReceiver", "kind=$kind exit=$exitCode errmsg=${outcome.errmsg.take(200)}")
        AppLog.event(
            "termux",
            "result kind=$kind exit=$exitCode errmsg=${outcome.errmsg.take(400)} " +
                "stdout=${outcome.stdout.take(400)} stderr=${outcome.stderr.take(600)}",
        )
        RunCommandFeedback.emit(outcome)
    }

    companion object {
        const val ACTION_RESULT = "com.linuxwff.pigapp.RUN_COMMAND_RESULT"
        const val EXTRA_KIND = "kind"

        const val KIND_BOOTSTRAP = "bootstrap"
        const val KIND_RELAY = "relay"
        const val KIND_RESTART = "restart"
        const val KIND_UNINSTALL = "uninstall"

        // TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE / TERMUX_SERVICE extras.
        private const val EXTRA_RESULT_BUNDLE = "result"
        private const val EXTRA_STDOUT = "stdout"
        private const val EXTRA_STDERR = "stderr"
        private const val EXTRA_EXIT_CODE = "exitCode"
        private const val EXTRA_ERRMSG = "errmsg"

        const val REQUEST_BOOTSTRAP = 1
        const val REQUEST_RELAY = 2
        const val REQUEST_RESTART = 3
        const val REQUEST_UNINSTALL = 4
    }
}

data class RunCommandOutcome(
    val kind: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val errmsg: String,
) {
    /** Termux refused before running anything: `allow-external-apps` is off. */
    val blockedByTermux: Boolean
        get() = errmsg.contains("allow-external-apps", ignoreCase = true)

    /** `exec` never found the binary — the Termux side is not installed yet. */
    val missingBinary: Boolean
        get() = stderr.contains("not found", ignoreCase = true) ||
            stderr.contains("No such file", ignoreCase = true) ||
            errmsg.contains("not found", ignoreCase = true)

    val failed: Boolean
        get() = errmsg.isNotBlank() || (exitCode != null && exitCode != 0)

    /** Short, user-facing reason: Termux's message first, then the tail of stderr. */
    fun summary(): String = when {
        errmsg.isNotBlank() -> errmsg.trim()
        stderr.isNotBlank() -> stderr.trim().takeLast(600)
        exitCode != null -> "exit code $exitCode"
        else -> ""
    }
}

/** Fan-out for [RunCommandReceiver]; collected by `AppViewModel`. */
object RunCommandFeedback {
    private val _outcomes = MutableSharedFlow<RunCommandOutcome>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val outcomes = _outcomes.asSharedFlow()

    fun emit(outcome: RunCommandOutcome) {
        _outcomes.tryEmit(outcome)
    }
}
