package com.example.qingzhang.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qingzhang.data.LivingPlan
import com.example.qingzhang.data.PlanProgress
import com.example.qingzhang.domain.LIVING_PLAN_DAYS
import com.example.qingzhang.domain.LivingBudget
import com.example.qingzhang.domain.budgetOn
import com.example.qingzhang.domain.endDate
import com.example.qingzhang.domain.formatCents
import com.example.qingzhang.domain.formatDate
import com.example.qingzhang.domain.parseAmountCents
import com.example.qingzhang.domain.startDate
import com.example.qingzhang.ui.LedgerViewModel
import com.example.qingzhang.ui.components.SectionCard
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LivingPlansScreen(viewModel: LedgerViewModel, onBack: () -> Unit, onSavings: () -> Unit, initialPlanId: Long? = null) {
    val day by viewModel.currentDay.collectAsStateWithLifecycle()
    val plans by viewModel.livingPlans.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = day.toLocalDate()
    val activePlans = plans.filter { !it.plan.archived }
    val archivedPlans = plans.filter { it.plan.archived }
    var selectedId by rememberSaveable { mutableStateOf(initialPlanId) }
    var template by remember { mutableStateOf<LivingPlan?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val selected = activePlans.firstOrNull { it.plan.id == selectedId }
        ?: activePlans.firstOrNull { today in it.plan.startDate..it.plan.endDate }
        ?: activePlans.firstOrNull()

    template?.let { draft ->
        CreatePlanDialog(viewModel, today, draft, onDismiss = { template = null }, onSave = {
            selectedId = it
            template = null
        })
    }

    BudgetPage("大学生活费计划", onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Button(onClick = { template = emptyPlanTemplate(today) }) { Text("新建 30 天计划") } }
            item { TextButton(onClick = onSavings) { Text("管理攒钱目标") } }
            if (selected == null) {
                item { Text("选择任意开始日期，连续 30 天汇总账本支出。") }
            } else {
                item {
                    key(selected.plan.id, day.zone) {
                        PlanDetails(viewModel, selected, day, onRenew = { previous ->
                            template = previous.copy(
                                id = 0,
                                startDay = previous.endDate.plusDays(1).toEpochDay(),
                                reserveCents = if (selected.goalArchived) 0 else previous.reserveCents,
                                goalId = previous.goalId.takeUnless { selected.goalArchived },
                                incomeId = null,
                                archived = false,
                            )
                        })
                    }
                }
                items(activePlans.filter { it.plan.id != selected.plan.id }, key = { "plan-${it.plan.id}" }) { progress ->
                    OutlinedButton(onClick = { selectedId = progress.plan.id }, modifier = Modifier.fillMaxWidth()) {
                        Text("${progress.plan.startDate} 至 ${progress.plan.endDate}")
                    }
                }
            }
            if (archivedPlans.isNotEmpty()) {
                item {
                    TextButton(onClick = { showHistory = !showHistory }) {
                        Text(if (showHistory) "收起历史计划" else "历史计划（${archivedPlans.size}）")
                    }
                }
                if (showHistory) {
                    items(archivedPlans, key = { "history-${it.plan.id}" }) { progress ->
                        Text(
                            "${progress.plan.startDate} 至 ${progress.plan.endDate} · 生活费 ${formatCents(progress.plan.budgetCents)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanDetails(
    viewModel: LedgerViewModel,
    progress: PlanProgress,
    day: ZonedDateTime,
    onRenew: (LivingPlan) -> Unit,
) {
    val plan = progress.plan
    val spending = remember(plan, day.zone) { viewModel.observePlanSpending(plan, day.zone) }
    val spent by spending.collectAsStateWithLifecycle(initialValue = null)
    val today = day.toLocalDate()
    val budget = spent?.let { plan.budgetOn(today, it, progress.savedCents, progress.goalArchived) }
    val settled = today > plan.endDate
    val status = when {
        today < plan.startDate -> "未开始"
        settled -> "已结算"
        else -> "第 ${today.toEpochDay() - plan.startDay + 1} / $LIVING_PLAN_DAYS 天"
    }
    var transferring by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    if (transferring && budget != null) {
        var amount by rememberSaveable { mutableStateOf("") }
        BudgetDialog("转入攒钱目标", "确认转入", { transferring = false }, submit = {
            viewModel.transferPlanSavings(plan.id, positiveAmount(amount), day.zone)
            transferring = false
        }) {
            Text("可转入 ${formatCents(budget.transferableCents)}")
            BudgetAmountField(amount, { amount = it }, "转入金额")
        }
    }

    if (editing) {
        var budgetAmount by rememberSaveable(plan.id) { mutableStateOf(formatCents(plan.budgetCents, false)) }
        var reserveAmount by rememberSaveable(plan.id) { mutableStateOf(formatCents(plan.reserveCents, false)) }
        BudgetDialog("修改生活费计划", "保存修改", { editing = false }, submit = {
            val reserve = if (reserveAmount.isBlank()) 0L else positiveAmount(reserveAmount)
            viewModel.updateLivingPlan(plan.id, positiveAmount(budgetAmount), reserve)
            editing = false
        }) {
            BudgetAmountField(budgetAmount, { budgetAmount = it }, "本期生活费")
            BudgetAmountField(reserveAmount, { reserveAmount = it }, "计划攒钱预留")
            Text("已有账目、转入记录和计划日期保持不变。", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除生活费计划？") },
            text = {
                Text(
                    "将删除 ${plan.startDate} 至 ${plan.endDate} 这期计划。账本收支保持不变；已转入攒钱目标的金额继续保留。删除后可按原开始日期重新创建。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    viewModel.deleteLivingPlan(plan.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } },
        )
    }

    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("${plan.startDate} 至 ${plan.endDate}", fontWeight = FontWeight.SemiBold)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "更多") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("修改预算与预留") }, onClick = {
                            menuExpanded = false
                            editing = true
                        })
                        if (settled) {
                            DropdownMenuItem(text = { Text("归档本期") }, onClick = {
                                menuExpanded = false
                                viewModel.archiveLivingPlan(plan.id)
                            })
                        }
                        DropdownMenuItem(text = { Text("删除") }, onClick = {
                            menuExpanded = false
                            deleting = true
                        })
                    }
                }
            }
            val dailyCents = budget?.dailyCents
            when {
                today < plan.startDate -> Text("计划将在 ${plan.startDate} 开始", style = MaterialTheme.typography.titleMedium)
                dailyCents != null -> Text(
                    "建议今天花 ${formatCents(dailyCents)}，还剩 ${budget.daysLeft} 天",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                settled -> Text("本期已结束，可开启下一期", style = MaterialTheme.typography.titleMedium)
                else -> Text("正在读取本期支出…", style = MaterialTheme.typography.titleMedium)
            }
            Text("本期生活费 ${formatCents(plan.budgetCents)}")
            Text("计划攒钱 ${formatCents(plan.reserveCents)}")
            Text("攒钱目标：${progress.goalName ?: "未关联"}")
            if (progress.goalArchived) {
                Text("目标已归档，尚未转入的预留已计入可用余额。", style = MaterialTheme.typography.bodySmall)
            }
            progress.incomeCents?.let { Text("已关联收入 ${formatCents(it)}") }
            spent?.let { Text("本期支出 ${formatCents(it)}") }
            if (budget != null) PlanMetrics(budget, progress.savedCents)
            if (budget != null && budget.transferableCents > 0) {
                Button(onClick = { transferring = true }) { Text("转入攒钱目标") }
            }
            if (settled) OutlinedButton(onClick = { onRenew(plan) }) { Text("开启下一期") }
        }
    }
}

@Composable
internal fun PlanMetrics(budget: LivingBudget, savedCents: Long) {
    val remainingLabel = when {
        budget.remainingCents < 0 -> "已超支"
        budget.daysLeft == 0L -> "结算剩余"
        else -> "剩余可花"
    }
    Text("$remainingLabel ${formatCents(if (budget.remainingCents < 0) -budget.remainingCents else budget.remainingCents)}")
    Text("本期已攒 ${formatCents(savedCents)}")
}

@Composable
private fun CreatePlanDialog(
    viewModel: LedgerViewModel,
    today: LocalDate,
    draft: LivingPlan,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
) {
    val goals by viewModel.savingsGoals.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeGoals = goals.filter { !it.goal.archived }
    var startDay by rememberSaveable(draft.startDay) { mutableStateOf(draft.startDay) }
    var budget by rememberSaveable(draft.startDay, draft.budgetCents) {
        mutableStateOf(if (draft.budgetCents > 0) formatCents(draft.budgetCents, false) else "")
    }
    var reserve by rememberSaveable(draft.startDay, draft.reserveCents) {
        mutableStateOf(if (draft.reserveCents > 0) formatCents(draft.reserveCents, false) else "")
    }
    var goalId by rememberSaveable(draft.startDay, draft.goalId) { mutableStateOf(draft.goalId) }
    var incomeId by rememberSaveable(draft.startDay) { mutableStateOf(draft.incomeId) }
    var incomeLabel by rememberSaveable(draft.startDay) { mutableStateOf("") }
    var choosingGoal by remember { mutableStateOf(false) }
    var choosingIncome by rememberSaveable { mutableStateOf(false) }

    if (choosingIncome) {
        val incomes by viewModel.planIncomes.collectAsStateWithLifecycle(initialValue = emptyList())
        AlertDialog(
            onDismissRequest = { choosingIncome = false },
            title = { Text("选择已记账的收入") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    item {
                        TextButton(onClick = {
                            incomeId = null
                            incomeLabel = ""
                            choosingIncome = false
                        }) { Text("不关联收入") }
                    }
                    items(incomes, key = { it.id }) { entry ->
                        val label = "${entry.occurredAt.formatDate()} · ${formatCents(entry.amountCents)}"
                        TextButton(onClick = {
                            incomeId = entry.id
                            incomeLabel = label
                            choosingIncome = false
                        }) { Text(if (entry.note.isBlank()) label else "$label · ${entry.note}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingIncome = false }) { Text("返回") } },
        )
    } else {
        BudgetDialog(if (draft.budgetCents > 0) "开启下一期" else "新建生活费计划", "保存计划", onDismiss, submit = {
            val reserved = if (reserve.isBlank() || reserve.trim() in listOf("0", "0.0", "0.00")) 0L else positiveAmount(reserve)
            onSave(viewModel.createLivingPlan(LivingPlan(
                startDay = startDay,
                budgetCents = positiveAmount(budget),
                reserveCents = reserved,
                goalId = goalId,
                incomeId = incomeId,
            )))
        }) {
            BudgetDateButton("开始日期", startDay) { startDay = it }
            Text("结束日期 ${LocalDate.ofEpochDay(startDay).plusDays(LIVING_PLAN_DAYS - 1)}（含当天）")
            BudgetAmountField(budget, { budget = it }, "本期生活费")
            BudgetAmountField(reserve, { reserve = it }, "计划攒钱（可留空）")
            OutlinedButton(onClick = { choosingGoal = true }) {
                Text(activeGoals.firstOrNull { it.goal.id == goalId }?.goal?.name ?: "选择攒钱目标")
            }
            DropdownMenu(expanded = choosingGoal, onDismissRequest = { choosingGoal = false }) {
                DropdownMenuItem(text = { Text("不关联目标") }, onClick = { goalId = null; choosingGoal = false })
                activeGoals.forEach { progress ->
                    DropdownMenuItem(text = { Text(progress.goal.name) }, onClick = {
                        goalId = progress.goal.id
                        choosingGoal = false
                    })
                }
            }
            if (activeGoals.isEmpty()) Text("可先在「攒钱目标」创建一个目标。")
            OutlinedButton(onClick = { choosingIncome = true }) {
                Text(incomeLabel.ifBlank { "关联已有收入（可选）" })
            }
        }
    }
}

private fun emptyPlanTemplate(today: LocalDate) = LivingPlan(
    startDay = today.toEpochDay(),
    budgetCents = 0,
    reserveCents = 0,
    goalId = null,
    incomeId = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetPage(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        content = content,
    )
}

@Composable
internal fun BudgetDialog(
    title: String,
    action: String,
    onDismiss: () -> Unit,
    submit: suspend () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                if (saving) return@TextButton
                saving = true
                scope.launch {
                    try {
                        submit()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (invalid: IllegalArgumentException) {
                        error = invalid.message ?: "请检查输入"
                    } catch (_: Exception) {
                        error = "保存失败，请重试"
                    } finally {
                        saving = false
                    }
                }
            }) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

@Composable
internal fun BudgetAmountField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        prefix = { Text("¥ ") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

internal fun positiveAmount(value: String): Long =
    requireNotNull(parseAmountCents(value)) { "请输入大于 0 且最多两位小数的有效金额" }

@Composable
internal fun BudgetDateButton(label: String, epochDay: Long, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    val date = LocalDate.ofEpochDay(epochDay)
    OutlinedButton(onClick = {
        DatePickerDialog(context, { _, year, month, day ->
            onChange(LocalDate.of(year, month + 1, day).toEpochDay())
        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }) { Text("$label $date") }
}
