package com.example.qingzhang.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.qingzhang.data.AppSettings
import com.example.qingzhang.data.BillFilter
import com.example.qingzhang.data.CategoryEntity
import com.example.qingzhang.data.LedgerRepository
import com.example.qingzhang.data.LivingPlan
import com.example.qingzhang.data.SaveOutcome
import com.example.qingzhang.data.TransactionDetail
import com.example.qingzhang.data.TransactionInput
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.CsvExporter
import com.example.qingzhang.domain.MonthlyAnalysis
import com.example.qingzhang.domain.StatisticsCalculator
import com.example.qingzhang.domain.StatisticsResult
import com.example.qingzhang.domain.StatsPeriod
import com.example.qingzhang.domain.endExclusiveMillis
import com.example.qingzhang.domain.endDate
import com.example.qingzhang.domain.startDate
import com.example.qingzhang.domain.startMillis
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatsSelection(
    val period: StatsPeriod = StatsPeriod.MONTH,
    val anchor: LocalDate = LocalDate.now(),
)

data class HomeContent(val day: ZonedDateTime, val records: List<TransactionDetail> = emptyList())

data class StatsContent(
    val selection: StatsSelection = StatsSelection(),
    val statistics: StatisticsResult = StatisticsResult(
        summary = com.example.qingzhang.domain.PeriodSummary(),
        categories = emptyList(),
        trend = emptyList(),
    ),
    val monthlyAnalysis: MonthlyAnalysis? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(private val repository: LedgerRepository) : ViewModel() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val categories: StateFlow<List<CategoryEntity>> = repository.categories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val homeDay = MutableStateFlow(ZonedDateTime.now().let { it.toLocalDate().atStartOfDay(it.zone) })
    val currentDay = homeDay.asStateFlow()
    val livingPlans = repository.livingPlans.plans
    val savingsGoals = repository.livingPlans.goals
    val planIncomes = repository.livingPlans.incomes
    val activeLivingPlan = combine(livingPlans, currentDay) { plans, day ->
        plans.firstOrNull { day.toLocalDate() in it.plan.startDate..it.plan.endDate } to day.zone
    }.distinctUntilChanged().flatMapLatest { (plan, zone) ->
        if (plan == null) flowOf(null) else observePlanSpending(plan.plan, zone).map { plan to it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val homeContent: StateFlow<HomeContent> = homeDay.flatMapLatest { day ->
        val month = YearMonth.from(day)
        repository.observeRange(month.startMillis(day.zone), month.endExclusiveMillis(day.zone))
            .map { HomeContent(day, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeContent(homeDay.value))
    val recentRecords: StateFlow<List<TransactionDetail>> = repository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _billFilter = MutableStateFlow(YearMonth.from(homeDay.value).let { BillFilter(it.atDay(1), it.atEndOfMonth()) })
    val billFilter = _billFilter.asStateFlow()
    val bills: StateFlow<List<TransactionDetail>> = _billFilter.flatMapLatest(repository::observeBills)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _statsSelection = MutableStateFlow(StatsSelection())
    val statsContent: StateFlow<StatsContent> = _statsSelection.flatMapLatest { selection ->
        val (start, end) = StatisticsCalculator.queryRange(selection.period, selection.anchor)
        repository.observeRange(start.startMillis(), end.endExclusiveMillis())
            .combine(_statsSelection) { records, currentSelection ->
                StatsContent(
                    selection = currentSelection,
                    statistics = StatisticsCalculator.calculate(records, currentSelection.period, currentSelection.anchor),
                    monthlyAnalysis = if (currentSelection.period == StatsPeriod.MONTH) {
                        StatisticsCalculator.analyzeMonth(records, YearMonth.from(currentSelection.anchor))
                    } else {
                        null
                    },
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsContent())

    init {
        viewModelScope.launch { repository.ensureDefaultCategories() }
    }

    fun refreshHomeDate(now: ZonedDateTime = ZonedDateTime.now()) {
        homeDay.value = now.toLocalDate().atStartOfDay(now.zone)
    }

    fun updateBillFilter(transform: (BillFilter) -> BillFilter) {
        val updated = transform(_billFilter.value)
        if (updated.end.isBefore(updated.start)) {
            _messages.tryEmit("结束日期不能早于开始日期")
        } else {
            _billFilter.value = updated
        }
    }

    fun selectStatsPeriod(period: StatsPeriod) {
        _statsSelection.value = _statsSelection.value.copy(period = period)
    }

    fun moveStats(direction: Int) {
        val selection = _statsSelection.value
        val anchor = when (selection.period) {
            StatsPeriod.DAY -> selection.anchor.plusDays(direction.toLong())
            StatsPeriod.MONTH -> selection.anchor.plusMonths(direction.toLong())
            StatsPeriod.YEAR -> selection.anchor.plusYears(direction.toLong())
        }
        _statsSelection.value = selection.copy(anchor = anchor)
    }

    suspend fun getTransaction(id: Long): TransactionDetail? = repository.getTransaction(id)

    suspend fun saveTransaction(input: TransactionInput): Result<SaveOutcome> = runCatching {
        repository.save(input)
    }

    fun deleteTransaction(id: Long, onComplete: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onSuccess {
                    _messages.emit("账目已删除")
                    onComplete()
                }
                .onFailure { _messages.emit(it.message ?: "删除失败") }
        }
    }

    suspend fun exportCurrentBills(): ByteArray = withContext(Dispatchers.IO) {
        CsvExporter.export(repository.getBills(_billFilter.value))
    }

    fun addCategory(name: String, type: TransactionType) {
        viewModelScope.launch {
            runCatching { repository.addCategory(name, type) }
                .onSuccess { _messages.emit("分类已添加") }
                .onFailure {
                    val message = if (it is android.database.sqlite.SQLiteConstraintException) {
                        "同一收支类型下已存在该分类"
                    } else {
                        it.message ?: "分类添加失败，请稍后重试"
                    }
                    _messages.emit(message)
                }
        }
    }

    fun deleteCategory(id: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteCategory(id) }
                .onSuccess { _messages.emit("分类已删除") }
                .onFailure { _messages.emit(it.message ?: "分类删除失败") }
        }
    }

    fun saveDailyLimit(cents: Long, enabled: Boolean, onComplete: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.updateDailyLimit(cents, enabled) }
                .onSuccess {
                    _messages.emit("每日限额已保存")
                    onComplete()
                }
                .onFailure { _messages.emit(it.message ?: "限额保存失败") }
        }
    }

    fun setDarkTheme(darkTheme: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateTheme(darkTheme)
            } catch (_: IOException) {
                _messages.emit("主题保存失败，请重试")
            }
        }
    }

    fun observePlanSpending(plan: LivingPlan, zone: ZoneId) = repository.livingPlans.spending(plan, zone)

    fun observeSavingsEntries(goalId: Long) = repository.livingPlans.entries(goalId)

    suspend fun createLivingPlan(plan: LivingPlan) = repository.livingPlans.createPlan(plan)

    suspend fun createSavingsGoal(name: String, targetCents: Long) = repository.livingPlans.createGoal(name, targetCents)

    suspend fun transferPlanSavings(planId: Long, amountCents: Long, zone: ZoneId) =
        repository.livingPlans.transferFromPlan(planId, amountCents, zone)

    suspend fun recordSavings(goalId: Long, amountCents: Long, date: LocalDate, incomeCategoryId: Long?) =
        repository.livingPlans.recordSavings(goalId, amountCents, date, incomeCategoryId)

    companion object {
        fun factory(repository: LedgerRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(repository) as T
        }
    }
}
