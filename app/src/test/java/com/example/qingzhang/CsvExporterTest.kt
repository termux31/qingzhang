package com.example.qingzhang

import com.example.qingzhang.data.ImportSource
import com.example.qingzhang.data.TransactionDetail
import com.example.qingzhang.data.TransactionEntity
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.CsvExporter
import com.example.qingzhang.domain.toMillis
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExporterTest {
    @Test
    fun exportsBomExactAmountEscapedTextAndFormulaProtection() {
        val detail = TransactionDetail(
            TransactionEntity(
                id = 42,
                amountCents = 1234,
                type = TransactionType.EXPENSE,
                categoryId = 1,
                occurredAt = LocalDateTime.of(2026, 10, 1, 12, 30).toMillis(),
                note = "=SUM(A1:A2), \"午餐\"",
                merchant = "商户",
                source = ImportSource.WECHAT,
                externalId = "W-1",
            ),
            "餐饮",
            0xFFE67C52,
        )
        val bytes = CsvExporter.export(listOf(detail))
        assertEquals(0xEF.toByte(), bytes[0])
        val text = String(bytes.copyOfRange(3, bytes.size), Charsets.UTF_8)
        assertTrue(text.contains("\"12.34\""))
        assertTrue(text.contains("\"'=SUM(A1:A2), \"\"午餐\"\"\""))
        assertTrue(text.contains("\"42\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesEmptyExport() {
        CsvExporter.export(emptyList())
    }
}
