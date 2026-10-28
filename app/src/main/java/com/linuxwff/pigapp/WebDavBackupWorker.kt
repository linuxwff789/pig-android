package com.linuxwff.pigapp

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
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

        // "Every day at HH:00" is a one-time work that re-arms itself: WorkManager
        // periodic work drifts by whatever Doze delays a run, and the point of a
        // daily backup is a predictable time.
        runCatching { WebDavScheduler.chainNextDaily(context, config) }

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
}

/**
 * Owns the scheduled upload. Hourly periods use WorkManager periodic work;
 * "every day" is a chain of one-time works pinned to the chosen wall-clock
 * time, because a periodic work's next run drifts with Doze.
 */
object WebDavScheduler {
    private const val WORK_NAME = "webdav-backup"
    private val NETWORK: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun apply(context: Context, config: WebDavConfig) {
        val workManager = WorkManager.getInstance(context)
        if (!config.ready || config.everyHours <= 0) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        if (config.everyHours == 24) {
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                dailyRequest(config.dailyMinutes),
            )
            return
        }
        val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(
            config.everyHours.toLong(),
            TimeUnit.HOURS,
        )
            // Turning the switch on must not fire a backup immediately (that
            // used to replace the app's relay connection right away).
            .setInitialDelay(config.everyHours.toLong(), TimeUnit.HOURS)
            .setConstraints(NETWORK)
            .build()
        // UPDATE (not REPLACE) so re-applying on every app start does not push
        // the next run further out each time.
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Queues tomorrow's daily run. Called by the worker itself, hence the
     * append: replacing the unique work here would cancel the caller.
     */
    fun chainNextDaily(context: Context, config: WebDavConfig) {
        if (!config.ready || config.everyHours != 24) return
        val delay = delayUntilNext(config.dailyMinutes)
        AppLog.event(
            "webdav",
            "next daily run in ${delay.toMinutes()} min " +
                "(${String.format("%02d:%02d", config.dailyMinutes / 60, config.dailyMinutes % 60)})",
        )
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                dailyRequest(config.dailyMinutes),
            )
        }
    }

    private fun dailyRequest(minutes: Int): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<WebDavBackupWorker>()
            .setInitialDelay(delayUntilNext(minutes))
            .setConstraints(NETWORK)
            .build()

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
