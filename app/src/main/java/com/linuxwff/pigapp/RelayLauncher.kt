package com.linuxwff.pigapp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
    private const val RAW = "https://raw.githubusercontent.com/linuxwff789/pig-android/main"
    /**
     * Fallback installer (debug builds, which have no tagged release). The
     * script is taken from the repository rather than a release asset: every
     * release is a pre-release now, and `/releases/latest` would serve the
     * script of the last *stable* build. The script resolves the newest
     * release itself, previews included.
     */
    private const val INSTALL_SCRIPT = "$RAW/scripts/install-termux.sh"
    /** Any dot-separated numeric version: 1.1.67 and 1.1.68.2 are both tags. */
    private val RELEASE_VERSION = Regex("""\d+(\.\d+)+""")

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
        // jsDelivr serves the same script straight from the repository and is
        // a different host/CDN, which matters when GitHub's TLS breaks behind a
        // proxy (`curl: (35) TLS connect error ... lib(0)::reason(0)`).
        val cdn = "https://cdn.jsdelivr.net/gh/linuxwff789/pig-android@" +
            (tag ?: "main") + "/scripts/install-termux.sh"
        val pin = if (tag != null) " $tag" else ""
        // `--retry` alone does not retry a failed TLS handshake; that needs
        // `--retry-all-errors` (curl 7.71+). Both hosts are tried before
        // giving up, and the file is only executed once it has content.
        return "mkdir -p \"\$PREFIX/tmp\"; p=\$PREFIX/tmp/pig-install.sh; rm -f \"\$p\"; " +
            "for u in $script $cdn; do " +
            "curl -fsSL --retry 3 --retry-all-errors --retry-delay 2 \"\$u\" -o \"\$p\" && break; " +
            "done; if [ -s \"\$p\" ]; then sh \"\$p\"$pin; else echo '下载失败，请稍后重试'; fi"
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    fun isTermuxInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(TERMUX_PKG, 0) }.isSuccess

    /**
     * The short one-liner that only flips Termux's external-command switch.
     * A fresh Termux refuses `RUN_COMMAND` until `allow-external-apps` is on, so
     * the app cannot install anything by itself; this is the smallest thing the
     * user has to paste, after which the app's own install button works (and
     * shows progress). Pure shell, so it also works offline — the same thing is
     * available as `enable-external-apps.sh` in every release.
     */
    fun enableExternalAppsCommand(): String =
        "mkdir -p ~/.termux; f=~/.termux/termux.properties; " +
            "sed -i '/allow-external-apps/d' \"\$f\" 2>/dev/null; " +
            "echo 'allow-external-apps = true' >> \"\$f\" && termux-reload-settings"

    /** Runs the one-line Termux installer (pig + pig-relay + UI dirs). */
    fun bootstrap(context: Context, cwd: String): String? =
        runCommand(
            context = context,
            cwd = cwd,
            command = installCommand(context),
            kind = RunCommandReceiver.KIND_BOOTSTRAP,
            requestCode = RunCommandReceiver.REQUEST_BOOTSTRAP,
            label = "Pig install",
        )

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
        return runCommand(
            context = context,
            cwd = cwd,
            command = relayCommand(port, token, cwd),
            kind = RunCommandReceiver.KIND_RELAY,
            requestCode = RunCommandReceiver.REQUEST_RELAY,
            label = "pig-relay",
        )
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
        return runCommand(
            context = context,
            cwd = cwd,
            command = kill + relayCommand(port, token, cwd),
            kind = RunCommandReceiver.KIND_RESTART,
            requestCode = RunCommandReceiver.REQUEST_RESTART,
            label = "pig-relay restart",
        )
    }

    /**
     * Removes the Termux-side install: stops pig and the relay, then deletes
     * both binaries and the installer receipt. With [purgeData] it also removes
     * `~/.pig` (config, sessions, keys, UI packs) - the app asks first and
     * defaults that switch off.
     *
     * The bracket patterns keep `pkill -f` from matching the shell that runs
     * this very command (its cmdline contains the same text without brackets).
     */
    fun uninstall(context: Context, purgeData: Boolean, cwd: String): String? {
        val command = buildString {
            // Stop first, so the optional backup sees a consistent ~/.pig.
            append("pkill -f 'pig-rela[y] --port' >/dev/null 2>&1; ")
            append("pkill -f 'pig --mode rp[c]' >/dev/null 2>&1; ")
            append("pkill -x pig >/dev/null 2>&1; sleep 0.5; ")
            if (purgeData) {
                // Purging is irreversible; write a recovery archive outside
                // ~/.pig first and refuse to delete anything if it fails.
                append("b=\"\$HOME/pig-before-uninstall-\$(date +%Y%m%d-%H%M%S).tar.gz\"; ")
                append("if tar -czf \"\$b\" -C \"\$HOME\" --exclude='.pig/cache' --exclude='.pig/docs' .pig; then ")
                append("echo \"backup=\$b\"; ")
                append("else echo '备份失败，未删除 ~/.pig'; exit 1; fi; ")
            }
            append("rm -f \"\$PREFIX\"/bin/pig \"\$PREFIX\"/bin/pig-relay \"\$PREFIX\"/bin/pig-relay.* ; ")
            append("rm -f \"\$HOME\"/.pig/install-receipt; ")
            if (purgeData) append("rm -rf \"\$HOME\"/.pig; ")
            append("echo pig-uninstalled")
        }
        return runCommand(
            context = context,
            cwd = cwd,
            command = command,
            kind = RunCommandReceiver.KIND_UNINSTALL,
            requestCode = RunCommandReceiver.REQUEST_UNINSTALL,
            label = "Pig uninstall",
        )
    }

    private fun relayCommand(port: Int, token: String, cwd: String): String =
        "exec \"$RELAY_BIN\" --port $port --token ${shellQuote(token)} --cwd ${shellQuote(cwd)}"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * The PendingIntent Termux answers through. It must be `FLAG_MUTABLE` on
     * Android 12+: Termux fills the result bundle into the intent it sends, and
     * an immutable PendingIntent silently drops those extras.
     */
    private fun resultPendingIntent(context: Context, kind: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, RunCommandReceiver::class.java)
            .setAction(RunCommandReceiver.ACTION_RESULT)
            .putExtra(RunCommandReceiver.EXTRA_KIND, kind)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun runCommand(
        context: Context,
        cwd: String,
        command: String,
        kind: String,
        requestCode: Int,
        label: String,
    ): String? {
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
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label)
            putExtra(
                "com.termux.RUN_COMMAND_PENDING_INTENT",
                resultPendingIntent(context, kind, requestCode),
            )
        }
        AppLog.event("termux", "run [$label]: ${AppLog.redact(command).take(600)}")
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
