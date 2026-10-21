package com.linuxwff.pigapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

object RelayLauncher {
    private const val TERMUX_PKG = "com.termux"
    private const val RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    private const val RELAY_BIN = "/data/data/com.termux/files/usr/bin/pig-relay"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    fun isTermuxInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(TERMUX_PKG, 0) }.isSuccess

    /**
     * Starts pig-relay as a Termux background command. Requires
     * `allow-external-apps = true` in ~/.termux/termux.properties.
     */
    fun launch(context: Context, port: Int, token: String, cwd: String): String? {
        val intent = Intent().apply {
            setClassName(TERMUX_PKG, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", RELAY_BIN)
            putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf("--port", port.toString(), "--token", token, "--cwd", cwd),
            )
            putExtra("com.termux.RUN_COMMAND_WORKDIR", cwd)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        return try {
            context.startService(intent)
            null
        } catch (first: Exception) {
            Log.e("RelayLauncher", "startService failed", first)
            try {
                ContextCompat.startForegroundService(context, intent)
                null
            } catch (second: Exception) {
                Log.e("RelayLauncher", "startForegroundService failed", second)
                "${first.javaClass.simpleName}: ${first.message} / " +
                    "${second.javaClass.simpleName}: ${second.message}"
            }
        }
    }
}
