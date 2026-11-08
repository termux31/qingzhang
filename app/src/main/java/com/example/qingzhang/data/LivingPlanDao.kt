package com.example.qingzhang.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "savings_goals")
data class SavingsGoal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetCents: Long,
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
)

data class GoalProgress(@Embedded val goal: SavingsGoal, val savedCents: Long)
data class PlanProgress(
    @Embedded val plan: LivingPlan,
    val goalName: String?,
    val incomeCents: Long?,
    val savedCents: Long,
)

@Dao
interface LivingPlanDao {
    @Query(
        """SELECT p.*, g.name AS goalName, t.amountCents AS incomeCents,
        COALESCE((SELECT SUM(e.amountCents) FROM savings_entries e WHERE e.planId = p.id), 0) AS savedCents
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

    @Query("SELECT EXISTS(SELECT 1 FROM savings_goals WHERE id = :id)")
    suspend fun hasGoal(id: Long): Boolean

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM savings_entries WHERE goalId = :goalId")
    suspend fun goalBalance(goalId: Long): Long

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM savings_entries WHERE planId = :planId")
    suspend fun planSavings(planId: Long): Long

    @Insert
    suspend fun insertPlan(plan: LivingPlan): Long

    @Insert
    suspend fun insertGoal(goal: SavingsGoal): Long

    @Insert
    suspend fun insertEntry(entry: SavingsEntry): Long
}
