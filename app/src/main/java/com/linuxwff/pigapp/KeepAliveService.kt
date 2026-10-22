package com.linuxwff.pigapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process (and with it the loopback socket to
 * pig-relay) alive while the screen is off. Like Termux's `termux-wake-lock`,
 * it can also hold a partial wake lock so the CPU is not suspended during a
 * long turn; the user can turn that off from the drawer.
 */
class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        applyWakeLock(prefsEnabled())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_WAKE_LOCK -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, prefsEnabled())
                applyWakeLock(enabled)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun prefsEnabled(): Boolean =
        getSharedPreferences("pig", MODE_PRIVATE).getBoolean(PREF_WAKE_LOCK, true)

    private fun applyWakeLock(enabled: Boolean) {
        if (!enabled) {
            releaseWakeLock()
            return
        }
        if (wakeLock?.isHeld == true) return
        runCatching {
            val manager = getSystemService(PowerManager::class.java)
            wakeLock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Pig::RelayConnection",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台保活",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持与 Termux 里 pig-relay 的连接"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pig)
            .setContentTitle("Pig 后台运行中")
            .setContentText("保持与 Termux 的连接，锁屏后会话继续")
            .setContentIntent(pending)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pig_keepalive"
        private const val NOTIFICATION_ID = 101
        private const val ACTION_STOP = "com.linuxwff.pigapp.KEEPALIVE_STOP"
        private const val ACTION_SET_WAKE_LOCK = "com.linuxwff.pigapp.SET_WAKE_LOCK"
        private const val EXTRA_ENABLED = "enabled"
        private const val PREF_WAKE_LOCK = "wakelock"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, KeepAliveService::class.java),
                )
            }
        }

        /** Turn the CPU wake lock on/off at runtime (also persists the choice). */
        fun setWakeLock(context: Context, enabled: Boolean) {
            context.getSharedPreferences("pig", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_WAKE_LOCK, enabled)
                .apply()
            runCatching {
                context.startService(
                    Intent(context, KeepAliveService::class.java)
                        .setAction(ACTION_SET_WAKE_LOCK)
                        .putExtra(EXTRA_ENABLED, enabled),
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveService::class.java))
            }
        }
    }
}
