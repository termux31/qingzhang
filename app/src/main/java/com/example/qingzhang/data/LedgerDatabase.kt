package com.example.qingzhang.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TransactionEntity::class, CategoryEntity::class, LivingPlan::class, SavingsGoal::class, SavingsEntry::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(LedgerTypeConverters::class)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun categoryDao(): CategoryDao
    abstract fun livingPlanDao(): LivingPlanDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN extraDailyExpense INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE savings_goals ADD COLUMN targetDay INTEGER")
                db.execSQL("ALTER TABLE savings_goals ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE living_plans ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE living_plans ADD COLUMN detachedSavingsCents INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE savings_entries ADD COLUMN fromPlan INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE savings_entries SET fromPlan = 1 WHERE planId IS NOT NULL")
            }
        }

        @Volatile
        private var instance: LedgerDatabase? = null

        fun get(context: Context): LedgerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LedgerDatabase::class.java,
                "qingzhang.db",
            ).addMigrations(MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
