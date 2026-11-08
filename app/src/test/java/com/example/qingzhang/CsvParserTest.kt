package com.example.qingzhang

import com.example.qingzhang.importer.CsvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvParserTest {
    @Test
    fun parsesQuotesCommasEscapedQuotesAndMultilineCells() {
        val rows = CsvParser.parse("a,\"b,c\",\"d\"\"e\"\r\n1,\"line1\nline2\",3")
        assertEquals(listOf("a", "b,c", "d\"e"), rows[0])
        assertEquals(listOf("1", "line1\nline2", "3"), rows[1])
    }

    @Test
    fun decodesGbkAndUtf8Bom() {
        val gbk = CsvParser.decode("交易时间,金额".toByteArray(charset("GBK")))
        assertTrue(gbk.contains("交易时间"))
        val utf8 = "金额".toByteArray(Charsets.UTF_8)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8
        assertEquals("金额", CsvParser.decode(bom))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnclosedQuotes() {
        CsvParser.parse("a,\"broken")
    }
}
