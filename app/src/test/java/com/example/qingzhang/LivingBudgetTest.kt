package com.example.qingzhang

import com.example.qingzhang.data.LivingPlan
import com.example.qingzhang.domain.budgetOn
import com.example.qingzhang.domain.endDate
import com.example.qingzhang.domain.weeklySavingsCents
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivingBudgetTest {
    private val start = LocalDate.of(2026, 11, 8)
    private val plan = LivingPlan(
        startDay = start.toEpochDay(),
        budgetCents = 180_000,
        reserveCents = 30_000,
        goalId = 1,
        incomeId = null,
    )

    @Test
    fun usesThirtyInclusiveDaysAcrossMonths() {
        assertEquals(LocalDate.of(2026, 12, 7), plan.endDate)
        assertEquals(30L, plan.budgetOn(start, 0, 0).daysLeft)
        assertEquals(0L, plan.budgetOn(start.minusDays(1), 0, 0).transferableCents)
        assertEquals(1L, plan.budgetOn(plan.endDate, 0, 0).daysLeft)
        val closed = plan.budgetOn(plan.endDate.plusDays(1), 45_000, 10_000)
        assertEquals(0L, closed.daysLeft)
        assertNull(closed.dailyCents)
        assertEquals(125_000L, closed.remainingCents)
        assertEquals(125_000L, closed.transferableCents)
    }

    @Test
    fun separatesReservedSavingsFromDailySpending() {
        val before = plan.budgetOn(start.plusDays(10), 45_000, 0)
        val after = plan.budgetOn(start.plusDays(10), 45_000, 10_000)
        assertEquals(20L, after.daysLeft)
        assertEquals(105_000L, after.remainingCents)
        assertEquals(5_250L, after.dailyCents!!)
        assertEquals(before.remainingCents, after.remainingCents)
        assertEquals(20_000L, after.transferableCents)

        val overspent = plan.budgetOn(start, 175_000, 0)
        assertEquals(-25_000L, overspent.remainingCents)
        assertEquals(0L, overspent.dailyCents!!)
        assertEquals(5_000L, overspent.transferableCents)
    }

    @Test
    fun computesWeeklySavingsTowardTargetDate() {
        val today = LocalDate.of(2026, 11, 8)
        assertEquals(150L, weeklySavingsCents(600L, today, today.plusDays(27).toEpochDay()))
        assertEquals(600L, weeklySavingsCents(600L, today, today.toEpochDay()))
        assertNull(weeklySavingsCents(600L, today, today.minusDays(1).toEpochDay()))
        assertNull(weeklySavingsCents(0L, today, today.plusDays(30).toEpochDay()))
    }
}
