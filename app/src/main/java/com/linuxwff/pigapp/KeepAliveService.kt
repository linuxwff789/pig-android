package com.linuxwff.pigapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process (and with it the loopback socket to
 * pig-relay) alive while the screen is off. Like Termux's `termux-wake-lock`,
 * it can also hold a partial wake lock so the CPU is not suspended during a
 * long turn; the user can turn that off from the drawer.
 *
 * The wake lock is held for the duration of one turn, not for the lifetime of
 * the service: the foreground notification already keeps the process and the
 * socket alive, so holding the CPU awake while idle only cost battery (a
 * measured 10.5mAh for a single 39-minute hold).
 */
class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val renewWakeLock = Runnable { refreshWakeLock() }
    private val releaseWhenIdle = Runnable { if (!turnActive) releaseWakeLock() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(NOTIFICATION_ID, buildNotification())
        refreshWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_WAKE_LOCK -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, prefsEnabled())
                refreshWakeLock()
                if (!enabled) releaseWakeLock()
            }
            ACTION_TURN_STATE -> setTurnActive(intent.getBooleanExtra(EXTRA_ACTIVE, false))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        turnActive = false
        releaseWakeLock()
        super.onDestroy()
    }

    private fun prefsEnabled(): Boolean =
        getSharedPreferences("pig", MODE_PRIVATE).getBoolean(PREF_WAKE_LOCK, true)

    /** A turn started or settled; hold the CPU only while one is running. */
    private fun setTurnActive(active: Boolean) {
        turnActive = active
        handler.removeCallbacks(releaseWhenIdle)
        if (active) {
            refreshWakeLock()
        } else {
            // Let the last frames of the turn land before the CPU may sleep.
            handler.postDelayed(releaseWhenIdle, TURN_GRACE_MS)
        }
    }

    private fun refreshWakeLock() {
        handler.removeCallbacks(renewWakeLock)
        if (!prefsEnabled() || !turnActive) {
            releaseWakeLock()
            return
        }
        runCatching {
            val held = wakeLock?.isHeld == true
            if (!held) {
                val manager = getSystemService(PowerManager::class.java)
                wakeLock = manager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Pig::Turn",
                ).apply { setReferenceCounted(false) }
            }
            // A timeout, not an indefinite hold: a frozen or killed turn must
            // never pin the CPU, so it is renewed while the turn keeps running.
            wakeLock?.acquire(TURN_WAKE_LOCK_MS)
            handler.postDelayed(renewWakeLock, TURN_WAKE_LOCK_MS / 2)
        }
    }

    private fun releaseWakeLock() {
        handler.removeCallbacks(renewWakeLock)
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
                AppViewModel.packString("notification.channelName", "后台保活"),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = AppViewModel.packString(
                    "notification.channelDesc",
                    "保持与 Termux 里 pig-relay 的连接",
                )
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
            .setContentTitle(
                AppViewModel.packString("notification.title", "Pig 后台运行中"),
            )
            .setContentText(
                AppViewModel.packString("notification.text", "保持与 Termux 的连接，锁屏后会话继续"),
            )
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
        private const val ACTION_TURN_STATE = "com.linuxwff.pigapp.TURN_STATE"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_ACTIVE = "active"
        private const val PREF_WAKE_LOCK = "wakelock"
        private const val TURN_WAKE_LOCK_MS = 2 * 60_000L
        private const val TURN_GRACE_MS = 30_000L

        @Volatile
        private var running = false

        /** True while pig is running a turn in this app. */
        @Volatile
        private var turnActive = false

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
            // Only talk to a running service; starting it just to release a
            // wake lock would also turn on the foreground service.
            if (!running) return
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

        /**
         * Tell the service whether a turn is running. Only a turn needs the CPU
         * awake; holding the lock for the whole service lifetime kept the device
         * out of deep sleep while nothing was happening.
         */
        fun setTurnActive(context: Context, active: Boolean) {
            if (turnActive == active) return
            turnActive = active
            if (!running) return
            runCatching {
                context.startService(
                    Intent(context, KeepAliveService::class.java)
                        .setAction(ACTION_TURN_STATE)
                        .putExtra(EXTRA_ACTIVE, active),
                )
            }
        }
    }
}
