package com.example.qingzhang.notifications

import com.example.qingzhang.data.LivingPlan
import com.example.qingzhang.domain.budgetOn
import com.example.qingzhang.domain.endDate
import com.example.qingzhang.domain.startDate
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class NoticeKind(val title: String, val message: String) {
    DAILY("记账提醒", "今天还没记账。有花销的话，顺手记一笔吧。"),
    OVER_BUDGET("生活费预算超支", "本期生活费已超出预算，点此查看支出明细。"),
    SETTLEMENT("生活费计划结算", "本期生活费计划已结束，点此查看支出和攒钱结算。"),
    GOAL("攒钱目标达成", "一个攒钱目标已达成，点此查看进度。"),
}

data class ReminderSettings(
    val enabledSince: Map<NoticeKind, Long> = emptyMap(),
    val dailyMinute: Int = 21 * 60,
)

internal fun ReminderSettings.dailyAt(day: LocalDate, zone: ZoneId): ZonedDateTime =
    day.atTime(LocalTime.of(dailyMinute / 60, dailyMinute % 60)).atZone(zone)

internal fun ReminderSettings.isDailyDue(now: ZonedDateTime): Boolean {
    val enabledAt = enabledSince[NoticeKind.DAILY] ?: return false
    val due = dailyAt(now.toLocalDate(), now.zone)
    return now >= due && due.toInstant().toEpochMilli() >= enabledAt
}

internal fun LivingPlan.settlementAt(zone: ZoneId): ZonedDateTime =
    endDate.plusDays(1).atTime(9, 0).atZone(zone)

internal fun LivingPlan.isOverBudget(today: LocalDate, spent: Long, saved: Long): Boolean =
    today in startDate..endDate && budgetOn(today, spent, saved).remainingCents < 0
