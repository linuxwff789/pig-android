package com.linuxwff.pigapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

object RelayLauncher {
    private const val TERMUX_PKG = "com.termux"
    private const val RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    private const val RELAY_BIN = "/data/data/com.termux/files/usr/bin/pig-relay"
    private const val WAKE_LOCK_BIN = "/data/data/com.termux/files/usr/bin/termux-wake-lock"
    private const val TERMUX_ACTIVITY = "com.termux.app.TermuxActivity"
    private const val INSTALL_SCRIPT =
        "https://github.com/linuxwff789/pig-android/releases/latest/download/install-termux.sh"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    fun isTermuxInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(TERMUX_PKG, 0) }.isSuccess

    /** Runs the one-line Termux installer (pig + pig-relay + UI dirs). */
    fun bootstrap(context: Context, cwd: String): String? {
        if (!hasPermission(context)) return "没有 Termux RUN_COMMAND 权限"
        if (!isTermuxInstalled(context)) return "Termux 未安装"
        val command = "pkg install -y curl >/dev/null 2>&1; curl -fsSL $INSTALL_SCRIPT | sh"
        val intent = Intent().apply {
            setClassName(TERMUX_PKG, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", cwd)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        return runCatching {
            context.startService(intent)
            null
        }.getOrElse { "无法启动安装命令: ${it.message}" }
    }

    fun openTermux(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent()
                .setClassName(TERMUX_PKG, TERMUX_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openTermuxInstallPage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Starts pig-relay as a Termux background command. Requires
     * `allow-external-apps = true` in ~/.termux/termux.properties.
     */
    fun launch(context: Context, port: Int, token: String, cwd: String): String? {
        acquireTermuxWakeLock(context)
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

    private var wakeLockRequested = false

    /** Ask Termux to hold its own wakelock once per app process. */
    private fun acquireTermuxWakeLock(context: Context) {
        if (wakeLockRequested) return
        val intent = Intent().apply {
            setClassName(TERMUX_PKG, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", WAKE_LOCK_BIN)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        runCatching {
            context.startService(intent)
            wakeLockRequested = true
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        manager.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** Returns true when the system dialog was launched. */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return false
        return runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }
}
