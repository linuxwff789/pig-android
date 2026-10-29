package com.linuxwff.pigapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Uploads a full backup to WebDAV on the user's schedule.
 *
 * When the app process is alive this reuses its relay connection: the relay
 * accepts one client, so a second connection would replace the app, the app
 * would reconnect and replace the worker, and the relay would flap forever.
 * Only with no UI process does it open a one-shot connection of its own.
 */
class WebDavBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        // No Activity means no ViewModel: open the log tail here so a nightly
        // run still leaves a trace when the app was never opened.
        AppLog.attach(context)
        val prefs = context.getSharedPreferences("pig", Context.MODE_PRIVATE)
        val config = WebDavConfig.from(prefs)
        if (!config.ready || config.everyHours <= 0) return@withContext Result.success()
        // A 65 MB session tree on a slow uplink outlives WorkManager's ~10 min
        // execution window; a foreground service keeps it from being cancelled
        // and retried forever.
        runCatching { setForeground(foregroundInfo()) }

        val port = prefs.getInt("port", 8791)
        val token = prefs.getString("token", "").orEmpty()
        val cwd = prefs.getString("cwd", null) ?: "/data/data/com.termux/files/home"

        // The app process is alive: upload over its connection. A second
        // RelayClient would replace it, the app would reconnect, replace the
        // worker, and the relay would flap until one of them gives up.
        AppViewModel.current?.let { live ->
            var uploaded = runCatching { live.scheduledWebDavBackup(config) }.getOrNull()
            if (uploaded == null) {
                // Relay down: start it and let the app's own client reconnect.
                RelayLauncher.launch(context, port, token, cwd)
                var waited = 0
                while (uploaded == null && waited < 40) {
                    delay(500)
                    waited++
                    uploaded = runCatching { live.scheduledWebDavBackup(config) }.getOrNull()
                }
            }
            if (uploaded != null) {
                AppLog.event("webdav", "scheduled backup ok (app connection): $uploaded")
                return@withContext Result.success()
            }
            AppLog.event("webdav", "scheduled backup: the app is alive but offline")
            return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        val client = RelayClient("127.0.0.1", port, token, autoReconnect = false)
        val archive = File(
            context.cacheDir,
            WebDavClient.PREFIX +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                WebDavClient.SUFFIX,
        )
        try {
            client.connect()
            if (!awaitConnection(client)) {
                // No relay yet (Termux was closed, or the app never started it
                // in this boot): ask Termux to start one and wait again.
                RelayLauncher.launch(context, port, token, cwd)
                delay(1_500)
                // One-shot: the failed attempt ended, so this is a new connect.
                client.connect()
                if (!awaitConnection(client)) {
                    AppLog.event("webdav", "scheduled backup: relay is not reachable")
                    return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
            }
            archive.outputStream().use { out ->
                client.exportBackup(out, includeSessions = true, includeUi = true) { }
            }
            WebDavClient.upload(config, archive.name, archive)
            val removed = runCatching { WebDavClient.prune(config, config.keep) }.getOrDefault(0)
            AppLog.event(
                "webdav",
                "scheduled backup ok: ${archive.name} (${archive.length()} bytes), pruned $removed",
            )
            Result.success()
        } catch (error: Exception) {
            AppLog.event("webdav", "scheduled backup failed: ${error.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            archive.delete()
            client.close()
        }
    }

    private suspend fun awaitConnection(client: RelayClient): Boolean {
        repeat(60) {
            if (client.connected.value) return true
            delay(500)
        }
        return client.connected.value
    }

    private fun foregroundInfo(): ForegroundInfo {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(FGS_CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        FGS_CHANNEL,
                        AppViewModel.packString("notification.backupChannel", "WebDAV 备份"),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
        val notification = NotificationCompat.Builder(context, FGS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pig)
            .setContentTitle(
                AppViewModel.packString("notification.backupTitle", "正在备份到 WebDAV"),
            )
            .setContentText(
                AppViewModel.packString("notification.backupText", "上传会话与配置，请保持网络连接"),
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(FGS_ID, notification)
    }

    private companion object {
        const val FGS_CHANNEL = "pig_backup"
        const val FGS_ID = 102
    }
}

/**
 * Owns the scheduled upload. Every interval is a periodic WorkManager job, so
 * each period comes back even when a run fails or never gets to start (a chain
 * of one-time works would simply stop). "Every day" is a 24 h period whose
 * first run is delayed to the chosen hour.
 */
object WebDavScheduler {
    private const val WORK_NAME = "webdav-backup"

    /** Remembers the last period/hour so a change resets the schedule. */
    private const val SIGNATURE_KEY = "webdav.scheduleSignature"
    private val NETWORK: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun apply(context: Context, config: WebDavConfig) {
        val workManager = WorkManager.getInstance(context)
        if (!config.ready || config.everyHours <= 0) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val daily = config.everyHours == 24
        // Daily: same 24 h period, but the first run waits for the chosen
        // hour. Deliberately *periodic* rather than a chain of one-time works:
        // a chained run that fails or never starts would end the schedule for
        // good, while periodic work keeps coming back every interval.
        val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(
            config.everyHours.toLong(),
            TimeUnit.HOURS,
        )
            // Turning the switch on must not fire a backup immediately (that
            // used to replace the app's relay connection right away).
            .setInitialDelay(
                if (daily) {
                    delayUntilNext(config.dailyMinutes)
                } else {
                    java.time.Duration.ofHours(config.everyHours.toLong())
                },
            )
            .setConstraints(NETWORK)
            .build()
        // UPDATE keeps the scheduled slot, so re-applying on every app start
        // does not push the next run further out. A changed period or hour has
        // to reset it instead, otherwise the new time would only apply after a
        // run at the old one.
        val prefs = context.getSharedPreferences("pig", Context.MODE_PRIVATE)
        val signature = "${config.everyHours}:${config.dailyMinutes}"
        val changed = prefs.getString(SIGNATURE_KEY, "") != signature
        if (changed) prefs.edit().putString(SIGNATURE_KEY, signature).apply()
        val at = if (daily) {
            " at " + String.format("%02d:%02d", config.dailyMinutes / 60, config.dailyMinutes % 60)
        } else {
            ""
        }
        AppLog.event(
            "webdav",
            "schedule: every ${config.everyHours}h$at" + if (changed) " (rescheduled)" else " (kept)",
        )
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            if (changed) {
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            } else {
                ExistingPeriodicWorkPolicy.UPDATE
            },
            request,
        )
    }

    /** Delay from [now] to the next [minutes] past midnight. */
    fun delayUntilNext(
        minutes: Int,
        now: java.time.ZonedDateTime = java.time.ZonedDateTime.now(),
    ): java.time.Duration {
        var next = now.toLocalDate()
            .atTime(minutes / 60, minutes % 60)
            .atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return java.time.Duration.between(now, next)
    }
}
