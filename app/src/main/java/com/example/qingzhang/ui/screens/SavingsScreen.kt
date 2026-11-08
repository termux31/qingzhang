package com.example.qingzhang.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.example.qingzhang.ui.LedgerViewModel
import com.example.qingzhang.ui.components.SectionCard
import java.time.LocalDate

@Composable
fun SavingsScreen(viewModel: LedgerViewModel, onBack: () -> Unit, initialGoalId: Long? = null) {
    val goals by viewModel.savingsGoals.collectAsStateWithLifecycle(initialValue = emptyList())
    val day by viewModel.currentDay.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf(initialGoalId) }
    var withdrawing by rememberSaveable { mutableStateOf(false) }
    var recording by rememberSaveable { mutableStateOf(false) }
    val selected = goals.firstOrNull { it.goal.id == selectedId } ?: goals.firstOrNull()
    val entries = if (selected == null) emptyList() else key(selected.goal.id) {
        val entryFlow = remember { viewModel.observeSavingsEntries(selected.goal.id) }
        entryFlow.collectAsStateWithLifecycle(initialValue = emptyList()).value
    }

    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        var target by rememberSaveable { mutableStateOf("") }
        BudgetDialog("新建攒钱目标", "保存目标", { creating = false }, submit = {
            selectedId = viewModel.createSavingsGoal(name, positiveAmount(target))
            creating = false
        }) {
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("目标名称") }, singleLine = true)
            BudgetAmountField(target, { target = it }, "目标金额")
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
                item {
                    val goal = selected.goal
                    val progress = (selected.savedCents.toFloat() / goal.targetCents.toFloat()).coerceIn(0f, 1f)
                    SectionCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(goal.name, fontWeight = FontWeight.SemiBold)
                            Text("已攒 ${formatCents(selected.savedCents)} / ${formatCents(goal.targetCents)}")
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            if (selected.savedCents >= goal.targetCents) {
                                Text("已达成")
                            } else {
                                val percent = selected.savedCents * 100 / goal.targetCents
                                Text("$percent% · 还差 ${formatCents(goal.targetCents - selected.savedCents)}")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { withdrawing = false; recording = true }) { Text("存入") }
                                OutlinedButton(
                                    onClick = { withdrawing = true; recording = true },
                                    enabled = selected.savedCents > 0,
                                ) { Text("取出") }
                            }
                        }
                    }
                }
                items(goals.filter { it.goal.id != selected.goal.id }, key = { "goal-${it.goal.id}" }) { progress ->
                    OutlinedButton(onClick = { selectedId = progress.goal.id }, modifier = Modifier.fillMaxWidth()) {
                        Text("${progress.goal.name} · 已攒 ${formatCents(progress.savedCents)}")
                    }
                }
                item { Text("攒钱明细", fontWeight = FontWeight.SemiBold) }
                if (entries.isEmpty()) item { Text("暂无明细") }
                items(entries, key = { it.id }) { entry ->
                    val label = when {
                        entry.planId != null -> "生活费转入"
                        entry.amountCents < 0 -> "取出"
                        else -> "存入"
                    }
                    Text("${LocalDate.ofEpochDay(entry.day)} · $label ${signedCents(kotlin.math.abs(entry.amountCents), entry.amountCents > 0)}")
                }
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
