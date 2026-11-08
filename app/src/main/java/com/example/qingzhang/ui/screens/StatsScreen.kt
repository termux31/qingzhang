package com.example.qingzhang.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qingzhang.domain.StatsPeriod
import com.example.qingzhang.domain.formatCents
import com.example.qingzhang.ui.LedgerViewModel
import com.example.qingzhang.ui.components.CategoryDonut
import com.example.qingzhang.ui.components.EmptyState
import com.example.qingzhang.ui.components.SectionCard
import com.example.qingzhang.ui.components.SummaryStrip
import com.example.qingzhang.ui.components.TransactionList
import com.example.qingzhang.ui.components.TrendChart
import java.time.YearMonth
import java.util.Locale

@Composable
fun StatsScreen(viewModel: LedgerViewModel, onEdit: (Long) -> Unit) {
    val content by viewModel.statsContent.collectAsStateWithLifecycle()
    val selection = content.selection
    val statistics = content.statistics
    val periodLabel = when (selection.period) {
        StatsPeriod.DAY -> selection.anchor.toString()
        StatsPeriod.MONTH -> YearMonth.from(selection.anchor).toString()
        StatsPeriod.YEAR -> "${selection.anchor.year}年"
    }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("统计", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = selection.period == period,
                        onClick = { viewModel.selectStatsPeriod(period) },
                        label = { Text("按${period.label}") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.moveStats(-1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一周期")
                }
                Text(periodLabel, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { viewModel.moveStats(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一周期")
                }
            }
        }
        item {
            SectionCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("收支概览", fontWeight = FontWeight.SemiBold)
                    SummaryStrip(statistics.summary.incomeCents, statistics.summary.expenseCents)
                }
            }
        }
        item {
            SectionCard {
                Column(Modifier.padding(16.dp)) {
                    Text("收支趋势", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    TrendChart(statistics.trend)
                }
            }
        }
        item {
            SectionCard {
                Column(Modifier.padding(16.dp)) {
                    Text("支出分类", fontWeight = FontWeight.SemiBold)
                    CategoryDonut(statistics.categories, statistics.summary.expenseCents)
                }
            }
        }
        content.monthlyAnalysis?.let { analysis ->
            item {
                SectionCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("月度消费分析", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val monthOverMonth = analysis.monthOverMonth
                        val changeText = if (monthOverMonth.changePercent == null) {
                            "上月无支出，本月支出为 ${formatCents(analysis.summary.expenseCents)}"
                        } else {
                            val direction = if (monthOverMonth.changeCents >= 0) "增加" else "减少"
                            "较上月$direction ${formatCents(kotlin.math.abs(monthOverMonth.changeCents))}（${String.format(Locale.CHINA, "%+.1f", monthOverMonth.changePercent)}%）"
                        }
                        Text(
                            changeText,
                            color = if (monthOverMonth.changeCents > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        )
                        Text("节省建议", fontWeight = FontWeight.SemiBold)
                        analysis.suggestions.forEach { suggestion ->
                            Text("• $suggestion", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                SectionCard {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text(
                            "异常支出",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "单笔金额超过 ¥200 且高于本月支出中位数的 3 倍",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (analysis.anomalies.isEmpty()) {
                            EmptyState("未发现异常支出", "当前规则下没有需重点核对的大额账目")
                        } else {
                            TransactionList(analysis.anomalies, onEdit)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}
