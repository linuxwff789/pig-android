package com.linuxwff.pigapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires the backup at the scheduled time even when the phone is in Doze.
 *
 * WorkManager's periodic job alone is not enough: JobScheduler is allowed to
 * defer it until a maintenance window, which can be hours away. The alarm is
 * permitted to wake the device and queues one immediate run. The worker
 * re-arms the next alarm in its finally block (after recording the success),
 * so a failure retries half an hour later instead of ending the schedule.
 */
class WebDavAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("pig", Context.MODE_PRIVATE)
        val config = WebDavConfig.from(prefs)
        if (!config.ready || config.everyHours <= 0) return
        WebDavScheduler.enqueueNow(context)
        AppLog.event("webdav", "alarm fired")
    }
}
