package com.example.qingzhang

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qingzhang.notifications.NoticeTarget
import com.example.qingzhang.ui.ImportViewModel
import com.example.qingzhang.ui.LedgerViewModel
import com.example.qingzhang.ui.QingZhangApp
import com.example.qingzhang.ui.QingZhangTheme
import com.example.qingzhang.ui.UpdateViewModel

class MainActivity : ComponentActivity() {
    private var reminderTarget by mutableStateOf<NoticeTarget?>(null)
    private val dateChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ledgerViewModel.refreshHomeDate()
        }
    }
    private val ledgerViewModel by viewModels<LedgerViewModel> {
        LedgerViewModel.factory((application as QingZhangApplication).container.ledgerRepository)
    }
    private val importViewModel by viewModels<ImportViewModel> {
        ImportViewModel.factory((application as QingZhangApplication).container.ledgerRepository)
    }
    private val updateViewModel by viewModels<UpdateViewModel> {
        val container = (application as QingZhangApplication).container
        UpdateViewModel.factory(container.settingsRepository, container.updateRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) reminderTarget = NoticeTarget.from(intent)
        val app = application as QingZhangApplication
        setContent {
            val settings by ledgerViewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = settings.darkTheme ?: isSystemInDarkTheme()
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()) { darkTheme },
                )
            }
            QingZhangTheme(darkTheme) {
                QingZhangApp(
                    ledgerViewModel = ledgerViewModel,
                    importViewModel = importViewModel,
                    updateViewModel = updateViewModel,
                    reminderPreferences = app.container.reminderPreferences,
                    reminderTarget = reminderTarget,
                    onReminderOpened = { reminderTarget = null },
                    onReminderPermissionChanged = { app.refreshReminders() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        reminderTarget = NoticeTarget.from(intent)
    }

    override fun onResume() {
        super.onResume()
        (application as QingZhangApplication).refreshReminders()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(Intent.ACTION_DATE_CHANGED).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, dateChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ledgerViewModel.refreshHomeDate()
    }

    override fun onStop() {
        unregisterReceiver(dateChanges)
        super.onStop()
    }
}
