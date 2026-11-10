package com.example.qingzhang.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qingzhang.BuildConfig
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.formatCents
import com.example.qingzhang.domain.parseAmountCents
import com.example.qingzhang.ui.LedgerViewModel
import com.example.qingzhang.ui.components.EmptyState
import com.example.qingzhang.ui.components.SectionCard
import java.time.LocalDate

@Composable
fun MineScreen(
    viewModel: LedgerViewModel,
    onImport: () -> Unit,
    onCategories: () -> Unit,
    onLimit: () -> Unit,
    onUpdate: () -> Unit,
    onLivingPlans: () -> Unit,
    onSavings: () -> Unit,
    onReminders: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val darkTheme = settings.darkTheme ?: isSystemInDarkTheme()
    val backupBusy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.transferBackup(context, uri, restore = false)
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.transferBackup(context, uri, restore = true)
    }
    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("我的", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            SectionCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("软件主题", fontWeight = FontWeight.SemiBold)
                        Text(if (darkTheme) "深色" else "白色", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = darkTheme,
                        onCheckedChange = viewModel::setDarkTheme,
                        modifier = Modifier.semantics { contentDescription = "深色主题" },
                    )
                }
            }
        }
        item {
            SectionCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("每日消费限额", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.dailyLimitEnabled) {
                            formatCents(settings.dailyLimitCents)
                        } else {
                            "¥0.00 · 未启用"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("支出超过限额时提醒，不会阻止记账", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SettingsItem("导入微信 / 支付宝账单", "识别、核对并修正后入库", Icons.Default.FileUpload, onImport) }
        item { SettingsItem("分类管理", "添加自定义收支分类", Icons.Default.Category, onCategories) }
        item { SettingsItem("每日限额设置", "修改金额或关闭提醒", Icons.Default.Notifications, onLimit) }
        item { SettingsItem("软件更新", "当前版本 ${BuildConfig.VERSION_NAME}", Icons.Default.SystemUpdate, onUpdate) }
        item { SettingsItem("大学生活费计划", "任选开始日期，规划 30 天生活费", Icons.Default.Today, onLivingPlans) }
        item { SettingsItem("攒钱目标", "记录存入和取出，查看目标进度", Icons.Default.Savings, onSavings) }
        item { SettingsItem("通知设置", "管理记账、生活费和攒钱提醒", Icons.Default.Notifications, onReminders) }
        item {
            SectionCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账本备份与恢复", fontWeight = FontWeight.SemiBold)
                    Text(
                        "备份含账目、分类、生活费计划和攒钱明细。文件未加密，请妥善保管。仅可恢复到空账本。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { exportBackup.launch("轻账备份-${LocalDate.now()}.json") },
                            enabled = !backupBusy,
                        ) { Text("导出备份") }
                        OutlinedButton(
                            onClick = { restoreBackup.launch(arrayOf("application/json", "application/octet-stream")) },
                            enabled = !backupBusy,
                        ) { Text("恢复备份") }
                    }
                }
            }
        }
        item {
            SectionCard {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("本地数据保护", fontWeight = FontWeight.SemiBold)
                        Text(
                            "账目保存在应用私有数据库中；仅检查更新时访问你配置的更新地址。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item { Text("轻账 ${BuildConfig.VERSION_NAME}", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SettingsItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(viewModel: LedgerViewModel, onBack: () -> Unit) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var type by remember { mutableStateOf(TransactionType.EXPENSE) }
    var showAdd by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<Long?>(null) }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加${type.label}分类") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(12) },
                    label = { Text("分类名称") },
                    singleLine = true,
                    supportingText = { Text("最多 12 个字符，不可重名") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        viewModel.addCategory(name, type)
                        showAdd = false
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } },
        )
    }
    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("删除自定义分类？") },
            text = { Text("仅未关联任何账目的自定义分类可以删除。") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteCategory(id); deleteId = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("分类管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "添加分类") } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TransactionType.entries.forEach { itemType ->
                        FilterChip(
                            selected = type == itemType,
                            onClick = { type = itemType },
                            label = { Text("${itemType.label}分类") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            val visible = categories.filter { it.type == type }
            if (visible.isEmpty()) item { EmptyState("暂无分类", "点击右下角添加自定义分类") }
            items(visible, key = { it.id }) { category ->
                SectionCard {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("●", color = androidx.compose.ui.graphics.Color(category.color))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(category.name, fontWeight = FontWeight.SemiBold)
                            Text(if (category.isCustom) "自定义分类" else "内置分类", style = MaterialTheme.typography.bodySmall)
                        }
                        if (category.isCustom) TextButton(onClick = { deleteId = category.id }) { Text("删除") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyLimitScreen(viewModel: LedgerViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var amount by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf("") }
    val actualAmount = amount ?: formatCents(settings.dailyLimitCents, false)
    val actualEnabled = enabled ?: settings.dailyLimitEnabled

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("每日限额") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("当日支出超过设定金额时，会弹出明确警告。收入不计入，提醒不会阻止保存。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = actualAmount,
                onValueChange = { amount = it; error = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("每日最大支出") },
                prefix = { Text("¥ ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = actualEnabled,
                isError = error.isNotBlank(),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用超限提醒", fontWeight = FontWeight.SemiBold)
                    Text("手动记录与导入支出均适用", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = actualEnabled, onCheckedChange = { enabled = it })
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    val cents = parseAmountCents(actualAmount)
                    if (actualEnabled && cents == null) {
                        error = "启用限额时，请输入大于 0 且最多两位小数的金额"
                    } else {
                        viewModel.saveDailyLimit(cents ?: 0, actualEnabled, onBack)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("保存设置") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
