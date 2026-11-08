package com.example.qingzhang.domain

import com.example.qingzhang.data.TransactionDetail
import com.example.qingzhang.data.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

enum class StatsPeriod(val label: String) { DAY("日"), MONTH("月"), YEAR("年") }

data class PeriodSummary(val incomeCents: Long = 0, val expenseCents: Long = 0) {
    val balanceCents: Long get() = incomeCents - expenseCents
}

data class CategoryShare(
    val categoryId: Long,
    val name: String,
    val color: Long,
    val amountCents: Long,
    val percent: Double,
)

data class TrendPoint(val label: String, val incomeCents: Long, val expenseCents: Long)

data class StatisticsResult(
    val summary: PeriodSummary,
    val categories: List<CategoryShare>,
    val trend: List<TrendPoint>,
)

data class MonthOverMonth(
    val changeCents: Long,
    val changePercent: Double?,
)

data class MonthlyAnalysis(
    val month: YearMonth,
    val summary: PeriodSummary,
    val categories: List<CategoryShare>,
    val monthOverMonth: MonthOverMonth,
    val anomalies: List<TransactionDetail>,
    val suggestions: List<String>,
)

object StatisticsCalculator {
    fun queryRange(period: StatsPeriod, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
        StatsPeriod.DAY -> anchor.minusDays(6) to anchor
        StatsPeriod.MONTH -> YearMonth.from(anchor).minusMonths(1).atDay(1) to YearMonth.from(anchor).atEndOfMonth()
        StatsPeriod.YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(anchor.lengthOfYear())
    }

    fun calculate(records: List<TransactionDetail>, period: StatsPeriod, anchor: LocalDate): StatisticsResult {
        val current = records.filter { it.transaction.occurredAt.toLocalDate().isIn(period, anchor) }
        val summary = current.summary()
        val categories = current.categoryShares()
        val trend = when (period) {
            StatsPeriod.DAY -> (0L..6L).map { offset ->
                val date = anchor.minusDays(6 - offset)
                records.onDate(date).trendPoint("${date.monthValue}/${date.dayOfMonth}")
            }
            StatsPeriod.MONTH -> {
                val month = YearMonth.from(anchor)
                (1..month.lengthOfMonth()).map { day ->
                    records.onDate(month.atDay(day)).trendPoint(day.toString())
                }
            }
            StatsPeriod.YEAR -> (1..12).map { month ->
                val monthRecords = records.filter {
                    val date = it.transaction.occurredAt.toLocalDate()
                    date.year == anchor.year && date.monthValue == month
                }
                monthRecords.trendPoint("${month}月")
            }
        }
        return StatisticsResult(summary, categories, trend)
    }

    fun analyzeMonth(records: List<TransactionDetail>, month: YearMonth): MonthlyAnalysis {
        val current = records.filter {
            YearMonth.from(it.transaction.occurredAt.toLocalDate()) == month
        }
        val previous = records.filter {
            YearMonth.from(it.transaction.occurredAt.toLocalDate()) == month.minusMonths(1)
        }
        val summary = current.summary()
        val previousExpenses = previous.summary().expenseCents
        val change = summary.expenseCents - previousExpenses
        val monthOverMonth = MonthOverMonth(
            changeCents = change,
            changePercent = if (previousExpenses > 0) change.toDouble() * 100 / previousExpenses else null,
        )
        val expenses = current.filter { it.transaction.type == TransactionType.EXPENSE }
        val anomalies = detectAnomalies(expenses)
        val categories = current.categoryShares()
        val suggestions = buildSuggestions(summary, categories, monthOverMonth, anomalies.size)
        return MonthlyAnalysis(month, summary, categories, monthOverMonth, anomalies, suggestions)
    }

    private fun detectAnomalies(expenses: List<TransactionDetail>): List<TransactionDetail> {
        if (expenses.size < 3) return emptyList()
        val sortedAmounts = expenses.map { it.transaction.amountCents }.sorted()
        val middle = sortedAmounts.size / 2
        val median = if (sortedAmounts.size % 2 == 1) {
            sortedAmounts[middle]
        } else {
            val lower = sortedAmounts[middle - 1]
            val upper = sortedAmounts[middle]
            lower + (upper - lower) / 2
        }
        val threshold = maxOf(20_000L, median * 3)
        return expenses.filter { it.transaction.amountCents > threshold }
            .sortedByDescending { it.transaction.amountCents }
            .take(5)
    }

    private fun buildSuggestions(
        summary: PeriodSummary,
        categories: List<CategoryShare>,
        monthOverMonth: MonthOverMonth,
        anomalyCount: Int,
    ): List<String> {
        if (summary.expenseCents == 0L) return listOf("本月暂无支出，记录账目后会自动生成消费建议。")
        val suggestions = mutableListOf<String>()
        val top = categories.firstOrNull()
        if (top != null) {
            suggestions.add(
                "${top.name}是本月最大支出类别，占 ${String.format(Locale.CHINA, "%.1f", top.percent)}%。可先将该类支出减少 10%，预计每月少支出 ${formatCents(top.amountCents / 10)}。",
            )
        }
        if ((monthOverMonth.changePercent ?: 0.0) > 10.0) {
            suggestions.add("本月支出较上月增加超过 10%，建议先核对新增类别和大额账目，再调整下月预算。")
        }
        if (anomalyCount > 0) suggestions.add("发现 $anomalyCount 笔显著高于日常水平的支出，建议核对是否为一次性消费。")
        return suggestions.take(3)
    }
}

private fun List<TransactionDetail>.summary(): PeriodSummary = PeriodSummary(
    incomeCents = filter { it.transaction.type == TransactionType.INCOME }.sumOf { it.transaction.amountCents },
    expenseCents = filter { it.transaction.type == TransactionType.EXPENSE }.sumOf { it.transaction.amountCents },
)

private fun List<TransactionDetail>.categoryShares(): List<CategoryShare> {
    val expenses = filter { it.transaction.type == TransactionType.EXPENSE }
    val total = expenses.sumOf { it.transaction.amountCents }
    return expenses.groupBy { it.transaction.categoryId }.map { (id, records) ->
        val amount = records.sumOf { it.transaction.amountCents }
        val first = records.first()
        CategoryShare(id, first.categoryName, first.categoryColor, amount, percentage(amount, total))
    }.sortedByDescending { it.amountCents }
}

private fun List<TransactionDetail>.onDate(date: LocalDate): List<TransactionDetail> =
    filter { it.transaction.occurredAt.toLocalDate() == date }

private fun List<TransactionDetail>.trendPoint(label: String): TrendPoint {
    val summary = summary()
    return TrendPoint(label, summary.incomeCents, summary.expenseCents)
}

private fun LocalDate.isIn(period: StatsPeriod, anchor: LocalDate): Boolean = when (period) {
    StatsPeriod.DAY -> this == anchor
    StatsPeriod.MONTH -> YearMonth.from(this) == YearMonth.from(anchor)
    StatsPeriod.YEAR -> year == anchor.year
}
