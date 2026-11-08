package com.example.qingzhang.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qingzhang.data.CategoryEntity
import com.example.qingzhang.data.ImportSource
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.formatCents
import com.example.qingzhang.domain.formatDateTime
import com.example.qingzhang.domain.parseAmountCents
import com.example.qingzhang.domain.toLocalDateTime
import com.example.qingzhang.domain.toMillis
import com.example.qingzhang.importer.ImportCandidate
import com.example.qingzhang.ui.ImportViewModel
import com.example.qingzhang.ui.LedgerViewModel
import com.example.qingzhang.ui.canSelect
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(importViewModel: ImportViewModel, ledgerViewModel: LedgerViewModel, onBack: () -> Unit) {
    val state by importViewModel.state.collectAsStateWithLifecycle()
    val sourceHint by importViewModel.sourceHint.collectAsStateWithLifecycle()
    val categories by ledgerViewModel.categories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ImportCandidate?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importViewModel.parse(context, it) }
    }
    val candidates = state.result?.candidates.orEmpty()
    val legacyGroups = remember(state.legacyConflicts) {
        state.legacyConflicts.flatMap { (id, keys) -> keys.map { it to id } }
            .groupBy({ it.first }, { it.second })
    }
    val matchedLegacyKeys = state.legacyChoices.values.toSet()
    val selectedCount = candidates.count { it.selected && it.canSelect && it.importKey !in matchedLegacyKeys }
    val close = {
        importViewModel.reset()
        onBack()
    }

    editing?.let { candidate ->
        CandidateEditDialog(
            candidate = candidate,
            categories = categories,
            onDismiss = { editing = null },
            onConfirm = {
                importViewModel.updateCandidate(it)
                editing = null
            },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("导入账单") },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        bottomBar = {
            if (state.result != null) {
                Button(
                    onClick = importViewModel::commit,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
                    enabled = (selectedCount > 0 || state.legacyChoices.isNotEmpty()) &&
                        state.legacyChoices.keys == state.legacyConflicts.keys &&
                        !state.committing && state.commitMessage.isEmpty(),
                ) {
                    Text(
                        when {
                            state.committing -> "正在导入…"
                            state.commitMessage.isNotEmpty() -> state.commitMessage
                            selectedCount == 0 && state.legacyChoices.isNotEmpty() -> "确认旧账匹配"
                            else -> "确认导入 $selectedCount 条"
                        },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("支持微信、支付宝原始 CSV / XLSX 文件，最大 10 MiB。数据只在本机解析。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sourceHint == null, onClick = { importViewModel.setSourceHint(null) }, label = { Text("自动识别") })
                    listOf(ImportSource.WECHAT, ImportSource.ALIPAY).forEach { source ->
                        FilterChip(
                            selected = sourceHint == source,
                            onClick = { importViewModel.setSourceHint(source) },
                            label = { Text(source.label) },
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        launcher.launch(
                            arrayOf(
                                "text/csv",
                                "text/comma-separated-values",
                                "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/octet-stream",
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = !state.loading && !state.committing,
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Text(if (state.fileName.isEmpty()) "  选择账单文件" else "  重新选择文件")
                }
            }
            if (state.loading) item { CircularProgressIndicator() }
            state.fileName.takeIf { it.isNotBlank() }?.let { fileName ->
                item { Text(fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            state.result?.let { result ->
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${result.source.label}账单 · 识别 ${candidates.size} 条", fontWeight = FontWeight.SemiBold)
                            if (state.commitMessage.isNotEmpty()) {
                                Text(state.commitMessage, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("待导入 $selectedCount 条 · 重复 ${candidates.count { it.duplicate }} 条 · 跳过 ${result.skippedCount} 条")
                            }
                            if (candidates.any { it.needsReview }) {
                                Text("待核对的账目须修正并确认收支方向和金额后才能导入。", color = MaterialTheme.colorScheme.error)
                            }
                            if (state.legacyConflicts.isNotEmpty()) {
                                Text("多条记录可能对应同一笔旧账。请按原账单选择每笔旧账对应的记录，再勾选要新增的账目。")
                            }
                        }
                    }
                }
                if (result.issues.isNotEmpty()) {
                    item {
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("解析说明", fontWeight = FontWeight.SemiBold)
                                result.issues.take(12).forEach { issue ->
                                    Text(issue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (result.issues.size > 12) Text("其余 ${result.issues.size - 12} 条说明已折叠")
                            }
                        }
                    }
                }
                if (state.commitMessage.isNotEmpty()) {
                    item {
                        Button(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("完成并返回") }
                    }
                }
            }
            items(candidates, key = { it.id }) { candidate ->
                val matchedLegacy = candidate.importKey in matchedLegacyKeys
                val legacyIds = legacyGroups[candidate.importKey].orEmpty()
                Card {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = candidate.selected && candidate.canSelect && !matchedLegacy,
                            onCheckedChange = { importViewModel.toggle(candidate.id, it) },
                            enabled = candidate.canSelect && !matchedLegacy && state.commitMessage.isEmpty(),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(candidate.merchant.ifBlank { candidate.note }, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(candidate.occurredAt.formatDateTime(), style = MaterialTheme.typography.bodySmall)
                            if (legacyIds.isNotEmpty()) {
                                Text("第 ${candidate.originalRow} 条记录", style = MaterialTheme.typography.bodySmall)
                                candidate.merchantOrderId?.let { Text("商户单号 $it", style = MaterialTheme.typography.bodySmall) }
                                legacyIds.forEach { id ->
                                    FilterChip(
                                        selected = state.legacyChoices[id] == candidate.importKey,
                                        onClick = { importViewModel.chooseLegacyMatch(id, candidate.importKey) },
                                        enabled = !state.committing && state.commitMessage.isEmpty(),
                                        label = { Text("对应旧账 #$id") },
                                    )
                                }
                            }
                            Text(
                                candidate.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (candidate.duplicate) "重复记录" else if (candidate.needsReview) "待核对" else candidate.suggestedCategoryName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (candidate.duplicate || candidate.needsReview) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                                if (!candidate.needsReview) Text(candidate.type.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (candidate.amountCents > 0) {
                                Text(formatCents(candidate.amountCents), fontWeight = FontWeight.SemiBold)
                            }
                            TextButton(
                                onClick = { editing = candidate },
                                enabled = !candidate.duplicate && !matchedLegacy && state.commitMessage.isEmpty(),
                            ) { Text("修正") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun CandidateEditDialog(
    candidate: ImportCandidate,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onConfirm: (ImportCandidate) -> Unit,
) {
    val context = LocalContext.current
    var amount by remember(candidate.id) {
        mutableStateOf(if (candidate.amountCents > 0) formatCents(candidate.amountCents, false) else "")
    }
    var type by remember(candidate.id) { mutableStateOf(candidate.type.takeUnless { candidate.needsReview }) }
    var categoryId by remember(candidate.id) { mutableStateOf(candidate.categoryId) }
    var dateTime by remember(candidate.id) { mutableStateOf(candidate.occurredAt.toLocalDateTime()) }
    var merchant by remember(candidate.id) { mutableStateOf(candidate.merchant) }
    var note by remember(candidate.id) { mutableStateOf(candidate.note) }
    var error by remember(candidate.id) { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    LaunchedEffect(type, categories) {
        val available = categories.filter { it.type == type }
        if (available.none { it.id == categoryId }) categoryId = available.firstOrNull()?.id
    }
    if (showDate) {
        LaunchedEffect(Unit) {
            DatePickerDialog(context, { _, year, month, day ->
                dateTime = dateTime.withYear(year).withMonth(month + 1).withDayOfMonth(day)
                showDate = false
            }, dateTime.year, dateTime.monthValue - 1, dateTime.dayOfMonth).apply {
                setOnCancelListener { showDate = false }
            }.show()
        }
    }
    if (showTime) {
        LaunchedEffect(Unit) {
            TimePickerDialog(context, { _, hour, minute ->
                dateTime = dateTime.withHour(hour).withMinute(minute)
                showTime = false
            }, dateTime.hour, dateTime.minute, true).apply {
                setOnCancelListener { showTime = false }
            }.show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("核对并修正") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransactionType.entries.forEach { itemType ->
                        FilterChip(selected = type == itemType, onClick = { type = itemType }, label = { Text(itemType.label) })
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("金额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.filter { it.type == type }.forEach { category ->
                        FilterChip(selected = categoryId == category.id, onClick = { categoryId = category.id }, label = { Text(category.name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDate = true }) { Text(dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE)) }
                    OutlinedButton(onClick = { showTime = true }) { Text(dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                }
                OutlinedTextField(value = merchant, onValueChange = { merchant = it.take(100) }, label = { Text("商户 / 来源") })
                OutlinedTextField(value = note, onValueChange = { note = it.take(200) }, label = { Text("消费信息 / 备注") })
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = parseAmountCents(amount)
                val selectedCategory = categoryId
                val selectedType = type
                if (selectedType == null) {
                    error = "请选择收入或支出"
                } else if (cents == null || selectedCategory == null) {
                    error = "请填写有效金额并选择分类"
                } else {
                    onConfirm(
                        candidate.copy(
                            amountCents = cents,
                            type = selectedType,
                            categoryId = selectedCategory,
                            suggestedCategoryName = categories.firstOrNull { it.id == selectedCategory }?.name ?: candidate.suggestedCategoryName,
                            occurredAt = dateTime.toMillis(),
                            merchant = merchant,
                            note = note,
                            needsReview = false,
                        ),
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
