package com.linuxwff.pigapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Session notifications for state changes that happen while the app is in the
 * background: the turn finished, an extension is waiting for an answer, the
 * request failed, or the context was compacted.
 *
 * Two channels, not one per kind: Android gives the user channel-level control,
 * so splitting "done" from "failed" is useful, five channels are noise.
 */
class SessionNotifications(private val context: Context) {

    enum class Kind(
        val id: Int,
        val alerts: Boolean,
        val priority: Int,
        val category: String,
        val silent: Boolean = false,
    ) {
        DONE(1001, false, NotificationCompat.PRIORITY_DEFAULT, NotificationCompat.CATEGORY_MESSAGE),
        INFO(1002, false, NotificationCompat.PRIORITY_LOW, NotificationCompat.CATEGORY_STATUS),
        COMPACTED(1003, false, NotificationCompat.PRIORITY_LOW, NotificationCompat.CATEGORY_STATUS, silent = true),
        ATTENTION(1004, true, NotificationCompat.PRIORITY_HIGH, NotificationCompat.CATEGORY_REMINDER),
        ERROR(1005, true, NotificationCompat.PRIORITY_HIGH, NotificationCompat.CATEGORY_ERROR),
    }

    private val manager = NotificationManagerCompat.from(context)
    private val posted = mutableSetOf<Pair<String, Int>>()

    fun notify(kind: Kind, session: String, title: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        val channels = ensureChannels()
        val tag = session.ifBlank { "pig" }
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            kind.id,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(
            context,
            if (kind.alerts) channels.second else channels.first,
        )
            .setSmallIcon(R.drawable.ic_stat_pig)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(kind.priority)
            .setCategory(kind.category)
        if (kind.silent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setSilent(true)
        }
        runCatching { manager.notify(tag, kind.id, builder.build()) }
        posted.add(tag to kind.id)
    }

    /**
     * The user is looking at the app again; drop what we raised for them.
     * Enumerate the live notifications instead of remembering tags: the tag is
     * the session path, and a recreated process would have an empty list while
     * the shade still shows stale cards.
     */
    fun clear() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        val active = system.activeNotifications
        val ours = active.filter { it.id in KIND_IDS }
        ours.forEach { runCatching { system.cancel(it.tag, it.id) } }
        android.util.Log.i(
            "PigNotifications",
            "clear: active=${active.size} ours=${ours.size} ids=${ours.map { it.id }}",
        )
        posted.clear()
    }

    /**
     * Android binds the sound to the channel, so the channel id carries the
     * chosen sound: switching creates a fresh channel and the old variants are
     * deleted instead of lingering in system settings.
     */
    private fun ensureChannels(): Pair<String, String> {
        val choice = soundChoice()
        val updates = "$CHANNEL_UPDATES_PREFIX$choice"
        val alerts = "$CHANNEL_ALERTS_PREFIX$choice"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return updates to alerts
        val system = context.getSystemService(NotificationManager::class.java)
            ?: return updates to alerts
        val sound = soundUri(choice)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (system.getNotificationChannel(updates) == null) {
            system.createNotificationChannel(
                NotificationChannel(
                    updates,
                    AppViewModel.packString("notification.updatesChannel", "会话进展"),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = AppViewModel.packString(
                        "notification.updatesChannelDesc",
                        "回复完成、扩展消息、上下文压缩",
                    )
                    setSound(sound, audio)
                    if (sound == null) enableVibration(false)
                },
            )
        }
        if (system.getNotificationChannel(alerts) == null) {
            system.createNotificationChannel(
                NotificationChannel(
                    alerts,
                    AppViewModel.packString("notification.alertsChannel", "需要处理"),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = AppViewModel.packString(
                        "notification.alertsChannelDesc",
                        "扩展在等确认、请求失败",
                    )
                    setSound(sound, audio)
                    if (sound == null) enableVibration(false)
                },
            )
        }
        // Drop variants from an earlier sound choice.
        system.notificationChannels
            .map { it.id }
            .filter {
                (it.startsWith(CHANNEL_UPDATES_PREFIX) || it.startsWith(CHANNEL_ALERTS_PREFIX)) &&
                    it != updates && it != alerts
            }
            .forEach { system.deleteNotificationChannel(it) }
        return updates to alerts
    }

    /** `system`, `silent`, `alarm` or `ringtone`; stored in the shared prefs. */
    fun soundChoice(): String = context.getSharedPreferences("pig", Context.MODE_PRIVATE)
        .getString(PREF_SOUND, SOUND_SYSTEM)
        ?.takeIf { it in SOUND_CHOICES } ?: SOUND_SYSTEM

    fun setSoundChoice(choice: String) {
        if (choice !in SOUND_CHOICES) return
        context.getSharedPreferences("pig", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_SOUND, choice)
            .apply()
        ensureChannels()
    }

    fun alertsChannelId(): String = "${CHANNEL_ALERTS_PREFIX}${soundChoice()}"

    private fun soundUri(choice: String): Uri? = when (choice) {
        SOUND_SILENT -> null
        SOUND_ALARM -> Settings.System.DEFAULT_ALARM_ALERT_URI
        SOUND_RINGTONE -> Settings.System.DEFAULT_RINGTONE_URI
        else -> Settings.System.DEFAULT_NOTIFICATION_URI
    }

    private companion object {
        const val CHANNEL_UPDATES_PREFIX = "pig_updates_"
        const val CHANNEL_ALERTS_PREFIX = "pig_alerts_"
        const val PREF_SOUND = "notifySound"
        const val SOUND_SYSTEM = "system"
        const val SOUND_SILENT = "silent"
        const val SOUND_ALARM = "alarm"
        const val SOUND_RINGTONE = "ringtone"
        val SOUND_CHOICES = setOf(SOUND_SYSTEM, SOUND_SILENT, SOUND_ALARM, SOUND_RINGTONE)
        val KIND_IDS = setOf(1001, 1002, 1003, 1004, 1005)
    }
}
