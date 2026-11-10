package com.example.qingzhang

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.qingzhang.data.CategoryEntity
import com.example.qingzhang.data.ImportSource
import com.example.qingzhang.data.LedgerBackup
import com.example.qingzhang.data.LedgerDatabase
import com.example.qingzhang.data.LivingPlan
import com.example.qingzhang.data.SavingsEntry
import com.example.qingzhang.data.SavingsGoal
import com.example.qingzhang.data.TransactionEntity
import com.example.qingzhang.data.TransactionType
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerBackupInstrumentedTest {
    private lateinit var source: LedgerDatabase
    private lateinit var target: LedgerDatabase
    private lateinit var backup: File
    private val builtIn = CategoryEntity(id = 1, name = "餐饮", type = TransactionType.EXPENSE, color = 0)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        source = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        backup = File.createTempFile("backup-test-", ".json", context.cacheDir)
        target.categoryDao().insert(builtIn)
        source.categoryDao().insert(builtIn.copy(id = 42, name = "测试分类", isCustom = true))
        source.ledgerDao().insert(TransactionEntity(
            id = 77, amountCents = 1_234, type = TransactionType.EXPENSE, categoryId = 42,
            occurredAt = 1_700_000_000_000, note = "旧账\n备注", source = ImportSource.WECHAT,
            externalId = "12345", importKey = "wechat:12345", extraDailyExpense = true,
        ))
        val plans = source.livingPlanDao()
        plans.insertGoal(SavingsGoal(id = 3, name = "旅行", targetCents = 8_000, targetDay = 20_000, archived = true))
        plans.insertPlan(LivingPlan(
            id = 4, startDay = 19_800, budgetCents = 10_000, reserveCents = 3_000, goalId = 3,
            incomeId = null, archived = true, detachedSavingsCents = 200,
        ))
        plans.insertEntry(SavingsEntry(id = 25, goalId = 3, planId = 4, amountCents = 1_500, day = 19_801))
        plans.insertEntry(SavingsEntry(id = 26, goalId = 3, amountCents = 200, day = 19_802, fromPlan = true))
        LedgerBackup(source).write(backup)
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
        backup.delete()
    }

    @Test
    fun preservesAllLedgerRowsAndRejectsRestoringOverExistingData() = runBlocking {
        val original = backup.readText()
        val targetBackup = LedgerBackup(target)
        targetBackup.restore(backup)
        val detached = target.livingPlanDao().observeEntries(3).first().first()
        assertEquals(null, detached.planId)
        assertEquals(true, detached.fromPlan)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { targetBackup.restore(backup) }
        }
        targetBackup.write(backup)
        assertEquals(original, backup.readText())
    }

    @Test
    fun restoresVersion1BackupWithLinkedAndManualEntries() = runBlocking {
        backup.writeText("""
            {"qingzhangLedger":1,"categories":[],"transactions":[],
             "savings_goals":[[3,"旅行",8000,null,0]],
             "living_plans":[[4,19800,10000,3000,3,null,0,0]],
             "savings_entries":[[25,3,4,1500,19801],[26,3,null,200,19802]]}
        """.trimIndent())
        LedgerBackup(target).restore(backup)
        val entries = target.livingPlanDao().observeEntries(3).first()
        assertEquals(mapOf(25L to true, 26L to false), entries.associate { it.id to it.fromPlan })
    }

    @Test
    fun truncatedBackupRollsBackAllRowsAndKeepsBuiltInCategories() = runBlocking {
        val original = backup.readText()
        backup.writeText(original.dropLast(1))
        val targetBackup = LedgerBackup(target)
        assertThrows(IOException::class.java) {
            runBlocking { targetBackup.restore(backup) }
        }
        assertEquals(listOf(builtIn), target.categoryDao().getAll())
        backup.writeText(original)
        targetBackup.restore(backup)
        assertEquals(source.ledgerDao().getById(77), target.ledgerDao().getById(77))
    }
}
