package com.example.qingzhang.data

import androidx.room.withTransaction
import com.example.qingzhang.domain.LIVING_PLAN_DAYS
import com.example.qingzhang.domain.budgetOn
import com.example.qingzhang.domain.endDate
import com.example.qingzhang.domain.endExclusiveMillis
import com.example.qingzhang.domain.startDate
import com.example.qingzhang.domain.startMillis
import java.time.LocalDate
import java.time.ZoneId

class LivingPlanRepository(private val database: LedgerDatabase) {
    private val dao = database.livingPlanDao()
    private val ledger = database.ledgerDao()

    val plans = dao.observePlans()
    val goals = dao.observeGoals()
    val incomes = dao.observeIncome()

    fun entries(goalId: Long) = dao.observeEntries(goalId)

    fun spending(plan: LivingPlan, zone: ZoneId) =
        dao.observeSpending(plan.startDate.startMillis(zone), plan.endDate.endExclusiveMillis(zone))

    suspend fun createPlan(plan: LivingPlan): Long = database.withTransaction {
        require(plan.budgetCents > 0) { "请输入有效的生活费金额" }
        require(plan.reserveCents in 0..plan.budgetCents) { "计划攒钱金额不能超过生活费" }
        require(plan.reserveCents == 0L || plan.goalId != null) { "请先选择攒钱目标" }
        require(plan.goalId == null || dao.hasGoal(plan.goalId)) { "攒钱目标不存在" }
        require(plan.incomeId == null || ledger.getById(plan.incomeId)?.type == TransactionType.INCOME) { "请选择有效的收入账目" }
        val overlap = LIVING_PLAN_DAYS - 1
        require(!dao.hasPlanBetween(plan.startDay - overlap, plan.startDay + overlap)) { "这 30 天与已有计划重叠，请调整开始日期" }
        dao.insertPlan(plan)
    }

    suspend fun createGoal(name: String, targetCents: Long): Long {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 40) { "目标名称须为 1～40 个字" }
        require(targetCents > 0) { "请输入有效的目标金额" }
        return dao.insertGoal(SavingsGoal(name = title, targetCents = targetCents))
    }

    suspend fun transferFromPlan(planId: Long, amountCents: Long, zone: ZoneId) = database.withTransaction {
        val plan = requireNotNull(dao.getPlan(planId)) { "生活费计划不存在" }
        val goalId = requireNotNull(plan.goalId) { "本期尚未关联攒钱目标" }
        val today = LocalDate.now(zone)
        val spent = ledger.expenseTotalBetween(plan.startDate.startMillis(zone), plan.endDate.endExclusiveMillis(zone))
        val budget = plan.budgetOn(today, spent, dao.planSavings(plan.id))
        require(amountCents in 1..budget.transferableCents) { "转入金额超过本期可转金额" }
        addEntry(goalId, planId, amountCents, today)
    }

    suspend fun recordSavings(goalId: Long, amountCents: Long, date: LocalDate, incomeCategoryId: Long?) = database.withTransaction {
        require(amountCents != 0L) { "请输入有效金额" }
        require(dao.hasGoal(goalId)) { "攒钱目标不存在" }
        if (incomeCategoryId != null) {
            require(amountCents > 0 && database.categoryDao().getById(incomeCategoryId)?.type == TransactionType.INCOME) {
                "请选择有效的收入分类"
            }
            ledger.insert(TransactionEntity(
                amountCents = amountCents,
                type = TransactionType.INCOME,
                categoryId = incomeCategoryId,
                occurredAt = date.startMillis(),
                note = "攒钱目标存入",
            ))
        }
        addEntry(goalId, null, amountCents, date)
    }

    private suspend fun addEntry(goalId: Long, planId: Long?, amountCents: Long, date: LocalDate) {
        val balance = Math.addExact(dao.goalBalance(goalId), amountCents)
        require(balance >= 0) { "取出金额超过目标已攒金额" }
        dao.insertEntry(SavingsEntry(goalId = goalId, planId = planId, amountCents = amountCents, day = date.toEpochDay()))
    }
}
