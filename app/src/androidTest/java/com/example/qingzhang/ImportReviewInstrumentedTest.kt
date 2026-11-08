package com.example.qingzhang

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.qingzhang.data.BillFilter
import com.example.qingzhang.data.LedgerDatabase
import com.example.qingzhang.data.LedgerRepository
import com.example.qingzhang.data.SettingsRepository
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.ui.ImportViewModel
import java.io.File
import java.time.LocalDate
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportReviewInstrumentedTest {
    @Test
    fun selectableIdenticalRowsStayImportedAfterChangingTimeZone() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repository = LedgerRepository(database, SettingsRepository(context))
        val viewModel = ImportViewModel(repository)
        val file = File.createTempFile("import-timezone-", ".csv", context.cacheDir)
        val date = LocalDate.of(2026, 11, 8)
        val previousZone = TimeZone.getDefault()
        suspend fun preview() = withTimeout(10_000) {
            withContext(Dispatchers.Main) {
                viewModel.reset()
                viewModel.parse(context, Uri.fromFile(file))
            }
            requireNotNull(viewModel.state.first { it.result != null }.result).candidates
        }
        try {
            file.writeText(
                """
                微信支付账单
                交易时间,交易对方,收/支,金额
                $date 12:00:00,商户,支出,25
                $date 12:00:00,商户,支出,25
                """.trimIndent(),
            )
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val initial = preview()
            assertEquals(listOf(true, false), initial.map { it.selected })
            withContext(Dispatchers.Main) {
                viewModel.toggle(initial[1].id, true)
                viewModel.commit()
            }
            withTimeout(10_000) { viewModel.state.first { it.commitMessage.isNotEmpty() } }
            assertEquals(2, repository.getBills(BillFilter(date, date)).size)

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(listOf(true, true), preview().map { it.duplicate })
        } finally {
            TimeZone.setDefault(previousZone)
            withContext(Dispatchers.Main) { viewModel.reset() }
            database.close()
            file.delete()
        }
    }

    @Test
    fun skipsFailedPaymentsAndCommitsPendingRowsOnlyAfterReview() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repository = LedgerRepository(database, SettingsRepository(context))
        val viewModel = ImportViewModel(repository)
        val file = File.createTempFile("import-review-", ".csv", context.cacheDir)
        val date = LocalDate.of(2026, 10, 8)
        val filter = BillFilter(date, date)

        try {
            file.writeText(
                """
                微信支付账单明细
                交易时间,收/支,金额(元),交易单号,当前状态,收入金额,支出金额
                $date 08:00:00,支出,9.90,WX-KNOWN,支付成功,,
                $date 09:00:00,未知,20.00,WX-REVIEW,,,
                $date 10:00:00,支出,12.50,WX-FAILED,支付失败,,
                $date 11:00:00,支出,9,WX-CONFLICT,支付成功,1,9
                """.trimIndent(),
            )
            val uri = Uri.fromFile(file)
            withContext(Dispatchers.Main) { viewModel.parse(context, uri) }
            val initial = withTimeout(10_000) { viewModel.state.first { it.result != null } }
            val candidates = requireNotNull(initial.result).candidates
            assertEquals(listOf("WX-KNOWN", "WX-REVIEW", "WX-CONFLICT"), candidates.map { it.externalId })
            val unknown = candidates.single { it.externalId == "WX-REVIEW" }
            val conflict = candidates.single { it.externalId == "WX-CONFLICT" }
            withContext(Dispatchers.Main) {
                viewModel.toggle(unknown.id, true)
                viewModel.toggle(conflict.id, true)
                viewModel.commit()
            }
            withTimeout(10_000) { viewModel.state.first { it.commitMessage.isNotEmpty() } }
            assertEquals("WX-KNOWN", repository.getBills(filter).single().transaction.externalId)

            withContext(Dispatchers.Main) { viewModel.parse(context, uri) }
            val reparsed = withTimeout(10_000) { viewModel.state.first { it.result != null } }
            val pending = requireNotNull(reparsed.result).candidates.single { it.externalId == "WX-REVIEW" }
            val incomeCategory = repository.categories.first().first { it.type == TransactionType.INCOME }
            withContext(Dispatchers.Main) {
                viewModel.updateCandidate(pending.copy(type = TransactionType.INCOME, categoryId = incomeCategory.id, needsReview = false))
                viewModel.commit()
            }
            withTimeout(10_000) { viewModel.state.first { it.commitMessage.isNotEmpty() } }
            val records = repository.getBills(filter).map { it.transaction }
            assertEquals(2, records.size)
            assertEquals(TransactionType.INCOME, records.single { it.externalId == "WX-REVIEW" }.type)
        } finally {
            withContext(Dispatchers.Main) { viewModel.reset() }
            database.close()
            file.delete()
        }
    }
}
