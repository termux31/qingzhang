package com.example.qingzhang.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qingzhang.data.GoalProgress
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.formatCents
import com.example.qingzhang.domain.signedCents
import com.example.qingzhang.domain.weeklySavingsCents
import com.example.qingzhang.ui.LedgerViewModel
import com.example.qingzhang.ui.components.SectionCard
import java.time.LocalDate

@Composable
fun SavingsScreen(viewModel: LedgerViewModel, onBack: () -> Unit, initialGoalId: Long? = null) {
    val goals by viewModel.savingsGoals.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeGoals = goals.filter { !it.goal.archived }
    val archivedGoals = goals.filter { it.goal.archived }
    val day by viewModel.currentDay.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf(initialGoalId) }
    var withdrawing by rememberSaveable { mutableStateOf(false) }
    var recording by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val selected = activeGoals.firstOrNull { it.goal.id == selectedId } ?: activeGoals.firstOrNull()
    val entries = if (selected == null) emptyList() else key(selected.goal.id) {
        val entryFlow = remember { viewModel.observeSavingsEntries(selected.goal.id) }
        entryFlow.collectAsStateWithLifecycle(initialValue = emptyList()).value
    }

    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        var target by rememberSaveable { mutableStateOf("") }
        var hasTargetDate by rememberSaveable { mutableStateOf(false) }
        var targetDay by rememberSaveable { mutableStateOf(day.toLocalDate().plusDays(180).toEpochDay()) }
        BudgetDialog("新建攒钱目标", "保存目标", { creating = false }, submit = {
            selectedId = viewModel.createSavingsGoal(
                name,
                positiveAmount(target),
                if (hasTargetDate) targetDay else null,
            )
            creating = false
        }) {
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("目标名称") }, singleLine = true)
            BudgetAmountField(target, { target = it }, "目标金额")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("设置目标日期", modifier = Modifier.weight(1f))
                Switch(checked = hasTargetDate, onCheckedChange = { hasTargetDate = it })
            }
            if (hasTargetDate) BudgetDateButton("目标日期", targetDay) { targetDay = it }
        }
    }
    if (recording && selected != null) {
        RecordSavingsDialog(viewModel, selected, day.toLocalDate(), withdrawing) { recording = false }
    }

    BudgetPage("攒钱目标", onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Button(onClick = { creating = true }) { Text("新建攒钱目标") } }
            if (selected == null) {
                item { Text("设一个目标，记录每笔存入和取出。") }
            } else {
                item { GoalCard(viewModel, selected, day.toLocalDate(), onRecord = { withdraw ->
                    withdrawing = withdraw
                    recording = true
                }) }
                items(activeGoals.filter { it.goal.id != selected.goal.id }, key = { "goal-${it.goal.id}" }) { progress ->
                    OutlinedButton(onClick = { selectedId = progress.goal.id }, modifier = Modifier.fillMaxWidth()) {
                        Text("${progress.goal.name} · 已攒 ${formatCents(progress.savedCents)}")
                    }
                }
                item { Text("攒钱明细", fontWeight = FontWeight.SemiBold) }
                if (entries.isEmpty()) item { Text("暂无明细") }
                items(entries, key = { it.id }) { entry ->
                    val label = when {
                        entry.fromPlan -> "生活费转入"
                        entry.amountCents < 0 -> "取出"
                        else -> "存入"
                    }
                    Text("${LocalDate.ofEpochDay(entry.day)} · $label ${signedCents(kotlin.math.abs(entry.amountCents), entry.amountCents > 0)}")
                }
            }
            if (archivedGoals.isNotEmpty()) {
                item {
                    TextButton(onClick = { showHistory = !showHistory }) {
                        Text(if (showHistory) "收起历史目标" else "历史目标（${archivedGoals.size}）")
                    }
                }
                if (showHistory) {
                    items(archivedGoals, key = { "history-goal-${it.goal.id}" }) { progress ->
                        Text(
                            "${progress.goal.name} · 已攒 ${formatCents(progress.savedCents)} / ${formatCents(progress.goal.targetCents)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalCard(
    viewModel: LedgerViewModel,
    progress: GoalProgress,
    today: LocalDate,
    onRecord: (Boolean) -> Unit,
) {
    val goal = progress.goal
    val remaining = (goal.targetCents - progress.savedCents).coerceAtLeast(0)
    val achieved = progress.savedCents >= goal.targetCents
    var editing by rememberSaveable(goal.id) { mutableStateOf(false) }
    var deleting by rememberSaveable(goal.id) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    if (editing) {
        var name by rememberSaveable(goal.id) { mutableStateOf(goal.name) }
        var target by rememberSaveable(goal.id) { mutableStateOf(formatCents(goal.targetCents, false)) }
        BudgetDialog("修改攒钱目标", "保存修改", { editing = false }, submit = {
            viewModel.updateSavingsGoal(goal.id, name, positiveAmount(target))
            editing = false
        }) {
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("目标名称") }, singleLine = true)
            BudgetAmountField(target, { target = it }, "目标金额")
            Text("已有存入、取出和生活费转入记录继续保留。", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除攒钱目标？") },
            text = {
                Text(
                    "将删除目标「${goal.name}」及它的全部攒钱明细。账本收支保持不变；关联生活费计划的已转入金额仍计入已攒，仅释放尚未转入的预留。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    viewModel.deleteSavingsGoal(goal.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } },
        )
    }

    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(goal.name, fontWeight = FontWeight.SemiBold)
                    goal.targetDay?.let { Text("目标日期 ${LocalDate.ofEpochDay(it)}", style = MaterialTheme.typography.bodySmall) }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "更多") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("修改名称与金额") }, onClick = {
                            menuExpanded = false
                            editing = true
                        })
                        if (achieved) {
                            DropdownMenuItem(text = { Text("归档目标") }, onClick = {
                                menuExpanded = false
                                viewModel.archiveSavingsGoal(goal.id)
                            })
                        }
                        DropdownMenuItem(text = { Text("删除") }, onClick = {
                            menuExpanded = false
                            deleting = true
                        })
                    }
                }
            }
            Text(
                if (achieved) "目标已达成" else "还差 ${formatCents(remaining)}",
                style = MaterialTheme.typography.titleMedium,
                color = if (achieved) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            weeklySavingsCents(remaining, today, goal.targetDay)?.let { weekly ->
                Text("平均每周存 ${formatCents(weekly)}", style = MaterialTheme.typography.bodyMedium)
            }
            val progressValue = (progress.savedCents.toFloat() / goal.targetCents.toFloat()).coerceIn(0f, 1f)
            Text("已攒 ${formatCents(progress.savedCents)} / ${formatCents(goal.targetCents)}")
            LinearProgressIndicator(progress = { progressValue }, modifier = Modifier.fillMaxWidth())
            if (!achieved) {
                val percent = progress.savedCents * 100 / goal.targetCents
                Text("$percent% 已完成")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRecord(false) }) { Text("存入") }
                OutlinedButton(onClick = { onRecord(true) }, enabled = progress.savedCents > 0) { Text("取出") }
            }
        }
    }
}

@Composable
private fun RecordSavingsDialog(viewModel: LedgerViewModel, goal: GoalProgress, today: LocalDate, withdrawing: Boolean, onDismiss: () -> Unit) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var amount by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var asIncome by rememberSaveable { mutableStateOf(false) }
    val title = if (withdrawing) "取出" else "存入"

    BudgetDialog(title, "确认$title", onDismiss, submit = {
        val cents = positiveAmount(amount)
        val incomeCategory = if (asIncome && !withdrawing) {
            requireNotNull(categories.firstOrNull { it.type == TransactionType.INCOME && it.name == "其他收入" }) {
                "收入分类尚未就绪，请重试"
            }.id
        } else null
        viewModel.recordSavings(goal.goal.id, if (withdrawing) -cents else cents, LocalDate.ofEpochDay(date), incomeCategory)
        onDismiss()
    }) {
        BudgetAmountField(amount, { amount = it }, "${title}金额")
        BudgetDateButton("日期", date) { date = it }
        if (!withdrawing) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("同时记为收入", modifier = Modifier.weight(1f))
                Switch(checked = asIncome, onCheckedChange = { asIncome = it })
            }
            Text("适用于新收到且尚未记账的钱。")
        }
    }
}
