package com.example.qingzhang.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal data class XlsxRow(val values: List<String>, val numericColumns: Set<Int>)
internal data class XlsxSheet(val rows: List<XlsxRow>, val uses1904Dates: Boolean)

object XlsxReader {
    private const val MAX_INFLATED_BYTES = 30 * 1024 * 1024
    private const val MAX_COLUMNS = 16_384
    private val cellReference = Regex("([A-Za-z]{1,3})[1-9][0-9]*")

    internal fun readFirstSheet(bytes: ByteArray): XlsxSheet {
        var sharedStringsXml: ByteArray? = null
        var sheetXml: ByteArray? = null
        var workbookXml: ByteArray? = null
        var entryCount = 0
        var inflatedBytes = 0
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount++
                require(entryCount <= 200) { "XLSX 文件结构异常" }
                val isSharedStrings = entry.name == "xl/sharedStrings.xml"
                val isFirstSheet = sheetXml == null &&
                    entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml")
                val isWorkbook = entry.name == "xl/workbook.xml"
                val output = if (isSharedStrings || isFirstSheet || isWorkbook) ByteArrayOutputStream() else null
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    inflatedBytes += count
                    require(inflatedBytes <= MAX_INFLATED_BYTES) { "XLSX 解压后内容超过 30 MiB" }
                    output?.write(buffer, 0, count)
                }
                if (isSharedStrings) sharedStringsXml = output?.toByteArray()
                if (isFirstSheet) sheetXml = output?.toByteArray()
                if (isWorkbook) workbookXml = output?.toByteArray()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val worksheet = sheetXml ?: throw IllegalArgumentException("XLSX 中未找到第一个工作表")
        val sharedStrings = sharedStringsXml?.let(::parseSharedStrings).orEmpty()
        return XlsxSheet(parseWorksheet(worksheet, sharedStrings), workbookXml?.let(::uses1904Dates) == true)
    }

    private fun uses1904Dates(xml: ByteArray): Boolean {
        val parser = parser(xml)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "workbookPr") {
                return when (parser.getAttributeValue(null, "date1904")) {
                    null, "0", "false" -> false
                    "1", "true" -> true
                    else -> throw IllegalArgumentException("XLSX 日期系统标记无效")
                }
            }
            parser.next()
        }
        return false
    }

    private fun parser(xml: ByteArray): XmlPullParser = XmlPullParserFactory.newInstance()
        .newPullParser()
        .apply {
            runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            setInput(xml.inputStream(), "UTF-8")
        }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val parser = parser(xml)
        val values = mutableListOf<String>()
        var current: StringBuilder? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    "t" -> current?.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    require(values.size < MAX_PARSED_CELLS) { CELL_LIMIT_MESSAGE }
                    values.add(current?.toString().orEmpty())
                    current = null
                }
            }
            if (parser.eventType != XmlPullParser.END_DOCUMENT) parser.next()
        }
        return values
    }

    private fun parseWorksheet(xml: ByteArray, sharedStrings: List<String>): List<XlsxRow> {
        val parser = parser(xml)
        val rows = mutableListOf<XlsxRow>()
        var cells = mutableMapOf<Int, String>()
        var numericColumns = mutableSetOf<Int>()
        var nextColumn = 0
        var reference = ""
        var cellType = ""
        var value: StringBuilder? = null
        var parsedCells = 0
        var resolvedChars = 0

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        cells = mutableMapOf()
                        numericColumns = mutableSetOf()
                        nextColumn = 0
                    }
                    "c" -> {
                        require(cells.size < MAX_PARSED_CELLS) { CELL_LIMIT_MESSAGE }
                        reference = parser.getAttributeValue(null, "r").orEmpty()
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        value = StringBuilder()
                    }
                    "v", "t" -> if (value != null) value?.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        val column = if (reference.isEmpty()) nextColumn else columnIndex(reference)
                        require(column in 0 until MAX_COLUMNS && column !in cells) { "XLSX 单元格引用无效或重复" }
                        nextColumn = column + 1
                        val raw = value.toString()
                        val text = if (cellType == "s") {
                            raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
                        } else {
                            raw
                        }
                        resolvedChars += text.length
                        require(resolvedChars <= MAX_INFLATED_BYTES) { "XLSX 表格文本过多，请拆分文件后导入" }
                        cells[column] = text
                        if (cellType.isEmpty() || cellType == "n") numericColumns.add(column)
                        value = null
                    }
                    "row" -> {
                        if (cells.isNotEmpty()) {
                            require(rows.size < MAX_FILE_ROWS) { ROW_LIMIT_MESSAGE }
                            val maxColumn = cells.keys.max()
                            parsedCells += maxColumn + 1
                            require(parsedCells <= MAX_PARSED_CELLS) { CELL_LIMIT_MESSAGE }
                            rows.add(XlsxRow((0..maxColumn).map { cells[it].orEmpty() }, numericColumns))
                        }
                    }
                }
            }
            if (parser.eventType != XmlPullParser.END_DOCUMENT) parser.next()
        }
        return rows
    }

    private fun columnIndex(reference: String): Int {
        val letters = cellReference.matchEntire(reference)?.groupValues?.get(1)?.uppercase() ?: return -1
        return letters.fold(0) { result, char -> result * 26 + (char - 'A' + 1) } - 1
    }
}
