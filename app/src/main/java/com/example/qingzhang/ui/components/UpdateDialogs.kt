package com.example.qingzhang.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.qingzhang.ui.UpdatePhase
import com.example.qingzhang.ui.UpdateUiState
import com.example.qingzhang.ui.UpdateViewModel
import java.util.Locale

@Composable
fun UpdateDialogs(viewModel: UpdateViewModel, state: UpdateUiState) {
    val context = LocalContext.current
    val manifest = state.manifest
    when (state.phase) {
        UpdatePhase.AVAILABLE -> {
            if (manifest != null) {
                AlertDialog(
                    onDismissRequest = viewModel::dismissUpdate,
                    title = { Text("发现新版本 ${manifest.versionName}") },
                    text = {
                        Column(
                            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                String.format(
                                    Locale.CHINA,
                                    "安装包 %.1f MB",
                                    manifest.sizeBytes.toDouble() / 1024 / 1024,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (manifest.releaseNotes.isNotBlank()) Text(manifest.releaseNotes)
                            Text(
                                "将覆盖安装当前版本，账目和设置会保留。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::downloadUpdate) { Text("下载更新") }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::dismissUpdate) { Text("稍后") }
                    },
                )
            }
        }
        UpdatePhase.DOWNLOADING -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("正在下载更新") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("已下载 ${state.progress}%")
                    }
                },
                confirmButton = {},
            )
        }
        UpdatePhase.READY -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("更新已下载") },
                text = { Text("安装时会覆盖当前版本，账目、分类和设置不会清空。请勿卸载应用。") },
                confirmButton = {
                    TextButton(onClick = { viewModel.installUpdate(context) }) { Text("安装更新") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissUpdate) { Text("稍后") }
                },
            )
        }
        else -> Unit
    }
}
