package com.example.qingzhang.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "savings_goals")
data class SavingsGoal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetCents: Long,
    val targetDay: Long? = null,
    val archived: Boolean = false,
)

@Entity(
    tableName = "living_plans",
    foreignKeys = [
        ForeignKey(entity = SavingsGoal::class, parentColumns = ["id"], childColumns = ["goalId"]),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["incomeId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("goalId"), Index("incomeId"), Index(value = ["startDay"], unique = true)],
)
data class LivingPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startDay: Long,
    val budgetCents: Long,
    val reserveCents: Long,
    val goalId: Long?,
    val incomeId: Long?,
    val archived: Boolean = false,
    val detachedSavingsCents: Long = 0,
)

@Entity(
    tableName = "savings_entries",
    foreignKeys = [
        ForeignKey(entity = SavingsGoal::class, parentColumns = ["id"], childColumns = ["goalId"]),
        ForeignKey(entity = LivingPlan::class, parentColumns = ["id"], childColumns = ["planId"]),
    ],
    indices = [Index("goalId"), Index("planId")],
)
data class SavingsEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val planId: Long? = null,
    val amountCents: Long,
    val day: Long,
    @ColumnInfo(defaultValue = "0") val fromPlan: Boolean = planId != null,
)

data class GoalProgress(@Embedded val goal: SavingsGoal, val savedCents: Long)
data class PlanProgress(
    @Embedded val plan: LivingPlan,
    val goalName: String?,
    val goalArchived: Boolean,
    val incomeCents: Long?,
    val savedCents: Long,
)

@Dao
interface LivingPlanDao {
    @Query(
        """SELECT p.*, g.name AS goalName, COALESCE(g.archived, 0) AS goalArchived, t.amountCents AS incomeCents,
        p.detachedSavingsCents + COALESCE((SELECT SUM(e.amountCents) FROM savings_entries e WHERE e.planId = p.id), 0) AS savedCents
        FROM living_plans p LEFT JOIN savings_goals g ON p.goalId = g.id
        LEFT JOIN transactions t ON p.incomeId = t.id AND t.type = 'INCOME'
        ORDER BY p.startDay DESC""",
    )
    fun observePlans(): Flow<List<PlanProgress>>

    @Query(
        """SELECT g.*, COALESCE((SELECT SUM(e.amountCents) FROM savings_entries e WHERE e.goalId = g.id), 0) AS savedCents
        FROM savings_goals g ORDER BY g.id DESC""",
    )
    fun observeGoals(): Flow<List<GoalProgress>>

    @Query("SELECT * FROM savings_entries WHERE goalId = :goalId ORDER BY day DESC, id DESC")
    fun observeEntries(goalId: Long): Flow<List<SavingsEntry>>

    @Query("SELECT * FROM transactions WHERE type = 'INCOME' ORDER BY occurredAt DESC, id DESC")
    fun observeIncome(): Flow<List<TransactionEntity>>

    @Query(
        """SELECT COALESCE(SUM(amountCents), 0) FROM transactions
        WHERE type = 'EXPENSE' AND occurredAt >= :startInclusive AND occurredAt < :endExclusive""",
    )
    fun observeSpending(startInclusive: Long, endExclusive: Long): Flow<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM living_plans WHERE startDay BETWEEN :first AND :last)")
    suspend fun hasPlanBetween(first: Long, last: Long): Boolean

    @Query("SELECT * FROM living_plans WHERE id = :id")
    suspend fun getPlan(id: Long): LivingPlan?

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM savings_entries WHERE goalId = :goalId")
    suspend fun goalBalance(goalId: Long): Long

    @Query(
        """SELECT COALESCE((SELECT SUM(amountCents) FROM savings_entries WHERE planId = :planId), 0) +
        COALESCE((SELECT detachedSavingsCents FROM living_plans WHERE id = :planId), 0)""",
    )
    suspend fun planSavings(planId: Long): Long

    @Query("SELECT * FROM savings_goals WHERE id = :id")
    suspend fun getGoal(id: Long): SavingsGoal?

    @Query("SELECT * FROM living_plans WHERE goalId = :goalId")
    suspend fun plansForGoal(goalId: Long): List<LivingPlan>

    @Query("SELECT EXISTS(SELECT 1 FROM living_plans WHERE goalId = :goalId AND archived = 0)")
    suspend fun hasUnarchivedPlanForGoal(goalId: Long): Boolean

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM savings_entries WHERE goalId = :goalId AND planId = :planId")
    suspend fun goalSavingsForPlan(goalId: Long, planId: Long): Long

    @Update
    suspend fun updatePlan(plan: LivingPlan): Int

    @Update
    suspend fun updateGoal(goal: SavingsGoal): Int

    @Query("UPDATE savings_entries SET planId = NULL WHERE planId = :planId")
    suspend fun detachPlanEntries(planId: Long)

    @Query("UPDATE living_plans SET goalId = NULL, reserveCents = :reserveCents, detachedSavingsCents = :detachedSavingsCents WHERE id = :planId")
    suspend fun detachGoalFromPlan(planId: Long, reserveCents: Long, detachedSavingsCents: Long)

    @Query("DELETE FROM living_plans WHERE id = :id")
    suspend fun deletePlan(id: Long): Int

    @Query("DELETE FROM savings_entries WHERE goalId = :goalId")
    suspend fun deleteGoalEntries(goalId: Long)

    @Query("DELETE FROM savings_goals WHERE id = :id")
    suspend fun deleteGoal(id: Long): Int

    @Insert
    suspend fun insertPlan(plan: LivingPlan): Long

    @Insert
    suspend fun insertGoal(goal: SavingsGoal): Long

    @Insert
    suspend fun insertEntry(entry: SavingsEntry): Long
}
