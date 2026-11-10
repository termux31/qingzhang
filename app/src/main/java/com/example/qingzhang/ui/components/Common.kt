package com.example.qingzhang.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.qingzhang.data.BudgetWarning
import com.example.qingzhang.data.TransactionDetail
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.formatCents
import com.example.qingzhang.domain.formatDateTime
import com.example.qingzhang.domain.signedCents

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { content() }
}

@Composable
fun SummaryStrip(incomeCents: Long, expenseCents: Long, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("收入", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatCents(incomeCents), color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("支出", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatCents(expenseCents), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("结余", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatCents(incomeCents - expenseCents), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun TransactionRow(detail: TransactionDetail, onClick: () -> Unit) {
    val transaction = detail.transaction
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(Color(detail.categoryColor).copy(alpha = 0.13f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                detail.categoryName.take(1),
                color = Color(detail.categoryColor),
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                transaction.merchant.ifBlank { detail.categoryName },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${detail.categoryName} · ${transaction.occurredAt.formatDateTime()}" +
                    (if (transaction.extraDailyExpense) " · 额外" else "") +
                    if (transaction.note.isNotBlank()) " · ${transaction.note}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            signedCents(transaction.amountCents, transaction.type == TransactionType.INCOME),
            color = if (transaction.type == TransactionType.INCOME) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun EmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TransactionList(records: List<TransactionDetail>, onEdit: (Long) -> Unit) {
    records.forEachIndexed { index, detail ->
        TransactionRow(detail = detail, onClick = { onEdit(detail.transaction.id) })
        if (index < records.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 70.dp), thickness = 0.5.dp)
    }
}

@Composable
fun BudgetWarningsDialog(warnings: List<BudgetWarning>, onDismiss: () -> Unit) {
    if (warnings.isEmpty()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当日支出已超出限额") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                warnings.take(8).forEach { warning ->
                    Text(
                        "${warning.date.monthValue}月${warning.date.dayOfMonth}日：支出 ${formatCents(warning.totalCents)}，" +
                            "限额 ${formatCents(warning.limitCents)}，超出 ${formatCents(warning.exceededCents)}。",
                    )
                }
                if (warnings.size > 8) Text("另有 ${warnings.size - 8} 天超出限额，可在统计页按日查看。")
                Text("账目已保存，请核对本次消费并调整后续支出。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("我知道了") } },
    )
}
