package com.example.qingzhang.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.example.qingzhang.data.BudgetWarning
import com.example.qingzhang.data.TransactionInput
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.formatCents
import com.example.qingzhang.domain.parseAmountCents
import com.example.qingzhang.domain.toLocalDateTime
import com.example.qingzhang.domain.toMillis
import com.example.qingzhang.ui.LedgerViewModel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(
    transactionId: Long?,
    viewModel: LedgerViewModel,
    onBack: () -> Unit,
    onWarning: (List<BudgetWarning>) -> Unit,
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable(transactionId) { mutableStateOf("") }
    var type by rememberSaveable(transactionId) { mutableStateOf(TransactionType.EXPENSE) }
    var categoryId by rememberSaveable(transactionId) { mutableStateOf<Long?>(null) }
    var dateTime by rememberSaveable(transactionId) { mutableStateOf(LocalDateTime.now().withSecond(0).withNano(0)) }
    var merchant by rememberSaveable(transactionId) { mutableStateOf("") }
    var note by rememberSaveable(transactionId) { mutableStateOf("") }
    var extraDailyExpense by rememberSaveable(transactionId) { mutableStateOf(false) }
    var loaded by rememberSaveable(transactionId) { mutableStateOf(transactionId == null) }
    var loadFailed by rememberSaveable(transactionId) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(transactionId) {
        if (transactionId != null && !loaded) {
            val detail = viewModel.getTransaction(transactionId)
            if (detail == null) {
                loadFailed = true
            } else {
                amount = formatCents(detail.transaction.amountCents, currency = false)
                type = detail.transaction.type
                categoryId = detail.transaction.categoryId
                dateTime = detail.transaction.occurredAt.toLocalDateTime()
                merchant = detail.transaction.merchant
                note = detail.transaction.note
                extraDailyExpense = detail.transaction.extraDailyExpense
            }
            loaded = true
        }
    }
    LaunchedEffect(loaded, type, categories) {
        if (!loaded || categories.isEmpty()) return@LaunchedEffect
        val available = categories.filter { it.type == type }
        if (available.none { it.id == categoryId }) categoryId = available.firstOrNull()?.id
    }

    if (showDatePicker) {
        LaunchedEffect(Unit) {
            DatePickerDialog(context, { _, year, month, day ->
                dateTime = dateTime.withYear(year).withMonth(month + 1).withDayOfMonth(day)
                showDatePicker = false
            }, dateTime.year, dateTime.monthValue - 1, dateTime.dayOfMonth).apply {
                setOnCancelListener { showDatePicker = false }
            }.show()
        }
    }
    if (showTimePicker) {
        LaunchedEffect(Unit) {
            TimePickerDialog(context, { _, hour, minute ->
                dateTime = dateTime.withHour(hour).withMinute(minute)
                showTimePicker = false
            }, dateTime.hour, dateTime.minute, true).apply {
                setOnCancelListener { showTimePicker = false }
            }.show()
        }
    }

    if (confirmDelete && transactionId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这笔账目？") },
            text = { Text("删除后无法恢复，相关统计会同步更新。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteTransaction(transactionId, onBack)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (transactionId == null) "记一笔" else "编辑账目") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (transactionId != null) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "删除") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (!loaded) {
                item { Text("正在读取账目…") }
                return@LazyColumn
            }
            if (loadFailed) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("账目不存在或已被删除")
                        Button(onClick = onBack) { Text("返回账单") }
                    }
                }
                return@LazyColumn
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TransactionType.entries.forEach { itemType ->
                        FilterChip(
                            selected = type == itemType,
                            onClick = { type = itemType },
                            label = { Text(itemType.label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it; error = "" },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("金额") },
                    placeholder = { Text("0.00") },
                    prefix = { Text("¥ ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    isError = error.isNotBlank(),
                )
            }
            if (type == TransactionType.EXPENSE) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("每日额外支出", fontWeight = FontWeight.SemiBold)
                            Text("不计入每日限额，仍计入账本和统计", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = extraDailyExpense, onCheckedChange = { extraDailyExpense = it })
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("分类", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        categories.filter { it.type == type }.forEach { category ->
                            FilterChip(
                                selected = categoryId == category.id,
                                onClick = { categoryId = category.id },
                                label = { Text(category.name) },
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                    }
                    OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(dateTime.format(DateTimeFormatter.ofPattern("HH:mm")))
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("商户 / 来源（选填）") },
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备注（选填）") },
                    minLines = 2,
                    maxLines = 4,
                    supportingText = { Text("${note.length}/200") },
                )
            }
            if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            item {
                Button(
                    onClick = {
                        val cents = parseAmountCents(amount)
                        val selectedCategory = categoryId
                        when {
                            cents == null -> error = "请输入大于 0 且最多两位小数的金额"
                            selectedCategory == null -> error = "请选择分类"
                            else -> {
                                saving = true
                                scope.launch {
                                    viewModel.saveTransaction(
                                        TransactionInput(
                                            id = transactionId ?: 0,
                                            amountCents = cents,
                                            type = type,
                                            categoryId = selectedCategory,
                                            occurredAt = dateTime.toMillis(),
                                            note = note,
                                            merchant = merchant,
                                            extraDailyExpense = type == TransactionType.EXPENSE && extraDailyExpense,
                                        ),
                                    ).onSuccess { outcome ->
                                        outcome.warning?.let { onWarning(listOf(it)) }
                                        onBack()
                                    }.onFailure {
                                        error = it.message ?: "保存失败"
                                        saving = false
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !saving,
                ) { Text(if (saving) "保存中…" else "保存账目") }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
