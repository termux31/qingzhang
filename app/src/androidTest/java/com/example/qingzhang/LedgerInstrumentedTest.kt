package com.example.qingzhang

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.qingzhang.data.BillFilter
import com.example.qingzhang.data.ImportSource
import com.example.qingzhang.data.LedgerDatabase
import com.example.qingzhang.data.LedgerRepository
import com.example.qingzhang.data.SettingsRepository
import com.example.qingzhang.data.TransactionEntity
import com.example.qingzhang.data.TransactionInput
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.StatsPeriod
import com.example.qingzhang.domain.toMillis
import com.example.qingzhang.importer.ImportCandidate
import com.example.qingzhang.importer.StatementImporter
import com.example.qingzhang.ui.LedgerViewModel
import com.example.qingzhang.ui.ImportViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerInstrumentedTest {
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LedgerRepository(database, SettingsRepository(context))
        repository.ensureDefaultCategories()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun persistsAggregatesFiltersAndWarnsOverLimit() = runBlocking {
        repository.updateDailyLimit(limitCents = 20_000, enabled = true)
        val food = database.categoryDao().getAll().first { it.name == "餐饮" && it.type == TransactionType.EXPENSE }
        val salary = database.categoryDao().getAll().first { it.name == "工资" && it.type == TransactionType.INCOME }
        val date = LocalDate.of(2026, 10, 7)
        val expense = repository.save(
            TransactionInput(
                amountCents = 25_050,
                type = TransactionType.EXPENSE,
                categoryId = food.id,
                occurredAt = date.atTime(12, 30).toMillis(),
                note = "午餐",
                merchant = "测试商户",
            ),
        )
        assertTrue(expense.transactionId > 0)
        assertEquals(5_050L, expense.warning?.exceededCents)

        repository.save(
            TransactionInput(
                amountCents = 500_000,
                type = TransactionType.INCOME,
                categoryId = salary.id,
                occurredAt = date.atTime(9, 0).toMillis(),
            ),
        )
        val records = repository.getBills(BillFilter(date, date))
        assertEquals(2, records.size)
        val filtered = repository.getBills(BillFilter(date, date, type = TransactionType.EXPENSE))
        assertEquals(1, filtered.size)
        assertEquals(25_050L, filtered.first().transaction.amountCents)
        assertEquals("餐饮", filtered.first().categoryName)
    }

    @Test
    fun extraDailyExpenseIsExcludedFromLimitButKeptInLedger() = runBlocking {
        repository.updateDailyLimit(limitCents = 10_000, enabled = true)
        val category = database.categoryDao().getAll().first { it.type == TransactionType.EXPENSE }
        val date = LocalDate.of(2026, 11, 8)
        val extra = repository.save(TransactionInput(
            amountCents = 15_000,
            type = TransactionType.EXPENSE,
            categoryId = category.id,
            occurredAt = date.atTime(12, 0).toMillis(),
            extraDailyExpense = true,
        ))
        assertEquals(null, extra.warning)
        val normal = repository.save(TransactionInput(
            amountCents = 12_000,
            type = TransactionType.EXPENSE,
            categoryId = category.id,
            occurredAt = date.atTime(13, 0).toMillis(),
        ))
        assertEquals(2_000L, normal.warning?.exceededCents)
        val bills = repository.getBills(BillFilter(date, date, type = TransactionType.EXPENSE))
        assertEquals(27_000L, bills.sumOf { it.transaction.amountCents })
    }

    @Test
    fun enforcesUniqueImportFingerprint() = runBlocking {
        val category = database.categoryDao().getAll().first { it.type == TransactionType.EXPENSE }
        val entity = TransactionEntity(
            amountCents = 990,
            type = TransactionType.EXPENSE,
            categoryId = category.id,
            occurredAt = LocalDateTime.of(2026, 10, 8, 8, 0).toMillis(),
            note = "早餐",
            merchant = "早餐店",
            source = ImportSource.WECHAT,
            externalId = "WX-DUP",
            importKey = "fingerprint-dup",
        )
        val result = repository.importTransactions(listOf(entity, entity.copy(id = 0)))
        assertEquals(1, result.importedCount)
        assertEquals(1, result.duplicateCount)
        assertEquals(setOf("fingerprint-dup"), repository.existingImportKeys(listOf("fingerprint-dup")))
    }

    @Test
    fun refreshesHomeQueryAndDateTogetherAtMonthBoundary() = runBlocking {
        val category = database.categoryDao().getAll().first { it.type == TransactionType.EXPENSE }
        val zone = ZoneId.systemDefault()
        val lastDay = LocalDate.of(2026, 10, 31)
        val nextDay = lastDay.plusDays(1)
        val oldId = repository.save(TransactionInput(
            amountCents = 100, type = category.type, categoryId = category.id,
            occurredAt = lastDay.atTime(12, 0).toMillis(zone),
        )).transactionId
        val newId = repository.save(TransactionInput(
            amountCents = 200, type = category.type, categoryId = category.id,
            occurredAt = nextDay.atTime(12, 0).toMillis(zone),
        )).transactionId
        val store = ViewModelStore()
        val viewModel = withContext(Dispatchers.Main) {
            ViewModelProvider(store, LedgerViewModel.factory(repository))[LedgerViewModel::class.java]
        }
        try {
            viewModel.refreshHomeDate(lastDay.atStartOfDay(zone))
            val old = withTimeout(5_000) { viewModel.homeContent.first { it.records.isNotEmpty() } }
            assertEquals(lastDay, old.day.toLocalDate())
            assertEquals(oldId, old.records.single().transaction.id)
            viewModel.refreshHomeDate(nextDay.atStartOfDay(zone))
            val updated = withTimeout(5_000) { viewModel.homeContent.first { it.day.toLocalDate() == nextDay } }
            assertEquals(newId, updated.records.single().transaction.id)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    @Test
    fun statsPeriodChangesPublishOnlyTotalsFromTheMatchingQuery() = runBlocking {
        val category = database.categoryDao().getAll().first { it.type == TransactionType.EXPENSE }
        val store = ViewModelStore()
        val viewModel = withContext(Dispatchers.Main) {
            ViewModelProvider(store, LedgerViewModel.factory(repository))[LedgerViewModel::class.java]
        }
        try {
            val anchor = viewModel.statsContent.value.selection.anchor
            val otherMonth = anchor.withMonth(if (anchor.monthValue == 1) 12 else 1).withDayOfMonth(1)
            for ((day, cents) in listOf(anchor to 100L, otherMonth to 200L)) {
                repository.save(TransactionInput(
                    amountCents = cents, type = category.type, categoryId = category.id,
                    occurredAt = day.atTime(12, 0).toMillis(),
                ))
            }
            val month = withTimeout(5_000) { viewModel.statsContent.first { it.monthlyAnalysis != null } }
            assertEquals(100L, month.statistics.summary.expenseCents)

            withContext(Dispatchers.Main) { viewModel.selectStatsPeriod(StatsPeriod.YEAR) }
            val year = withTimeout(5_000) { viewModel.statsContent.first { it.selection.period == StatsPeriod.YEAR } }
            assertEquals(300L, year.statistics.summary.expenseCents)

            withContext(Dispatchers.Main) { viewModel.selectStatsPeriod(StatsPeriod.MONTH) }
            val nextMonth = withTimeout(5_000) { viewModel.statsContent.first { it.selection.period == StatsPeriod.MONTH } }
            assertEquals(100L, nextMonth.statistics.summary.expenseCents)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    @Test
    fun keepsDistinctRowsThatCollidedInLegacyImports() = runBlocking {
        val csv = """微信支付账单
交易时间,交易对方,商品,收/支,金额
2026-10-08 08:00:00,甲|乙,丙,支出,9.90
2026-10-08 08:00:00,甲,乙|丙,支出,9.90
"""
        val categories = database.categoryDao().getAll()
        val (first, second) = StatementImporter.parse(csv.toByteArray(), "wechat.csv", null, categories).candidates
        assertFalse(first.duplicate)
        assertFalse(second.duplicate)
        val legacyKey = MessageDigest.getInstance("SHA-256")
            .digest("wechat|${first.occurredAt}|990|expense|甲|乙|丙".toByteArray())
            .joinToString("") { "%02x".format(it) }
        database.ledgerDao().insert(first.asTransaction().copy(importKey = legacyKey))
        val aliases = listOf(first, second).associate { it.importKey to it.legacyMatches }
        assertEquals(setOf(first.importKey), repository.previewLegacyImports(aliases).duplicates)

        val transactions = listOf(first.asTransaction(), second.asTransaction())
        val imported = repository.importTransactions(transactions, aliases)
        assertEquals(1, imported.importedCount)
        assertEquals(1, imported.duplicateCount)
        val reimport = repository.importTransactions(transactions, aliases)
        assertEquals(0, reimport.importedCount)
        assertEquals(2, reimport.duplicateCount)
        val date = LocalDate.of(2026, 10, 8)
        assertEquals(setOf("甲|乙", "甲"), repository.getBills(BillFilter(date, date)).map { it.transaction.merchant }.toSet())
    }

    @Test
    fun confirmsLegacyMerchantOrderWithoutDependingOnFileOrder() = runBlocking {
        val csv = """微信支付账单
交易时间,交易对方,商品,收/支,金额,商户单号
2026-11-08 12:00:00,商户,商品,支出,25,M-1
2026-11-08 12:00:00,商户,商品,支出,25,M-2
"""
        val categories = database.categoryDao().getAll()
        val (first, second) = StatementImporter.parse(csv.toByteArray(), "wechat.csv", null, categories).candidates
        val legacyKey = MessageDigest.getInstance("SHA-256")
            .digest("wechat|${first.occurredAt}|2500|expense|商户|商品".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val oldId = database.ledgerDao().insert(first.asTransaction().copy(importKey = legacyKey))
        val aliases = listOf(first, second).associate { it.importKey to it.legacyMatches }
        val reversed = listOf(second, first)
        val reversedAliases = reversed.associate { it.importKey to it.legacyMatches }
        for (order in listOf(aliases, reversedAliases)) {
            val preview = repository.previewLegacyImports(order)
            assertTrue(preview.duplicates.isEmpty())
            assertEquals(setOf(first.importKey, second.importKey), preview.conflicts.getValue(oldId).toSet())
        }
        val unconfirmed = runCatching { repository.importTransactions(listOf(first.asTransaction()), reversedAliases) }
        assertTrue(unconfirmed.exceptionOrNull() is IllegalArgumentException)
        assertEquals(legacyKey, database.ledgerDao().getById(oldId)?.importKey)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("legacy-order-", ".csv", context.cacheDir)
        val viewModel = ImportViewModel(repository)
        try {
            val lines = csv.trimEnd().lines()
            file.writeText((lines.take(2) + lines.drop(2).reversed()).joinToString("\n"))
            withContext(Dispatchers.Main) { viewModel.parse(context, Uri.fromFile(file)) }
            val preview = withTimeout(10_000) { viewModel.state.first { it.result != null } }
            val rows = requireNotNull(preview.result).candidates
            assertEquals(listOf("M-2", "M-1"), rows.map { it.merchantOrderId })
            assertTrue(rows.none { it.duplicate || it.selected })
            withContext(Dispatchers.Main) {
                viewModel.chooseLegacyMatch(oldId, first.importKey)
                viewModel.toggle(rows.first().id, true)
                viewModel.commit()
            }
            val committed = withTimeout(10_000) { viewModel.state.first { it.commitMessage.isNotEmpty() } }
            assertTrue(committed.commitMessage.contains("成功导入 1 条"))
        } finally {
            withContext(Dispatchers.Main) { viewModel.reset() }
            file.delete()
        }
        assertEquals(first.importKey, database.ledgerDao().getById(oldId)?.importKey)

        val reimport = repository.importTransactions(reversed.map { it.asTransaction() }, reversed.associate { it.importKey to it.legacyMatches })
        assertEquals(0, reimport.importedCount)
        assertEquals(2, reimport.duplicateCount)
    }

    @Test
    fun matchesPreviouslyMisclassifiedSourceOnlyWhenRowFieldsAgree() = runBlocking {
        val csv = """支付宝交易明细
交易时间,交易对方,收/支,金额,交易单号
2026-10-08 08:00:00,微信支付,支出,9.90,A-1
""".toByteArray()
        val categories = database.categoryDao().getAll()
        val old = StatementImporter.parse(csv.toString(Charsets.UTF_8).substringAfter('\n').toByteArray(), "alipay.csv", ImportSource.WECHAT, categories).candidates.single()
        val corrected = StatementImporter.parse(csv, "alipay.csv", null, categories).candidates.single()
        assertEquals(ImportSource.ALIPAY, corrected.source)
        val aliases = mapOf(corrected.importKey to corrected.legacyMatches)
        val oldId = database.ledgerDao().insert(old.asTransaction().copy(amountCents = 100))
        assertTrue(repository.previewLegacyImports(aliases).duplicates.isEmpty())
        database.ledgerDao().update(old.asTransaction().copy(id = oldId))
        assertEquals(setOf(corrected.importKey), repository.previewLegacyImports(aliases).duplicates)
        assertEquals(0, repository.importTransactions(listOf(corrected.asTransaction()), aliases).importedCount)
    }

    private fun ImportCandidate.asTransaction() = TransactionEntity(
        amountCents = amountCents,
        type = type,
        categoryId = requireNotNull(categoryId),
        occurredAt = occurredAt,
        note = note,
        merchant = merchant,
        source = source,
        externalId = externalId,
        importKey = importKey,
    )

    @Test
    fun blocksDeletingUsedCategory() = runBlocking {
        repository.addCategory("测试宠物", TransactionType.EXPENSE)
        val category = database.categoryDao().getAll().first { it.name == "测试宠物" }
        repository.save(
            TransactionInput(
                amountCents = 100,
                type = TransactionType.EXPENSE,
                categoryId = category.id,
                occurredAt = System.currentTimeMillis(),
            ),
        )
        val failure = runCatching { repository.deleteCategory(category.id) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("已有账目"))
    }

    @Test
    fun readsXlsxUsingAndroidXmlParser() {
        val shared = """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>微信支付账单</t></si><si><t>交易时间</t></si><si><t>交易对方</t></si><si><t>商品</t></si><si><t>收/支</t></si><si><t>金额(元)</t></si><si><t>2026-10-01 12:30:00</t></si><si><t>咖啡店</t></si><si><t>咖啡</t></si><si><t>支出</t></si></sst>"""
        val sheet = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row><row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2" t="s"><v>2</v></c><c r="C2" t="s"><v>3</v></c><c r="D2" t="s"><v>4</v></c><c r="E2" t="s"><v>5</v></c></row><row r="3"><c r="A3" t="s"><v>6</v></c><c r="B3" t="s"><v>7</v></c><c r="C3" t="s"><v>8</v></c><c r="D3" t="s"><v>9</v></c><c r="E3"><v>18.80</v></c></row></sheetData></worksheet>"""
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(shared.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/custom-name.xml"))
            zip.write(sheet.toByteArray())
            zip.closeEntry()
        }
        val categories = runBlocking { database.categoryDao().getAll() }
        val result = StatementImporter.parse(output.toByteArray(), "wechat.xlsx", null, categories)
        assertEquals(1, result.candidates.size)
        assertEquals(1880L, result.candidates.single().amountCents)
        assertEquals("餐饮", result.candidates.single().suggestedCategoryName)
    }
}
