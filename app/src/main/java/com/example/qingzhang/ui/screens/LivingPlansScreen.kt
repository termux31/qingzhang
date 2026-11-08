package com.example.qingzhang.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
    var selectedId by rememberSaveable { mutableStateOf(initialPlanId) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val selected = plans.firstOrNull { it.plan.id == selectedId }
        ?: plans.firstOrNull { today in it.plan.startDate..it.plan.endDate }
        ?: plans.firstOrNull()

    if (creating) {
        CreatePlanDialog(viewModel, today, onDismiss = { creating = false }, onSave = {
            selectedId = it
            creating = false
        })
    }

    BudgetPage("大学生活费计划", onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Button(onClick = { creating = true }) { Text("新建 30 天计划") } }
            item { TextButton(onClick = onSavings) { Text("管理攒钱目标") } }
            if (selected == null) {
                item { Text("选择任意开始日期，连续 30 天汇总账本支出。") }
            } else {
                item {
                    key(selected.plan.id, day.zone) {
                        PlanDetails(viewModel, selected, day)
                    }
                }
                items(plans.filter { it.plan.id != selected.plan.id }, key = { it.plan.id }) { progress ->
                    OutlinedButton(onClick = { selectedId = progress.plan.id }, modifier = Modifier.fillMaxWidth()) {
                        Text("${progress.plan.startDate} 至 ${progress.plan.endDate}")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanDetails(viewModel: LedgerViewModel, progress: PlanProgress, day: ZonedDateTime) {
    val plan = progress.plan
    val spending = remember(plan, day.zone) { viewModel.observePlanSpending(plan, day.zone) }
    val spent by spending.collectAsStateWithLifecycle(initialValue = null)
    var transferring by rememberSaveable { mutableStateOf(false) }
    val today = day.toLocalDate()
    val budget = spent?.let { plan.budgetOn(today, it, progress.savedCents) }
    val status = when {
        today < plan.startDate -> "未开始"
        today > plan.endDate -> "已结算"
        else -> "第 ${today.toEpochDay() - plan.startDay + 1} / $LIVING_PLAN_DAYS 天"
    }

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

    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${plan.startDate} 至 ${plan.endDate}", fontWeight = FontWeight.SemiBold)
            Text(status)
            Text("本期生活费 ${formatCents(plan.budgetCents)}")
            Text("计划攒钱 ${formatCents(plan.reserveCents)}")
            Text("攒钱目标：${progress.goalName ?: "未关联"}")
            progress.incomeCents?.let { Text("已关联收入 ${formatCents(it)}") }
            spent?.let { Text("本期支出 ${formatCents(it)}") }
            if (budget != null) {
                PlanMetrics(budget, progress.savedCents)
                if (budget.transferableCents > 0) {
                    Button(onClick = { transferring = true }) { Text("转入攒钱目标") }
                }
            }
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
    budget.dailyCents?.let { Text("日均可花 ${formatCents(it)} · 剩余 ${budget.daysLeft} 天") }
    Text("本期已攒 ${formatCents(savedCents)}")
}

@Composable
private fun CreatePlanDialog(viewModel: LedgerViewModel, today: LocalDate, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val goals by viewModel.savingsGoals.collectAsStateWithLifecycle(initialValue = emptyList())
    var startDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var budget by rememberSaveable { mutableStateOf("") }
    var reserve by rememberSaveable { mutableStateOf("") }
    var goalId by rememberSaveable { mutableStateOf<Long?>(null) }
    var incomeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var incomeLabel by rememberSaveable { mutableStateOf("") }
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
        BudgetDialog("新建生活费计划", "保存计划", onDismiss, submit = {
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
                Text(goals.firstOrNull { it.goal.id == goalId }?.goal?.name ?: "选择攒钱目标")
            }
            DropdownMenu(expanded = choosingGoal, onDismissRequest = { choosingGoal = false }) {
                DropdownMenuItem(text = { Text("不关联目标") }, onClick = { goalId = null; choosingGoal = false })
                goals.forEach { progress ->
                    DropdownMenuItem(text = { Text(progress.goal.name) }, onClick = {
                        goalId = progress.goal.id
                        choosingGoal = false
                    })
                }
            }
            if (goals.isEmpty()) Text("可先在「攒钱目标」创建一个目标。")
            OutlinedButton(onClick = { choosingIncome = true }) {
                Text(incomeLabel.ifBlank { "关联已有收入（可选）" })
            }
        }
    }
}

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
