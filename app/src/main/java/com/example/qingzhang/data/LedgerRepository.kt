package com.example.qingzhang.data

import androidx.room.withTransaction
import com.example.qingzhang.domain.endExclusiveMillis
import com.example.qingzhang.domain.startMillis
import com.example.qingzhang.domain.toLocalDate
import com.example.qingzhang.importer.LegacyImportMatch
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal data class LegacyImportPreview(
    val duplicates: Set<String> = emptySet(),
    val conflicts: Map<Long, List<String>> = emptyMap(),
)

class LedgerRepository(
    private val database: LedgerDatabase,
    private val settingsRepository: SettingsRepository,
) {
    private val ledgerDao = database.ledgerDao()
    private val categoryDao = database.categoryDao()

    val categories: Flow<List<CategoryEntity>> = categoryDao.observeAll()
    val settings: Flow<AppSettings> = settingsRepository.settings
    internal val livingPlans = LivingPlanRepository(database)
    internal val backup = LedgerBackup(database)

    suspend fun updateTheme(darkTheme: Boolean) = settingsRepository.updateTheme(darkTheme)

    suspend fun ensureDefaultCategories() {
        categoryDao.insertAll(DefaultCategories.values)
    }

    fun observeRecent(limit: Int = 20): Flow<List<TransactionDetail>> = ledgerDao.observeRecent(limit)

    fun observeRange(startInclusive: Long, endExclusive: Long): Flow<List<TransactionDetail>> =
        ledgerDao.observeBetween(startInclusive, endExclusive)

    fun observeBills(filter: BillFilter): Flow<List<TransactionDetail>> {
        require(!filter.end.isBefore(filter.start)) { "结束日期不能早于开始日期" }
        return ledgerDao.observeBetween(
            filter.start.startMillis(),
            filter.end.endExclusiveMillis(),
        ).map { records -> records.filter { it.matches(filter) } }
    }

    suspend fun getBills(filter: BillFilter): List<TransactionDetail> {
        require(!filter.end.isBefore(filter.start)) { "结束日期不能早于开始日期" }
        return ledgerDao.getBetween(filter.start.startMillis(), filter.end.endExclusiveMillis())
            .filter { it.matches(filter) }
    }

    suspend fun getTransaction(id: Long): TransactionDetail? = ledgerDao.getDetailById(id)

    suspend fun save(input: TransactionInput): SaveOutcome {
        validate(input)
        val appSettings = settingsRepository.settings.first()
        return database.withTransaction {
            val category = categoryDao.getById(input.categoryId)
                ?: throw IllegalArgumentException("请选择有效分类")
            require(category.type == input.type) { "分类与收支类型不一致" }
            val now = System.currentTimeMillis()
            val id = if (input.id == 0L) {
                ledgerDao.insert(
                    TransactionEntity(
                        amountCents = input.amountCents,
                        type = input.type,
                        categoryId = input.categoryId,
                        occurredAt = input.occurredAt,
                        note = input.note.trim(),
                        merchant = input.merchant.trim(),
                        createdAt = now,
                        updatedAt = now,
                        extraDailyExpense = input.extraDailyExpense && input.type == TransactionType.EXPENSE,
                    ),
                )
            } else {
                val existing = ledgerDao.getById(input.id)
                    ?: throw IllegalArgumentException("账目已不存在，请返回列表刷新")
                ledgerDao.update(
                    existing.copy(
                        amountCents = input.amountCents,
                        type = input.type,
                        categoryId = input.categoryId,
                        occurredAt = input.occurredAt,
                        note = input.note.trim(),
                        merchant = input.merchant.trim(),
                        extraDailyExpense = input.extraDailyExpense && input.type == TransactionType.EXPENSE,
                        updatedAt = now,
                    ),
                )
                input.id
            }
            val warning = if (input.type == TransactionType.EXPENSE) {
                budgetWarning(input.occurredAt.toLocalDate(), appSettings)
            } else {
                null
            }
            SaveOutcome(id, warning)
        }
    }

    suspend fun delete(id: Long) {
        database.withTransaction {
            val existing = ledgerDao.getById(id) ?: return@withTransaction
            ledgerDao.delete(existing)
        }
    }

    suspend fun existingImportKeys(keys: Collection<String>): Set<String> =
        keys.distinct().chunked(500).flatMap { ledgerDao.findExistingImportKeys(it) }.toSet()

    internal suspend fun previewLegacyImports(matchesByImportKey: Map<String, List<LegacyImportMatch>>): LegacyImportPreview {
        val existing = matchesByImportKey.values.flatten().map { it.key }.distinct().chunked(500)
            .flatMap { ledgerDao.findImportedTransactions(it) }.associateBy { it.importKey }
        if (existing.isEmpty()) return LegacyImportPreview()
        val exactKeys = existingImportKeys(matchesByImportKey.keys)
        val candidatesById = mutableMapOf<Long, MutableList<String>>()
        for ((importKey, matches) in matchesByImportKey) {
            if (importKey in exactKeys) continue
            for (match in matches) {
                val transaction = existing[match.key] ?: continue
                if (match.matches(transaction)) {
                    candidatesById.getOrPut(transaction.id) { mutableListOf() }.add(importKey)
                }
            }
        }
        val conflicts = candidatesById.filterValues { keys ->
            // Occurrences of the same fingerprint have no distinguishing fields.
            keys.map { if (it.startsWith("v3:")) it.substringBeforeLast(':') else it }.distinct().size > 1
        }
        val duplicates = mutableSetOf<String>()
        for ((id, keys) in candidatesById) {
            if (id !in conflicts) keys.firstOrNull { it !in duplicates }?.let(duplicates::add)
        }
        return LegacyImportPreview(duplicates, conflicts)
    }

    suspend fun importTransactions(
        transactions: List<TransactionEntity>,
        legacyMatches: Map<String, List<LegacyImportMatch>> = emptyMap(),
        legacyChoices: Map<Long, String> = emptyMap(),
    ): ImportCommitResult {
        require(transactions.size <= 5_000) { "单次最多导入 5000 条账目" }
        val appSettings = settingsRepository.settings.first()
        return database.withTransaction {
            transactions.forEach { entity ->
                require(entity.source != ImportSource.MANUAL && !entity.importKey.isNullOrBlank()) {
                    "导入账目缺少来源或去重标识"
                }
                validate(
                    TransactionInput(
                        amountCents = entity.amountCents,
                        type = entity.type,
                        categoryId = entity.categoryId,
                        occurredAt = entity.occurredAt,
                        note = entity.note,
                        merchant = entity.merchant,
                    ),
                )
                val category = categoryDao.getById(entity.categoryId)
                require(category?.type == entity.type) { "导入账目的分类与收支类型不一致" }
            }
            val preview = previewLegacyImports(legacyMatches)
            require(legacyChoices.keys == preview.conflicts.keys &&
                legacyChoices.values.toSet().size == legacyChoices.size &&
                legacyChoices.all { (id, key) -> key in preview.conflicts.getValue(id) }
            ) { "旧账匹配尚未确认或已变化，请重新核对账单" }
            legacyChoices.forEach { (id, key) -> ledgerDao.assignImportKey(id, key) }
            val legacyDuplicates = preview.duplicates + legacyChoices.values
            val newTransactions = transactions.filterNot { it.importKey in legacyDuplicates }
            val insertedIds = ledgerDao.insertImported(newTransactions)
            val importedDates = newTransactions.zip(insertedIds)
                .filter { (_, id) -> id != -1L }
                .map { (entity, _) -> entity.occurredAt.toLocalDate() }
                .distinct()
            val warnings = if (appSettings.dailyLimitEnabled) {
                importedDates.mapNotNull { budgetWarning(it, appSettings) }.sortedBy { it.date }
            } else {
                emptyList()
            }
            val importedCount = insertedIds.count { it != -1L }
            ImportCommitResult(
                importedCount = importedCount,
                duplicateCount = transactions.size - importedCount,
                warnings = warnings,
            )
        }
    }

    suspend fun addCategory(name: String, type: TransactionType) {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "分类名称不能为空" }
        require(normalized.length <= 12) { "分类名称最多 12 个字符" }
        val colorPalette = listOf(0xFF5276C9, 0xFFB36A5E, 0xFF648C72, 0xFF9A6FB3, 0xFF9B843D)
        categoryDao.insert(
            CategoryEntity(
                name = normalized,
                type = type,
                color = colorPalette[(normalized.codePointSum() % colorPalette.size)],
                isCustom = true,
                sortOrder = 100,
            ),
        )
    }

    suspend fun deleteCategory(id: Long) = database.withTransaction {
        val category = categoryDao.getById(id) ?: return@withTransaction
        require(category.isCustom) { "内置分类不可删除" }
        require(categoryDao.transactionCount(id) == 0) { "该分类已有账目，请先修改相关账目的分类" }
        categoryDao.delete(category)
    }

    suspend fun updateDailyLimit(limitCents: Long, enabled: Boolean) =
        settingsRepository.update(limitCents, enabled)

    private suspend fun budgetWarning(date: LocalDate, appSettings: AppSettings): BudgetWarning? {
        if (!appSettings.dailyLimitEnabled) return null
        val total = ledgerDao.dailyLimitExpenseBetween(date.startMillis(), date.endExclusiveMillis())
        return if (total > appSettings.dailyLimitCents) {
            BudgetWarning(date, total, appSettings.dailyLimitCents)
        } else {
            null
        }
    }

    private fun validate(input: TransactionInput) {
        require(input.amountCents > 0) { "金额必须大于 0" }
        require(input.note.trim().length <= 200) { "备注最多 200 个字符" }
        require(input.merchant.trim().length <= 100) { "商户/来源最多 100 个字符" }
    }

    private fun String.codePointSum(): Int = codePoints().sum()
}

private fun TransactionDetail.matches(filter: BillFilter): Boolean {
    if (filter.type != null && transaction.type != filter.type) return false
    if (filter.categoryId != null && transaction.categoryId != filter.categoryId) return false
    if (filter.query.isBlank()) return true
    val query = filter.query.trim()
    return transaction.note.contains(query, ignoreCase = true) ||
        transaction.merchant.contains(query, ignoreCase = true) ||
        categoryName.contains(query, ignoreCase = true)
}
