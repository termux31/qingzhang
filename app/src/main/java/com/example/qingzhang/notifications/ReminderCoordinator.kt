package com.example.qingzhang.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.qingzhang.MainActivity
import com.example.qingzhang.R
import com.example.qingzhang.data.LedgerDao
import com.example.qingzhang.data.LivingPlanRepository
import com.example.qingzhang.domain.endDate
import com.example.qingzhang.domain.endExclusiveMillis
import com.example.qingzhang.domain.startDate
import com.example.qingzhang.domain.startMillis
import java.io.IOException
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val REMINDER_CHANNEL = "qingzhang_reminders"
private const val OPEN_REMINDER = "com.example.qingzhang.OPEN_REMINDER"

internal data class NoticeTarget(val kind: NoticeKind, val id: Long) {
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        action = OPEN_REMINDER
        data = Uri.parse("qingzhang://reminders/${kind.name}/$id")
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    companion object {
        fun from(intent: Intent): NoticeTarget? {
            if (intent.action != OPEN_REMINDER || intent.data?.host != "reminders") return null
            val parts = intent.data?.pathSegments.orEmpty()
            if (parts.size != 2) return null
            val kind = NoticeKind.entries.firstOrNull { it.name == parts[0] } ?: return null
            val id = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return NoticeTarget(kind, id)
        }
    }
}

internal fun notificationsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    val manager = context.getSystemService(NotificationManager::class.java)
    return manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(REMINDER_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
}

internal class ReminderCoordinator(
    private val context: Context,
    private val preferences: ReminderPreferences,
    private val plans: LivingPlanRepository,
    private val ledger: LedgerDao,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()
    private val alarmIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setAction(REMINDER_ALARM_ACTION),
        PendingIntent.FLAG_IMMUTABLE,
    )

    init {
        manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, "轻账提醒", NotificationManager.IMPORTANCE_DEFAULT))
    }

    suspend fun observeChanges() {
        combine(preferences.settings, plans.plans, plans.goals) { _, _, _ -> Unit }
            .collect { refresh() }
    }

    suspend fun refresh(now: ZonedDateTime = ZonedDateTime.now()) = mutex.withLock {
        try {
            checkReminders(now)
        } catch (error: IOException) {
            Log.w("QingZhangReminders", "Could not read or save reminders", error)
        }
    }

    private suspend fun checkReminders(now: ZonedDateTime) {
        val config = preferences.settings.first()
        val day = now.toLocalDate()
        val zone = now.zone
        val allowed = notificationsAllowed(context)
        var next: ZonedDateTime? = null

        if (allowed && NoticeKind.DAILY in config.enabledSince) {
            if (config.isDailyDue(now) && preferences.claim(NoticeKind.DAILY, day.toEpochDay())) {
                if (!ledger.hasTransactionsBetween(day.startMillis(zone), day.endExclusiveMillis(zone))) {
                    post(NoticeTarget(NoticeKind.DAILY, day.toEpochDay()))
                }
            }
            val due = config.dailyAt(day, zone)
            next = if (now < due) due else config.dailyAt(day.plusDays(1), zone)
        }

        for (progress in plans.plans.first()) {
            val plan = progress.plan
            if (allowed && NoticeKind.OVER_BUDGET in config.enabledSince && day in plan.startDate..plan.endDate) {
                val spent = ledger.expenseTotalBetween(plan.startDate.startMillis(zone), plan.endDate.endExclusiveMillis(zone))
                if (plan.isOverBudget(day, spent, progress.savedCents) &&
                    notificationsAllowed(context) && preferences.claim(NoticeKind.OVER_BUDGET, plan.id)
                ) post(NoticeTarget(NoticeKind.OVER_BUDGET, plan.id))
            }

            val since = config.enabledSince[NoticeKind.SETTLEMENT]
            if (allowed && since != null) {
                val due = plan.settlementAt(zone)
                if (due.toInstant().toEpochMilli() >= since) {
                    if (now >= due) {
                        if (preferences.claim(NoticeKind.SETTLEMENT, plan.id)) post(NoticeTarget(NoticeKind.SETTLEMENT, plan.id))
                    } else if (next == null || due < next) {
                        next = due
                    }
                }
            }
        }

        if (allowed && NoticeKind.GOAL in config.enabledSince) {
            for (progress in plans.goals.first()) {
                if (progress.savedCents >= progress.goal.targetCents &&
                    notificationsAllowed(context) && preferences.claim(NoticeKind.GOAL, progress.goal.id)
                ) post(NoticeTarget(NoticeKind.GOAL, progress.goal.id))
            }
        }

        if (next == null) alarms.cancel(alarmIntent)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), alarmIntent)
    }

    @SuppressLint("MissingPermission")
    private fun post(target: NoticeTarget) {
        if (!notificationsAllowed(context)) return
        val open = PendingIntent.getActivity(context, 0, target.intent(context), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, REMINDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(target.kind.title)
            .setContentText(target.kind.message)
            .setStyle(Notification.BigTextStyle().bigText(target.kind.message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        val tag = if (target.kind == NoticeKind.DAILY) target.kind.name else "${target.kind.name}:${target.id}"
        manager.notify(tag, 0, notification)
    }
}
