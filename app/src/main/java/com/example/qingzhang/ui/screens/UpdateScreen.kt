package com.example.qingzhang.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.qingzhang.ui.UpdatePhase
import com.example.qingzhang.ui.UpdateViewModel
import com.example.qingzhang.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(viewModel: UpdateViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var manifestUrl by remember(settings.updateManifestUrl) { mutableStateOf(settings.updateManifestUrl) }
    var autoCheck by remember(settings.autoCheckUpdates) { mutableStateOf(settings.autoCheckUpdates) }
    val busy = state.phase == UpdatePhase.CHECKING || state.phase == UpdatePhase.DOWNLOADING

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("软件更新") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("当前版本 ${state.installedVersion.versionName}", fontWeight = FontWeight.SemiBold)
                        Text("版本号 ${state.installedVersion.versionCode}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("更新源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = manifestUrl,
                onValueChange = { manifestUrl = it.trim().take(2_000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("版本清单 HTTPS 地址") },
                placeholder = { Text("https://example.com/qingzhang/latest.json") },
                minLines = 2,
                maxLines = 3,
                singleLine = false,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启动时自动检查", fontWeight = FontWeight.SemiBold)
                    Text("发现新版本时在应用内提示", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = autoCheck, onCheckedChange = { autoCheck = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { viewModel.saveUpdateSettings(manifestUrl, autoCheck) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) { Text("保存设置") }
                Button(
                    onClick = {
                        if (manifestUrl.trim() != settings.updateManifestUrl || autoCheck != settings.autoCheckUpdates) {
                            viewModel.saveUpdateSettings(manifestUrl, autoCheck)
                        } else {
                            viewModel.checkForUpdate(manual = true)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) { Text(if (state.phase == UpdatePhase.CHECKING) "检查中…" else "检查更新") }
            }
            if (state.error.isNotBlank()) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
            }
            when (state.phase) {
                UpdatePhase.UP_TO_DATE -> {
                    Text("当前已是最新版本", color = MaterialTheme.colorScheme.primary)
                }
                UpdatePhase.AVAILABLE, UpdatePhase.DOWNLOADING, UpdatePhase.READY, UpdatePhase.ERROR -> {
                    val manifest = state.manifest
                    if (manifest != null) {
                        SectionCard {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("新版本 ${manifest.versionName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${manifest.sizeBytes / 1024 / 1024} MB · versionCode ${manifest.versionCode}")
                            if (manifest.releaseNotes.isNotBlank()) {
                                Text("更新内容", fontWeight = FontWeight.SemiBold)
                                Text(manifest.releaseNotes)
                            }
                            if (state.phase == UpdatePhase.DOWNLOADING) {
                                LinearProgressIndicator(
                                    progress = { state.progress / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("已下载 ${state.progress}%")
                            }
                            when (state.phase) {
                                UpdatePhase.AVAILABLE -> Button(
                                    onClick = viewModel::downloadUpdate,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Text("  下载更新")
                                }
                                UpdatePhase.ERROR -> Button(
                                    onClick = viewModel::downloadUpdate,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("重新下载") }
                                UpdatePhase.READY -> Button(
                                    onClick = { viewModel.installUpdate(context) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("安装更新") }
                                else -> Unit
                            }
                            Text(
                                "覆盖安装会保留账目、分类和设置，请勿先卸载当前应用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    }
                }
                else -> Unit
            }
            Text(
                "版本清单与安装包必须使用 HTTPS。应用会校验安装包 SHA-256、包名和签名，签名不一致时系统会拒绝安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
