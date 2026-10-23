package com.linuxwff.pigapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
        val channel: String,
        val priority: Int,
        val category: String,
        val silent: Boolean = false,
    ) {
        DONE(1001, "pig_updates", NotificationCompat.PRIORITY_DEFAULT, NotificationCompat.CATEGORY_MESSAGE),
        INFO(1002, "pig_updates", NotificationCompat.PRIORITY_LOW, NotificationCompat.CATEGORY_STATUS),
        COMPACTED(1003, "pig_updates", NotificationCompat.PRIORITY_LOW, NotificationCompat.CATEGORY_STATUS, silent = true),
        ATTENTION(1004, "pig_alerts", NotificationCompat.PRIORITY_HIGH, NotificationCompat.CATEGORY_REMINDER),
        ERROR(1005, "pig_alerts", NotificationCompat.PRIORITY_HIGH, NotificationCompat.CATEGORY_ERROR),
    }

    private val manager = NotificationManagerCompat.from(context)
    private val posted = mutableSetOf<Pair<String, Int>>()

    fun notify(kind: Kind, session: String, title: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        ensureChannels()
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
        val builder = NotificationCompat.Builder(context, kind.channel)
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

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL_UPDATES) == null) {
            system.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_UPDATES,
                    AppViewModel.packString("notification.updatesChannel", "会话进展"),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = AppViewModel.packString(
                        "notification.updatesChannelDesc",
                        "回复完成、扩展消息、上下文压缩",
                    )
                },
            )
        }
        if (system.getNotificationChannel(CHANNEL_ALERTS) == null) {
            system.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    AppViewModel.packString("notification.alertsChannel", "需要处理"),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = AppViewModel.packString(
                        "notification.alertsChannelDesc",
                        "扩展在等确认、请求失败",
                    )
                },
            )
        }
    }

    private companion object {
        const val CHANNEL_UPDATES = "pig_updates"
        const val CHANNEL_ALERTS = "pig_alerts"
        val KIND_IDS = setOf(1001, 1002, 1003, 1004, 1005)
    }
}
