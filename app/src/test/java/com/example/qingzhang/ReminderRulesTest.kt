package com.example.qingzhang

import com.example.qingzhang.data.LivingPlan
import com.example.qingzhang.notifications.NoticeKind
import com.example.qingzhang.notifications.ReminderSettings
import com.example.qingzhang.notifications.isDailyDue
import com.example.qingzhang.notifications.isOverBudget
import com.example.qingzhang.notifications.settlementAt
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = LocalDate.of(2026, 11, 8)
    private val plan = LivingPlan(startDay = start.toEpochDay(), budgetCents = 180_000, reserveCents = 30_000, goalId = 1, incomeId = null)

    @Test
    fun dailyReminderStartsAtSelectedTimeAfterEnabling() {
        val due = start.atTime(21, 0).atZone(zone)
        assertFalse(ReminderSettings().isDailyDue(due))
        val early = ReminderSettings(mapOf(NoticeKind.DAILY to due.minusMinutes(1).toInstant().toEpochMilli()))
        assertFalse(early.isDailyDue(due.minusSeconds(1)))
        assertTrue(early.isDailyDue(due))
        val late = ReminderSettings(mapOf(NoticeKind.DAILY to due.plusMinutes(1).toInstant().toEpochMilli()))
        assertFalse(late.isDailyDue(due.plusMinutes(5)))
        assertTrue(late.isDailyDue(due.plusDays(1)))
    }

    @Test
    fun planSettlesNextMorningAndOverspendingUsesSpendableBudget() {
        assertEquals(LocalDate.of(2026, 12, 8).atTime(9, 0).atZone(zone), plan.settlementAt(zone))
        assertFalse(plan.isOverBudget(start, 150_000, 0))
        assertTrue(plan.isOverBudget(start, 150_001, 10_000))
        assertFalse(plan.isOverBudget(start.minusDays(1), 150_001, 0))
        assertFalse(plan.isOverBudget(start.plusDays(30), 150_001, 0))
    }
}
