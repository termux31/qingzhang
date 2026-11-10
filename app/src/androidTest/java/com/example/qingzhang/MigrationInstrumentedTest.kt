package com.example.qingzhang

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.qingzhang.data.LedgerDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LedgerDatabase::class.java,
    )

    @Test
    fun migratesVersion2DataToVersion3WithDefaultNewFields() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                """INSERT INTO categories(id, name, type, color, isCustom, sortOrder)
                VALUES(1, '餐饮', 'EXPENSE', 0, 0, 0)""",
            )
            execSQL(
                """INSERT INTO transactions(id, amountCents, type, categoryId, occurredAt, note, merchant, source, createdAt, updatedAt)
                VALUES(1, 1000, 'EXPENSE', 1, 0, '旧账', '旧商户', 'MANUAL', 0, 0)""",
            )
            execSQL("INSERT INTO savings_goals(id, name, targetCents) VALUES(1, '旅行', 1000)")
            execSQL(
                """INSERT INTO living_plans(id, startDay, budgetCents, reserveCents, goalId, incomeId)
                VALUES(1, 0, 10000, 3000, 1, NULL)""",
            )
            execSQL("INSERT INTO savings_entries(id, goalId, planId, amountCents, day) VALUES(1, 1, 1, 2000, 0)")
            close()
        }

        val database = helper.runMigrationsAndValidate(TEST_DB, 3, true, LedgerDatabase.MIGRATION_2_3)
        database.query("SELECT extraDailyExpense FROM transactions WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT targetDay, archived FROM savings_goals WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertFalse(cursor.getInt(1) != 0)
        }
        database.query("SELECT archived, detachedSavingsCents FROM living_plans WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(0L, cursor.getLong(1))
        }
        database.query("SELECT COUNT(*) FROM transactions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        database.close()
    }

    @Test
    fun migratesVersion3SavingsEntryOrigins() {
        val name = "migration-test-v3.db"
        helper.createDatabase(name, 3).use {
            it.execSQL("INSERT INTO savings_goals(id, name, targetCents, archived) VALUES(1, '旅行', 1000, 0)")
            it.execSQL(
                """INSERT INTO living_plans(id, startDay, budgetCents, reserveCents, goalId, archived, detachedSavingsCents)
                VALUES(1, 0, 10000, 3000, 1, 0, 0)""",
            )
            it.execSQL("INSERT INTO savings_entries(id, goalId, planId, amountCents, day) VALUES(1, 1, 1, 2000, 0), (2, 1, NULL, 1000, 0)")
        }
        helper.runMigrationsAndValidate(name, 4, true, LedgerDatabase.MIGRATION_3_4).use { database ->
            database.query("SELECT fromPlan FROM savings_entries ORDER BY id").use { cursor ->
                assertTrue(cursor.moveToNext())
                assertEquals(1, cursor.getInt(0))
                assertTrue(cursor.moveToNext())
                assertEquals(0, cursor.getInt(0))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test-v2.db"
    }
}
