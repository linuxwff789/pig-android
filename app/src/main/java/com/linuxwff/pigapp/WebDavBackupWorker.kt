package com.linuxwff.pigapp

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
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
 * Uploads a full backup to WebDAV on the user's schedule. It runs without the
 * UI, so it opens its own relay connection (and starts the relay if Termux is
 * not running one); a live app connection is replaced for the duration and
 * reconnects on its own.
 */
class WebDavBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val prefs = context.getSharedPreferences("pig", Context.MODE_PRIVATE)
        val config = WebDavConfig.from(prefs)
        if (!config.ready || config.everyHours <= 0) return@withContext Result.success()

        val port = prefs.getInt("port", 8791)
        val token = prefs.getString("token", "").orEmpty()
        val cwd = prefs.getString("cwd", null) ?: "/data/data/com.termux/files/home"
        val client = RelayClient("127.0.0.1", port, token)
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

/** Registers (or removes) the periodic job that matches [config]. */
object WebDavScheduler {
    private const val WORK_NAME = "webdav-backup"

    fun apply(context: Context, config: WebDavConfig) {
        val workManager = WorkManager.getInstance(context)
        if (!config.ready || config.everyHours <= 0) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(
            config.everyHours.toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
