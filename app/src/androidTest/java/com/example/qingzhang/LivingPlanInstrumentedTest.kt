package com.example.qingzhang

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.qingzhang.data.CategoryEntity
import com.example.qingzhang.data.LedgerDatabase
import com.example.qingzhang.data.LivingPlan
import com.example.qingzhang.data.LivingPlanRepository
import com.example.qingzhang.data.TransactionEntity
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.budgetOn
import com.example.qingzhang.domain.endExclusiveMillis
import com.example.qingzhang.domain.startMillis
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LivingPlanInstrumentedTest {
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LivingPlanRepository
    private var expenseCategory = 0L
    private var incomeCategory = 0L
    private val zone = ZoneId.systemDefault()

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        repository = LivingPlanRepository(database)
        expenseCategory = database.categoryDao().insert(CategoryEntity(name = "支出", type = TransactionType.EXPENSE, color = 0))
        incomeCategory = database.categoryDao().insert(CategoryEntity(name = "收入", type = TransactionType.INCOME, color = 0))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun sumsOnlyExpensesWithinThePlanAndRejectsOverlap() = runBlocking {
        val start = LocalDate.of(2026, 11, 8)
        val end = start.plusDays(29)
        insertBill(1_000, TransactionType.EXPENSE, start.startMillis(zone) - 1)
        insertBill(2_000, TransactionType.EXPENSE, start.startMillis(zone))
        insertBill(3_000, TransactionType.EXPENSE, end.endExclusiveMillis(zone) - 1)
        insertBill(4_000, TransactionType.EXPENSE, end.endExclusiveMillis(zone))
        val income = insertBill(10_000, TransactionType.INCOME, start.startMillis(zone))
        val plan = LivingPlan(startDay = start.toEpochDay(), budgetCents = 10_000, reserveCents = 0, goalId = null, incomeId = income)
        repository.createPlan(plan)
        assertEquals(5_000L, repository.spending(plan, zone).first())
        assertEquals(10_000L, repository.plans.first().single().incomeCents!!)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.createPlan(plan.copy(startDay = end.toEpochDay())) }
        }
        repository.createPlan(plan.copy(startDay = end.plusDays(1).toEpochDay()))
        assertEquals(2, repository.plans.first().size)
    }

    @Test
    fun transfersAtomicallyAndOnlyWritesIncomeWhenSelected() = runBlocking {
        val today = LocalDate.now(zone)
        val goalId = repository.createGoal("旅行", 30_000)
        val planId = repository.createPlan(LivingPlan(
            startDay = today.toEpochDay(), budgetCents = 10_000, reserveCents = 3_000, goalId = goalId, incomeId = null,
        ))
        val attempts = List(2) {
            async { runCatching { repository.transferFromPlan(planId, 2_000, zone) } }
        }.awaitAll()
        assertEquals(1, attempts.count { it.isSuccess })
        assertEquals(2_000L, repository.plans.first().single().savedCents)
        assertEquals(2_000L, repository.goals.first().single().savedCents)
        assertEquals(emptyList<TransactionEntity>(), repository.incomes.first())

        repository.recordSavings(goalId, 1_000, today, null)
        repository.recordSavings(goalId, 2_000, today, incomeCategory)
        assertEquals(2_000L, repository.incomes.first().single().amountCents)
        repository.recordSavings(goalId, -1_000, today, null)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.recordSavings(goalId, -4_001, today, null) }
        }
        assertEquals(4_000L, repository.goals.first().single().savedCents)
        assertEquals(4, repository.entries(goalId).first().size)
        assertEquals(2_000L, repository.plans.first().single().savedCents)
    }

    @Test
    fun editsArchivesAndDeletesPlansWithoutDeletingLedgerOrSavings() = runBlocking {
        val today = LocalDate.now(zone)
        val goal = repository.createGoal("旅行", 30_000, today.plusDays(30).toEpochDay())
        val plan = repository.createPlan(LivingPlan(
            startDay = today.toEpochDay(), budgetCents = 10_000, reserveCents = 3_000, goalId = goal, incomeId = null,
        ))
        repository.updatePlanValues(plan, 8_000, 2_000)
        repository.updateGoalDetails(goal, "旅行基金", 20_000)
        repository.transferFromPlan(plan, 2_000, zone)
        repository.archivePlan(plan)
        repository.archiveGoal(goal)
        assertEquals(true, repository.plans.first().single().plan.archived)
        assertEquals(true, repository.goals.first().single().goal.archived)
        repository.deletePlan(plan)
        assertEquals(emptyList<com.example.qingzhang.data.PlanProgress>(), repository.plans.first())
        val transfer = repository.entries(goal).first().single()
        assertEquals(null, transfer.planId)
        assertEquals(true, transfer.fromPlan)
        assertEquals(2_000L, transfer.amountCents)
        val updatedGoal = repository.goals.first().single()
        assertEquals(2_000L, updatedGoal.savedCents)
        assertEquals("旅行基金", updatedGoal.goal.name)
        assertEquals(today.plusDays(30).toEpochDay(), updatedGoal.goal.targetDay)
    }

    @Test
    fun refusesToArchiveGoalWhileAnUnarchivedPlanUsesIt() = runBlocking {
        val today = LocalDate.now(zone)
        val goal = repository.createGoal("旅行", 1_000)
        val plan = repository.createPlan(LivingPlan(
            startDay = today.toEpochDay(), budgetCents = 10_000, reserveCents = 3_000, goalId = goal, incomeId = null,
        ))
        repository.transferFromPlan(plan, 1_000, zone)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.archiveGoal(goal) }
        }
        assertEquals(false, repository.goals.first().single().goal.archived)
        repository.transferFromPlan(plan, 2_000, zone)
        assertEquals(3_000L, repository.goals.first().single().savedCents)
    }

    @Test
    fun legacyArchivedGoalReleasesOnlyTheUntransferredReserve() = runBlocking {
        val today = LocalDate.now(zone)
        val goal = repository.createGoal("旅行", 1_000)
        val plan = repository.createPlan(LivingPlan(
            startDay = today.toEpochDay(), budgetCents = 10_000, reserveCents = 3_000, goalId = goal, incomeId = null,
        ))
        repository.transferFromPlan(plan, 1_000, zone)
        val dao = database.livingPlanDao()
        dao.updateGoal(requireNotNull(dao.getGoal(goal)).copy(archived = true))
        val progress = repository.plans.first().single()
        val budget = progress.plan.budgetOn(today, 0, progress.savedCents, progress.goalArchived)
        assertEquals(9_000L, budget.remainingCents)
        assertEquals(0L, budget.transferableCents)
        assertEquals(1_000L, repository.entries(goal).first().single().amountCents)
    }

    @Test
    fun deletingGoalPreservesTransferredAmountAndReleasesOnlyUnspentReserve() = runBlocking {
        val today = LocalDate.now(zone)
        val goal = repository.createGoal("应急金", 10_000)
        val plan = repository.createPlan(LivingPlan(
            startDay = today.toEpochDay(), budgetCents = 10_000, reserveCents = 3_000, goalId = goal, incomeId = null,
        ))
        repository.transferFromPlan(plan, 2_000, zone)
        repository.deleteGoal(goal)
        val progress = repository.plans.first().single()
        assertEquals(null, progress.plan.goalId)
        assertEquals(2_000L, progress.plan.detachedSavingsCents)
        assertEquals(2_000L, progress.plan.reserveCents)
        assertEquals(2_000L, progress.savedCents)
        assertEquals(emptyList<com.example.qingzhang.data.GoalProgress>(), repository.goals.first())
    }

    @Test
    fun settledPlanCanTransferUnspentFundsOnce() = runBlocking {
        val today = LocalDate.now(zone)
        val start = today.minusDays(30)
        val goal = repository.createGoal("应急金", 10_000)
        val plan = repository.createPlan(LivingPlan(
            startDay = start.toEpochDay(), budgetCents = 10_000, reserveCents = 3_000, goalId = goal, incomeId = null,
        ))
        insertBill(4_500, TransactionType.EXPENSE, start.startMillis(zone))
        repository.transferFromPlan(plan, 5_500, zone)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.transferFromPlan(plan, 1, zone) }
        }
        assertEquals(5_500L, repository.goals.first().single().savedCents)
    }

    private suspend fun insertBill(amount: Long, type: TransactionType, timestamp: Long): Long =
        database.ledgerDao().insert(TransactionEntity(
            amountCents = amount,
            type = type,
            categoryId = if (type == TransactionType.EXPENSE) expenseCategory else incomeCategory,
            occurredAt = timestamp,
        ))
}
