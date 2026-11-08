package com.example.qingzhang.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object CsvParser {
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        else -> decodeUtf8OrGbk(bytes)
    }

    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        var parsedCells = 0

        fun finishField() {
            require(++parsedCells <= MAX_PARSED_CELLS) { CELL_LIMIT_MESSAGE }
            row.add(field.toString().trim())
            field.clear()
        }

        fun finishRow() {
            finishField()
            if (row.any { it.isNotBlank() }) {
                require(rows.size < MAX_FILE_ROWS) { ROW_LIMIT_MESSAGE }
                rows.add(row)
            }
            row = mutableListOf()
        }

        while (index < text.length) {
            val char = text[index]
            if (inQuotes) {
                if (char == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(char)
                }
            } else {
                when (char) {
                    '"' -> if (field.isBlank()) inQuotes = true else field.append(char)
                    ',' -> finishField()
                    '\r' -> {
                        if (index + 1 < text.length && text[index + 1] == '\n') index++
                        finishRow()
                    }
                    '\n' -> finishRow()
                    else -> field.append(char)
                }
            }
            index++
        }
        if (inQuotes) throw IllegalArgumentException("CSV 存在未闭合的引号")
        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    private fun decodeUtf8OrGbk(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("GBK"))
        }
    }
}
