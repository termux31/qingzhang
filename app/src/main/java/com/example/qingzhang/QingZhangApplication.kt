package com.example.qingzhang

import android.app.Application
import com.example.qingzhang.data.LedgerDatabase
import com.example.qingzhang.data.LedgerRepository
import com.example.qingzhang.data.SettingsRepository
import com.example.qingzhang.notifications.ReminderCoordinator
import com.example.qingzhang.notifications.ReminderPreferences
import com.example.qingzhang.update.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Application) {
    private val database = LedgerDatabase.get(context)
    val settingsRepository = SettingsRepository(context, BuildConfig.UPDATE_MANIFEST_URL)
    val ledgerRepository = LedgerRepository(database, settingsRepository)
    val updateRepository = UpdateRepository(context)
    internal val reminderPreferences = ReminderPreferences(context)
    internal val reminders = ReminderCoordinator(context, reminderPreferences, ledgerRepository.livingPlans, database.ledgerDao())
}

class QingZhangApplication : Application() {
    lateinit var container: AppContainer
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch { container.ledgerRepository.ensureDefaultCategories() }
        applicationScope.launch { container.reminders.observeChanges() }
    }

    internal fun refreshReminders(onComplete: () -> Unit = {}) {
        applicationScope.launch {
            try {
                container.reminders.refresh()
            } finally {
                onComplete()
            }
        }
    }
}
