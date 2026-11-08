package com.example.qingzhang.ui.screens

import android.app.DatePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.formatCents
import com.example.qingzhang.ui.LedgerViewModel
import com.example.qingzhang.ui.components.EmptyState
import com.example.qingzhang.ui.components.SectionCard
import com.example.qingzhang.ui.components.SummaryStrip
import com.example.qingzhang.ui.components.TransactionRow
import kotlinx.coroutines.launch

@Composable
fun BillScreen(viewModel: LedgerViewModel, onEdit: (Long) -> Unit, onMessage: (String) -> Unit) {
    val filter by viewModel.billFilter.collectAsStateWithLifecycle()
    val records by viewModel.bills.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exporting = true
            runCatching {
                val bytes = viewModel.exportCurrentBills()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: throw IllegalArgumentException("无法写入所选位置")
                }
            }.onSuccess { onMessage("已导出 ${records.size} 条账目") }
                .onFailure { onMessage(it.message ?: "导出失败") }
            exporting = false
        }
    }

    LaunchedEffect(showStartPicker) {
        if (showStartPicker) {
            DatePickerDialog(context, { _, year, month, day ->
                viewModel.updateBillFilter { it.copy(start = java.time.LocalDate.of(year, month + 1, day)) }
                showStartPicker = false
            }, filter.start.year, filter.start.monthValue - 1, filter.start.dayOfMonth).apply {
                setOnCancelListener { showStartPicker = false }
            }.show()
            showStartPicker = false
        }
    }
    LaunchedEffect(showEndPicker) {
        if (showEndPicker) {
            DatePickerDialog(context, { _, year, month, day ->
                viewModel.updateBillFilter { it.copy(end = java.time.LocalDate.of(year, month + 1, day)) }
                showEndPicker = false
            }, filter.end.year, filter.end.monthValue - 1, filter.end.dayOfMonth).apply {
                setOnCancelListener { showEndPicker = false }
            }.show()
            showEndPicker = false
        }
    }

    val income = records.filter { it.transaction.type == TransactionType.INCOME }.sumOf { it.transaction.amountCents }
    val expense = records.filter { it.transaction.type == TransactionType.EXPENSE }.sumOf { it.transaction.amountCents }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("账单", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("共 ${records.size} 条", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    onClick = { exportLauncher.launch("轻账_${filter.start}_${filter.end}.csv") },
                    enabled = records.isNotEmpty() && !exporting,
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Text(if (exporting) "导出中" else " 导出")
                }
            }
        }
        item {
            SectionCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = filter.query,
                        onValueChange = { value -> viewModel.updateBillFilter { it.copy(query = value.take(50)) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("搜索备注、商户或分类") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showStartPicker = true }, modifier = Modifier.weight(1f)) {
                            Text(filter.start.toString())
                        }
                        OutlinedButton(onClick = { showEndPicker = true }, modifier = Modifier.weight(1f)) {
                            Text(filter.end.toString())
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = filter.type == null,
                            onClick = { viewModel.updateBillFilter { it.copy(type = null, categoryId = null) } },
                            label = { Text("全部") },
                        )
                        TransactionType.entries.forEach { type ->
                            FilterChip(
                                selected = filter.type == type,
                                onClick = { viewModel.updateBillFilter { it.copy(type = type, categoryId = null) } },
                                label = { Text(type.label) },
                            )
                        }
                    }
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = filter.categoryId == null,
                            onClick = { viewModel.updateBillFilter { it.copy(categoryId = null) } },
                            label = { Text("全部分类") },
                        )
                        categories.filter { filter.type == null || it.type == filter.type }.forEach { category ->
                            FilterChip(
                                selected = filter.categoryId == category.id,
                                onClick = { viewModel.updateBillFilter { it.copy(categoryId = category.id) } },
                                label = { Text(category.name) },
                            )
                        }
                    }
                }
            }
        }
        item { SectionCard { Column(Modifier.padding(16.dp)) { SummaryStrip(income, expense) } } }
        if (records.isEmpty()) {
            item {
                SectionCard { EmptyState("没有符合条件的账目", "可调整时间、类型或关键词，或先记一笔") }
            }
        } else {
            items(records, key = { it.transaction.id }) { detail ->
                Card { TransactionRow(detail, onClick = { onEdit(detail.transaction.id) }) }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}
