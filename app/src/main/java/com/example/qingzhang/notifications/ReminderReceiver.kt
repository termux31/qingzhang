package com.example.qingzhang.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.qingzhang.QingZhangApplication

internal const val REMINDER_ALARM_ACTION = "com.example.qingzhang.REMINDER_ALARM"

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            REMINDER_ALARM_ACTION, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        val pending = goAsync()
        (context.applicationContext as QingZhangApplication).refreshReminders { pending.finish() }
    }
}
