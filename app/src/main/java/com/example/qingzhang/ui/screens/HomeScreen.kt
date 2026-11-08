package com.example.qingzhang.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.budgetOn
import com.example.qingzhang.domain.formatCents
import com.example.qingzhang.domain.toLocalDate
import com.example.qingzhang.ui.LedgerViewModel
import com.example.qingzhang.ui.components.EmptyState
import com.example.qingzhang.ui.components.SectionCard
import com.example.qingzhang.ui.components.SummaryStrip
import com.example.qingzhang.ui.components.TransactionList
import java.time.YearMonth

@Composable
fun HomeScreen(
    viewModel: LedgerViewModel,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onStats: () -> Unit,
    onBills: () -> Unit,
    onEdit: (Long) -> Unit,
    onLimit: () -> Unit,
    onLivingPlans: () -> Unit,
) {
    val home by viewModel.homeContent.collectAsStateWithLifecycle()
    val records = home.records
    val recent by viewModel.recentRecords.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activePlan by viewModel.activeLivingPlan.collectAsStateWithLifecycle()
    val income = records.filter { it.transaction.type == TransactionType.INCOME }.sumOf { it.transaction.amountCents }
    val expense = records.filter { it.transaction.type == TransactionType.EXPENSE }.sumOf { it.transaction.amountCents }
    val todayExpense = records.filter {
        it.transaction.type == TransactionType.EXPENSE &&
            it.transaction.occurredAt.toLocalDate(home.day.zone) == home.day.toLocalDate()
    }.sumOf { it.transaction.amountCents }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("轻账", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("${YearMonth.from(home.day)} · 每一笔都清楚", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onLimit) { Text("限额设置") }
            }
        }
        item {
            SectionCard {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("本月收支", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    SummaryStrip(income, expense)
                }
            }
        }
        item {
            SectionCard {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("今日支出", fontWeight = FontWeight.SemiBold)
                        Text(formatCents(todayExpense), color = MaterialTheme.colorScheme.primary)
                    }
                    if (settings.dailyLimitEnabled) {
                        val progress = (todayExpense.toFloat() / settings.dailyLimitCents.toFloat()).coerceIn(0f, 1f)
                        val progressColor = if (todayExpense > settings.dailyLimitCents) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                        val trackColor = MaterialTheme.colorScheme.surfaceVariant
                        Canvas(modifier = Modifier.fillMaxWidth().height(7.dp)) {
                            val radius = size.height / 2
                            drawRoundRect(trackColor, cornerRadius = CornerRadius(radius, radius))
                            if (progress > 0f) {
                                drawRoundRect(
                                    progressColor,
                                    size = Size(size.width * progress, size.height),
                                    cornerRadius = CornerRadius(radius, radius),
                                )
                            }
                        }
                        Text(
                            if (todayExpense > settings.dailyLimitCents) {
                                "已超出限额 ${formatCents(todayExpense - settings.dailyLimitCents)}"
                            } else {
                                "每日限额 ${formatCents(settings.dailyLimitCents)}，还可支出 ${formatCents(settings.dailyLimitCents - todayExpense)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (todayExpense > settings.dailyLimitCents) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("每日限额提醒已关闭", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        activePlan?.let { (progress, spentCents) ->
            item {
                SectionCard(Modifier.clickable(onClick = onLivingPlans)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("大学生活费计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        PlanMetrics(progress.plan.budgetOn(home.day.toLocalDate(), spentCents, progress.savedCents), progress.savedCents)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAdd, modifier = Modifier.weight(1f)) { Text("记一笔") }
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("导入账单") }
                OutlinedButton(onClick = onStats, modifier = Modifier.weight(1f)) { Text("看统计") }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("最近账目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onBills) { Text("查看全部") }
            }
        }
        item {
            SectionCard {
                if (recent.isEmpty()) {
                    EmptyState("还没有账目", "点击“记一笔”，或导入微信、支付宝账单")
                } else {
                    TransactionList(recent.take(10), onEdit)
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}
