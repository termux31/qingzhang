package com.example.qingzhang.domain

import com.example.qingzhang.data.LivingPlan
import java.time.LocalDate

const val LIVING_PLAN_DAYS = 30L

val LivingPlan.startDate: LocalDate get() = LocalDate.ofEpochDay(startDay)
val LivingPlan.endDate: LocalDate get() = startDate.plusDays(LIVING_PLAN_DAYS - 1)

data class LivingBudget(
    val daysLeft: Long,
    val remainingCents: Long,
    val dailyCents: Long?,
    val transferableCents: Long,
)

fun LivingPlan.budgetOn(today: LocalDate, spentCents: Long, savedCents: Long, goalArchived: Boolean = false): LivingBudget {
    val daysLeft = (endDate.toEpochDay() - today.toEpochDay() + 1).coerceIn(0, LIVING_PLAN_DAYS)
    val available = budgetCents - spentCents - savedCents
    val remaining = if (daysLeft == 0L || goalArchived) available else budgetCents - spentCents - maxOf(reserveCents, savedCents)
    val transferable = when {
        goalId == null || goalArchived || today < startDate -> 0
        daysLeft == 0L -> available.coerceAtLeast(0)
        else -> minOf(available, reserveCents - savedCents).coerceAtLeast(0)
    }
    return LivingBudget(daysLeft, remaining, if (daysLeft > 0) remaining.coerceAtLeast(0) / daysLeft else null, transferable)
}

fun weeklySavingsCents(remainingCents: Long, today: LocalDate, targetDay: Long?): Long? {
    if (remainingCents <= 0 || targetDay == null || targetDay < today.toEpochDay()) return null
    val daysLeft = targetDay - today.toEpochDay() + 1
    val weeks = (daysLeft + 6) / 7
    return (remainingCents + weeks - 1) / weeks
}
