package com.linuxwff.pigapp

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
    private const val REPO = "https://github.com/linuxwff789/pig-android"
    private const val INSTALL_SCRIPT =
        "$REPO/releases/latest/download/install-termux.sh"
    private val RELEASE_VERSION = Regex("""\d+\.\d+\.\d+""")

    /** The release this app was built from, or null for a debug/CI build: those
     *  carry a version that has no release, so they use the latest one. */
    private fun releaseTag(context: Context): String? {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) return null
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        return version.takeIf { RELEASE_VERSION.matches(it) }?.let { "v$it" }
    }

    /** The Termux one-liner that installs the backend: shown in the setup dialog
     *  and run by [bootstrap].
     *
     *  The relay is published with this app version, so the installer is taken
     *  from this release (the tagged script is missing for some old releases, so
     *  the latest one is the fallback). No `pkg install` runs before it: Termux's
     *  bootstrap ships curl, and the script itself points the repo at a mirror
     *  before its first install. The download goes to a file first, so a failed
     *  fetch cannot look like a successful install with nothing to do. */
    fun installCommand(context: Context): String {
        val tag = releaseTag(context)
        val script = if (tag != null) {
            "$REPO/releases/download/$tag/install-termux.sh"
        } else {
            INSTALL_SCRIPT
        }
        val pin = if (tag != null) " $tag" else ""
        return "mkdir -p \"\$PREFIX/tmp\"; p=\$PREFIX/tmp/pig-install.sh; " +
            "{ curl -fsSL $script -o \"\$p\" || curl -fsSL $INSTALL_SCRIPT -o \"\$p\"; } && " +
            "sh \"\$p\"$pin"
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    fun isTermuxInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(TERMUX_PKG, 0) }.isSuccess

    /** Runs the one-line Termux installer (pig + pig-relay + UI dirs). */
    fun bootstrap(context: Context, cwd: String): String? {
        if (!hasPermission(context)) {
            return AppViewModel.packString("relay.noPermission", "没有 Termux RUN_COMMAND 权限")
        }
        if (!isTermuxInstalled(context)) {
            return AppViewModel.packString("relay.termuxMissing", "Termux 未安装")
        }
        val command = installCommand(context)
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
        }.getOrElse {
            AppViewModel.packString("relay.launchFailed", "无法启动安装命令: {error}")
                .replace("{error}", it.message ?: "")
        }
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
        return runRelay(context, cwd, relayCommand(port, token, cwd))
    }

    /**
     * Replaces the relay on [port]. The relay only reads `--token` at startup,
     * so a stale process (older install, hand-run relay) answers every
     * handshake with `auth failed: bad token` no matter what we send. Only
     * called after a bad-token rejection, where the running relay is useless
     * to us anyway.
     */
    fun restart(context: Context, port: Int, token: String, cwd: String): String? {
        acquireTermuxWakeLock(context)
        // The bracket in the pattern keeps pkill from matching this very
        // shell, whose command line also contains the literal string.
        val kill = "pkill -f \"pig-rela[y] --port $port\" >/dev/null 2>&1; sleep 0.5; "
        return runRelay(context, cwd, kill + relayCommand(port, token, cwd))
    }

    private fun relayCommand(port: Int, token: String, cwd: String): String =
        "exec \"$RELAY_BIN\" --port $port --token $token --cwd ${shellQuote(cwd)}"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun runRelay(context: Context, cwd: String, command: String): String? {
        if (!hasPermission(context)) {
            return AppViewModel.packString("relay.noPermission", "没有 Termux RUN_COMMAND 权限")
        }
        if (!isTermuxInstalled(context)) {
            return AppViewModel.packString("relay.termuxMissing", "Termux 未安装")
        }
        val intent = Intent().apply {
            setClassName(TERMUX_PKG, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
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
