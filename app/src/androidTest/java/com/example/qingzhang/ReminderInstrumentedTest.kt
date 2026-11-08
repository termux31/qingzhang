package com.example.qingzhang

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.qingzhang.data.CategoryEntity
import com.example.qingzhang.data.LedgerDatabase
import com.example.qingzhang.data.LivingPlan
import com.example.qingzhang.data.LivingPlanRepository
import com.example.qingzhang.data.TransactionEntity
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.startMillis
import com.example.qingzhang.notifications.NoticeKind
import com.example.qingzhang.notifications.REMINDER_CHANNEL
import com.example.qingzhang.notifications.ReminderCoordinator
import com.example.qingzhang.notifications.ReminderPreferences
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderInstrumentedTest {
    @Test
    fun postsAllFourKindsOnceAndSkipsDailyReminderWhenThereIsABill() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        val preferences = ReminderPreferences(store)
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val ledger = database.ledgerDao()
        val plans = LivingPlanRepository(database)
        val coordinator = ReminderCoordinator(context, preferences, plans, ledger)
        val now = ZonedDateTime.now().plusDays(3).withHour(22).withMinute(0)
        val today = now.toLocalDate()
        val zone = now.zone
        val types = NoticeKind.entries
        fun visible() = manager.activeNotifications.filter { it.notification.channelId == REMINDER_CHANNEL }
        fun clear() = visible().forEach { manager.cancel(it.tag, it.id) }

        try {
            clear()
            val category = database.categoryDao().insert(CategoryEntity(name = "测试支出", type = TransactionType.EXPENSE, color = 0))
            val goal = plans.createGoal("测试目标", 100)
            plans.recordSavings(goal, 100, today, null)
            plans.createPlan(LivingPlan(startDay = today.minusDays(31).toEpochDay(), budgetCents = 100, reserveCents = 0, goalId = null, incomeId = null))
            val plan = plans.createPlan(LivingPlan(startDay = today.minusDays(1).toEpochDay(), budgetCents = 100, reserveCents = 0, goalId = null, incomeId = null))
            val expense = TransactionEntity(
                amountCents = 101, type = TransactionType.EXPENSE, categoryId = category,
                occurredAt = today.minusDays(1).startMillis(zone),
            )
            ledger.insert(expense)
            val history = stringSetPreferencesKey("history")
            coordinator.refresh(now)
            assertTrue(store.data.first()[history].orEmpty().isEmpty())
            assertTrue(visible().isEmpty())
            store.edit { it[history] = setOf("GOAL:$goal", "OVER_BUDGET:$plan") }
            types.forEach { preferences.setEnabled(it, true) }

            coordinator.refresh(now)
            withTimeout(5_000) { while (visible().size < 4) delay(25) }
            assertEquals(types.map { it.title }.toSet(), visible().map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() }.toSet())
            assertEquals(types.map { it.message }.toSet(), visible().map { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString() }.toSet())
            assertTrue(visible().all { it.notification.contentIntent != null })

            clear()
            withTimeout(5_000) { while (visible().isNotEmpty()) delay(25) }
            coordinator.refresh(now)
            delay(200)
            assertTrue(visible().isEmpty())

            ledger.insert(expense.copy(occurredAt = today.plusDays(1).startMillis(zone)))
            coordinator.refresh(now.plusDays(1))
            delay(200)
            assertTrue(visible().isEmpty())
        } finally {
            types.forEach { preferences.setEnabled(it, false) }
            coordinator.refresh(now)
            clear()
            database.close()
            scope.cancel()
            file.delete()
            (context.applicationContext as QingZhangApplication).refreshReminders()
        }
    }
}
