package com.example.qingzhang

import com.example.qingzhang.data.ImportSource
import com.example.qingzhang.data.TransactionDetail
import com.example.qingzhang.data.TransactionEntity
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.StatisticsCalculator
import com.example.qingzhang.domain.StatsPeriod
import com.example.qingzhang.domain.toMillis
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsTest {
    private var nextId = 1L

    private fun record(date: String, cents: Long, type: TransactionType = TransactionType.EXPENSE, categoryId: Long = 1): TransactionDetail {
        val entity = TransactionEntity(
            id = nextId++,
            amountCents = cents,
            type = type,
            categoryId = categoryId,
            occurredAt = LocalDateTime.parse(date).toMillis(),
            source = ImportSource.MANUAL,
        )
        return TransactionDetail(entity, if (categoryId == 1L) "餐饮" else "交通", if (categoryId == 1L) 0xFFE67C52 else 0xFF4C8DFF)
    }

    @Test
    fun calculatesDaySummaryAndSevenDayTrend() {
        val records = listOf(
            record("2026-10-07T10:00:00", 1000),
            record("2026-10-07T11:00:00", 20000, TransactionType.INCOME),
            record("2026-10-06T10:00:00", 200),
            record("2026-09-30T23:59:59", 999),
        )
        val result = StatisticsCalculator.calculate(records, StatsPeriod.DAY, LocalDate.of(2026, 10, 7))
        assertEquals(20000L, result.summary.incomeCents)
        assertEquals(1000L, result.summary.expenseCents)
        assertEquals(19000L, result.summary.balanceCents)
        assertEquals(7, result.trend.size)
        assertEquals(200L, result.trend[5].expenseCents)
    }

    @Test
    fun calculatesCategorySharesAndYearTrend() {
        val records = listOf(
            record("2026-01-01T10:00:00", 3000, categoryId = 1),
            record("2026-02-01T10:00:00", 1000, categoryId = 2),
        )
        val month = StatisticsCalculator.calculate(records, StatsPeriod.MONTH, LocalDate.of(2026, 1, 20))
        assertEquals(100.0, month.categories.single().percent, 0.001)
        val year = StatisticsCalculator.calculate(records, StatsPeriod.YEAR, LocalDate.of(2026, 5, 1))
        assertEquals(4000L, year.summary.expenseCents)
        assertEquals(12, year.trend.size)
        assertEquals(3000L, year.trend[0].expenseCents)
        assertEquals(1000L, year.trend[1].expenseCents)
    }

    @Test
    fun analyzesMonthOverMonthAnomaliesAndSuggestions() {
        val records = listOf(
            record("2026-09-10T10:00:00", 10000),
            record("2026-10-01T10:00:00", 1000),
            record("2026-10-02T10:00:00", 1000),
            record("2026-10-03T10:00:00", 30000),
        )
        val analysis = StatisticsCalculator.analyzeMonth(records, YearMonth.of(2026, 10))
        assertEquals(220.0, analysis.monthOverMonth.changePercent!!, 0.001)
        assertEquals(22000L, analysis.monthOverMonth.changeCents)
        assertEquals(1, analysis.anomalies.size)
        assertEquals(30000L, analysis.anomalies.single().transaction.amountCents)
        assertTrue(analysis.suggestions.isNotEmpty())
    }

    @Test
    fun handlesNoPreviousMonthAndNoData() {
        val current = listOf(record("2026-10-01T10:00:00", 1000))
        val analysis = StatisticsCalculator.analyzeMonth(current, YearMonth.of(2026, 10))
        assertNull(analysis.monthOverMonth.changePercent)
        assertEquals(1, analysis.suggestions.size)
        val empty = StatisticsCalculator.analyzeMonth(emptyList(), YearMonth.of(2026, 10))
        assertEquals(0L, empty.summary.expenseCents)
        assertTrue(empty.anomalies.isEmpty())
        assertEquals(1, empty.suggestions.size)
    }
}
