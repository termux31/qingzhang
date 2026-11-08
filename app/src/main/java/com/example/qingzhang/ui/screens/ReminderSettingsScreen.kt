package com.example.qingzhang.ui.screens

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qingzhang.notifications.NoticeKind
import com.example.qingzhang.notifications.ReminderPreferences
import com.example.qingzhang.notifications.ReminderSettings
import com.example.qingzhang.notifications.notificationsAllowed
import com.example.qingzhang.ui.components.SectionCard
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun ReminderSettingsScreen(
    preferences: ReminderPreferences,
    onPermissionChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by preferences.settings.collectAsStateWithLifecycle(initialValue = ReminderSettings())
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    var error by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = notificationsAllowed(context)
        onPermissionChanged()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = notificationsAllowed(context) }

    fun save(change: suspend () -> Unit) {
        scope.launch {
            try {
                change()
                error = false
            } catch (_: IOException) {
                error = true
            }
        }
    }

    BudgetPage("通知设置", onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!allowed) {
                item {
                    Text("系统通知当前关闭，允许后才能收到轻账提醒。")
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("打开系统通知设置") }
                }
            }
            items(NoticeKind.entries, key = { it.name }) { kind ->
                val checked = kind in config.enabledSince
                SectionCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(kind.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = checked,
                                onCheckedChange = { enabled ->
                                    save { preferences.setEnabled(kind, enabled) }
                                    if (enabled && Build.VERSION.SDK_INT >= 33 &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                    ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                                modifier = Modifier.semantics { contentDescription = kind.title },
                            )
                        }
                        Text(kind.message, style = MaterialTheme.typography.bodySmall)
                        if (kind == NoticeKind.DAILY) {
                            val hour = config.dailyMinute / 60
                            val minute = config.dailyMinute % 60
                            val time = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                            OutlinedButton(onClick = {
                                TimePickerDialog(context, { _, selectedHour, selectedMinute ->
                                    save { preferences.setDailyMinute(selectedHour * 60 + selectedMinute) }
                                }, hour, minute, true).show()
                            }) { Text("提醒时间 $time") }
                        }
                    }
                }
            }
            if (error) item { Text("通知设置保存失败，请重试", color = MaterialTheme.colorScheme.error) }
        }
    }
}
