package com.example.qingzhang

import com.example.qingzhang.data.CategoryEntity
import com.example.qingzhang.data.DefaultCategories
import com.example.qingzhang.data.ImportSource
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.toLocalDateTime
import com.example.qingzhang.importer.StatementImporter
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementImporterTest {
    private val categories = DefaultCategories.values.mapIndexed { index, category ->
        category.copy(id = (index + 1).toLong())
    }

    @Test
    fun parsesWechatRowsAndDetectsWithinFileDuplicates() {
        val csv = """微信支付账单明细
交易时间,交易对方,商品,收/支,金额(元),交易单号
2026-10-01 12:30:00,小餐馆,午餐,支出,32.50,W-1
2026-10-01 12:30:00,小餐馆,午餐,支出,32.50,W-1
2026-10-02 09:00:00,公司,十月工资,收入,8000.00,W-2
"""
        val result = StatementImporter.parse(csv.toByteArray(), "wechat.csv", null, categories)
        assertEquals(ImportSource.WECHAT, result.source)
        assertEquals(3, result.candidates.size)
        assertEquals(3250L, result.candidates[0].amountCents)
        assertEquals("餐饮", result.candidates[0].suggestedCategoryName)
        assertFalse(result.candidates[0].duplicate)
        assertTrue(result.candidates[1].duplicate)
        assertEquals(TransactionType.INCOME, result.candidates[2].type)
        assertEquals("工资", result.candidates[2].suggestedCategoryName)
    }

    @Test
    fun distinguishesMerchantOrdersWithoutUsingTheDeviceTimeZone() {
        val csv = """微信支付账单
交易时间,交易对方,商品,收/支,金额,交易单号,商户单号
2026-11-08 12:00:00,商户,商品,支出,25,,M-1
2026-11-08 12:00:00,商户,商品,支出,25,,M-2
""".toByteArray()
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val original = StatementImporter.parse(csv, "w.csv", null, categories).candidates
            assertEquals(2, original.map { it.importKey }.toSet().size)
            assertTrue(original.all { it.externalId == null && !it.duplicate && it.selected })

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val reimport = StatementImporter.parse(csv, "renamed.csv", null, categories).candidates
            assertTrue(original.first().occurredAt != reimport.first().occurredAt)
            assertEquals(original.map { it.importKey }, reimport.map { it.importKey })
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun keepsIndistinguishableRowsSelectableWithoutPreselectingBoth() {
        val csv = """微信支付账单
交易时间,交易对方,商品,收/支,金额,交易单号
2026-11-08 12:00:00,商户,商品,支出,25,/
2026-11-08 12:00:00,商户,商品,支出,25,/
"""
        val result = StatementImporter.parse(csv.toByteArray(), "w.csv", null, categories)
        assertEquals(2, result.candidates.map { it.importKey }.toSet().size)
        assertTrue(result.candidates.none { it.duplicate || it.needsReview })
        assertEquals(listOf(true, false), result.candidates.map { it.selected })
        assertTrue(result.issues.single().contains("勾选"))
    }

    @Test
    fun parsesAlipayGbkAndReportsInvalidRows() {
        val csv = """支付宝交易记录明细
交易时间,交易对方,商品说明,收/支,金额,交易订单号
2026/10/03 08:20:00,微信支付,地铁出行,支出,4.00,A-1
bad-date,商店,商品,支出,10.00,A-2
"""
        val result = StatementImporter.parse(csv.toByteArray(charset("GBK")), "alipay.csv", null, categories)
        assertEquals(ImportSource.ALIPAY, result.source)
        assertEquals(1, result.candidates.size)
        assertEquals("交通", result.candidates[0].suggestedCategoryName)
        assertEquals(1, result.skippedCount)
        assertTrue(result.issues.single().contains("时间"))
    }

    @Test
    fun marksUnknownDirectionForReview() {
        val csv = """微信支付账单
交易时间,交易对方,商品,收/支,金额(元)
2026-10-01 12:30:00,商户,转账,/,20.00
"""
        val candidate = StatementImporter.parse(csv.toByteArray(), "w.csv", null, categories).candidates.single()
        assertTrue(candidate.needsReview)
        assertFalse(candidate.selected)
        assertEquals(TransactionType.EXPENSE, candidate.type)
    }

    @Test
    fun parsesCompactCsvDatesAndRejectsInvalidDatesAndExcelSerials() {
        val csv = """微信支付账单
交易时间,收/支,金额
2026-02-30 12:00:00,支出,20.00
2026/2/29,支出,20.00
2026年4月31日 8:00,支出,20.00
2026-10-01 24:00,支出,20.00
1000000000000,支出,20.00
2024-02-29 12:00:00,支出,20.00
2024/2/29,收入,20.00
20261108,支出,20.00
20260230,支出,20.00
2026,支出,20.00
46000.5,支出,20.00
"""
        val result = StatementImporter.parse(csv.toByteArray(), "w.csv", null, categories)
        assertEquals(8, result.skippedCount)
        assertEquals(((3..7) + (11..13)).map { "第 $it 行：时间无法识别" }, result.issues)
        assertEquals(listOf(8, 9, 10), result.candidates.map { it.originalRow })
        assertEquals(LocalDateTime.of(2026, 11, 8, 12, 0), result.candidates.last().occurredAt.toLocalDateTime())
    }

    @Test
    fun requiresReviewForSplitAmountsThatConflictWithEachOtherOrTheDirection() {
        val csv = """微信支付账单
交易时间,收/支,收入金额,支出金额,交易单号
2026-11-08 12:00:00,支出,1,9,W-1
2026-11-08 12:01:00,支出,1,,W-2
2026-11-08 12:02:00,收入,7,0,W-3
2026-11-08 12:03:00,,0,8,W-4
2026-11-08 12:04:00,,1,9,W-5
"""
        val result = StatementImporter.parse(csv.toByteArray(), "w.csv", null, categories)
        val (both, opposite, income, inferred, ambiguous) = result.candidates
        assertEquals(900L, both.amountCents)
        assertEquals(TransactionType.EXPENSE, both.type)
        assertEquals(TransactionType.EXPENSE, opposite.type)
        assertEquals(700L, income.amountCents)
        assertEquals(800L, inferred.amountCents)
        assertEquals(TransactionType.EXPENSE, inferred.type)
        assertEquals(0L, ambiguous.amountCents)
        assertEquals(listOf(true, true, false, false, true), result.candidates.map { it.needsReview })
        assertEquals(listOf(false, false, true, true, false), result.candidates.map { it.selected })
    }

    @Test
    fun requiresReviewWhenTotalAndSplitAmountsDisagree() {
        val csv = """支付宝交易记录
交易时间,收支类型,金额,收入金额,支出金额,交易订单号
2026-11-08,支出,10,0,9,A-1
2026-11-08,支出,9,0,9,A-2
"""
        val (conflict, consistent) = StatementImporter.parse(csv.toByteArray(), "a.csv", null, categories).candidates
        assertTrue(conflict.needsReview)
        assertFalse(conflict.selected)
        assertTrue(consistent.selected)
    }

    @Test
    fun skipsFailedPaymentsAndLeavesUnclearStatusesForReview() {
        val csv = """微信支付账单
交易时间,收/支,金额,当前状态,交易单号
2026-11-08 12:00:00,支出,12.50,支付失败,W-1
2026-11-08 12:01:00,支出,12.50,支付成功,W-2
2026-11-08 12:02:00,支出,12.50,退款失败,W-3
2026-11-08 12:03:00,支出,12.50,,W-4
"""
        for (header in listOf("当前状态", "交易状态")) {
            val result = StatementImporter.parse(csv.replace("当前状态", header).toByteArray(), "w.csv", null, categories)
            assertEquals(1, result.skippedCount)
            assertEquals(listOf("W-2", "W-3", "W-4"), result.candidates.map { it.externalId })
            assertEquals(listOf(true, false, true), result.candidates.map { it.selected })
            assertTrue(result.candidates[1].needsReview)
            assertTrue(result.issues.any { "支付失败" in it })
            assertTrue(result.issues.any { "退款失败" in it })
        }
    }

    @Test
    fun doesNotInferPlatformFromTransactionContents() {
        val csv = "交易时间,交易对方,收/支,金额\n2026-10-01 12:30:00,微信支付,支出,20.00"
        val error = runCatching {
            StatementImporter.parse(csv.toByteArray(), "w.csv", null, categories)
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("无法识别账单来源"))
        val manual = StatementImporter.parse(csv.toByteArray(), "w.csv", ImportSource.ALIPAY, categories)
        assertEquals(ImportSource.ALIPAY, manual.candidates.single().source)
    }

    @Test
    fun rejectsManualSourceThatConflictsWithTheStatement() {
        val csv = "微信支付账单\n交易时间,收/支,金额,交易单号\n2026-10-01,支出,9,WX123".toByteArray()
        val auto = StatementImporter.parse(csv, "bill.csv", null, categories).candidates.single()
        val manual = StatementImporter.parse(csv, "bill.csv", ImportSource.WECHAT, categories).candidates.single()
        assertEquals(auto.importKey, manual.importKey)
        val error = runCatching { StatementImporter.parse(csv, "bill.csv", ImportSource.ALIPAY, categories) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && error.message.orEmpty().contains("微信"))
    }

    @Test
    fun boundsRowsAndSparseCellsDuringParsing() {
        val csv = "微信支付账单\n交易时间,收/支,金额\n" + "2026-10-01,支出,20\n".repeat(5031)
        val csvError = runCatching {
            StatementImporter.parse(csv.toByteArray(), "wechat.csv", null, categories)
        }.exceptionOrNull()
        assertEquals("单次最多解析 5000 条账目", csvError?.message)

        val sheet = "<worksheet><sheetData>" + "<row><c r=\"ZZ1\"><v>1</v></c></row>".repeat(400) +
            "</sheetData></worksheet>"
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray())
        }
        val xlsxError = runCatching {
            StatementImporter.parse(output.toByteArray(), "wechat.xlsx", null, categories)
        }.exceptionOrNull()
        assertEquals("账单表格单元格过多，请拆分文件后导入", xlsxError?.message)
    }

    @Test
    fun countsIgnoredXlsxEntriesTowardTheInflatedLimit() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/media/ignored.bin"))
            val chunk = ByteArray(1024 * 1024)
            repeat(31) { zip.write(chunk) }
        }
        val error = runCatching {
            StatementImporter.parse(output.toByteArray(), "wechat.xlsx", null, categories)
        }.exceptionOrNull()
        assertEquals("XLSX 解压后内容超过 30 MiB", error?.message)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingHeaders() {
        StatementImporter.parse("a,b\n1,2".toByteArray(), "bad.csv", ImportSource.WECHAT, categories)
    }

    @Test
    fun readsSimpleXlsxWorksheet() {
        val shared = """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>微信支付账单</t></si><si><t>交易时间</t></si><si><t>交易对方</t></si><si><t>商品</t></si><si><t>收/支</t></si><si><t>金额(元)</t></si><si><t>2026-10-01 12:30:00</t></si><si><t>咖啡店</t></si><si><t>咖啡</t></si><si><t>支出</t></si></sst>"""
        val sheet = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row><row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2" t="s"><v>2</v></c><c r="C2" t="s"><v>3</v></c><c r="D2" t="s"><v>4</v></c><c r="E2" t="s"><v>5</v></c></row><row r="3"><c r="A3" t="s"><v>6</v></c><c r="B3" t="s"><v>7</v></c><c r="C3" t="s"><v>8</v></c><c r="D3" t="s"><v>9</v></c><c r="E3"><v>18.80</v></c></row></sheetData></worksheet>"""
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(shared.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray())
            zip.closeEntry()
        }
        val result = StatementImporter.parse(output.toByteArray(), "wechat.xlsx", null, categories)
        assertEquals(1880L, result.candidates.single().amountCents)
        assertEquals("餐饮", result.candidates.single().suggestedCategoryName)
    }

    @Test
    fun readsSparseXlsxColumnsAndOnlyParsesSerialsFromNumericCells() {
        val sheet = """<worksheet><sheetData>
<row><c r="A1" t="inlineStr"><is><t>微信支付账单</t></is></c></row>
<row><c r="A2" t="inlineStr"><is><t>交易时间</t></is></c><c r="B2" t="inlineStr"><is><t>收/支</t></is></c><c r="D2" t="inlineStr"><is><t>金额</t></is></c></row>
<row><c r="A3"><v>46000.5</v></c><c r="B3" t="inlineStr"><is><t>支出</t></is></c><c r="D3"><v>25</v></c><c r="AAA3"><v>999</v></c></row>
<row><c r="A4" t="inlineStr"><is><t>46000.5</t></is></c><c r="B4" t="inlineStr"><is><t>支出</t></is></c><c r="D4"><v>25</v></c></row>
<row><c r="A5" t="n"><v>3000000</v></c><c r="B5" t="inlineStr"><is><t>支出</t></is></c><c r="D5"><v>25</v></c></row>
</sheetData></worksheet>"""
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray())
        }
        val result = StatementImporter.parse(output.toByteArray(), "wechat.xlsx", null, categories)
        val candidate = result.candidates.single()
        assertEquals(2, result.skippedCount)
        assertEquals(2500L, candidate.amountCents)
        assertEquals(LocalDateTime.of(2025, 12, 9, 12, 0), candidate.occurredAt.toLocalDateTime())
        assertTrue(candidate.selected)
    }

    @Test
    fun reads1904WorkbookSerialDatesIncludingZero() {
        val sheet = """<worksheet><sheetData>
<row><c r="A1" t="inlineStr"><is><t>微信支付账单</t></is></c></row>
<row><c r="A2" t="inlineStr"><is><t>交易时间</t></is></c><c r="B2" t="inlineStr"><is><t>收/支</t></is></c><c r="C2" t="inlineStr"><is><t>金额</t></is></c></row>
<row><c r="A3"><v>39267.5</v></c><c r="B3" t="inlineStr"><is><t>支出</t></is></c><c r="C3"><v>9</v></c></row>
<row><c r="A4"><v>0</v></c><c r="B4" t="inlineStr"><is><t>支出</t></is></c><c r="C4"><v>9</v></c></row>
</sheetData></worksheet>"""
        for (flag in listOf("1", "true")) {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write(sheet.toByteArray())
                zip.putNextEntry(ZipEntry("xl/workbook.xml"))
                zip.write("""<workbook><workbookPr date1904="$flag"/></workbook>""".toByteArray())
            }
            val result = StatementImporter.parse(output.toByteArray(), "wechat.xlsx", null, categories)
            assertEquals(emptyList<String>(), result.issues)
            assertEquals(
                listOf(LocalDateTime.of(2011, 7, 5, 12, 0), LocalDateTime.of(1904, 1, 1, 0, 0)),
                result.candidates.map { it.occurredAt.toLocalDateTime() },
            )
        }
    }
}
