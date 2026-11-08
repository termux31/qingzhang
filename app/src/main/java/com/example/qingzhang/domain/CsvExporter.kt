package com.example.qingzhang.domain

import com.example.qingzhang.data.TransactionDetail
import java.time.format.DateTimeFormatter

object CsvExporter {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun export(records: List<TransactionDetail>): ByteArray {
        require(records.isNotEmpty()) { "当前筛选条件下没有可导出的账目" }
        val content = buildString {
            appendRow(listOf("账目ID", "日期", "类型", "分类", "金额(元)", "备注", "商户/来源", "导入来源", "平台交易单号"))
            records.forEach { detail ->
                val transaction = detail.transaction
                appendRow(
                    listOf(
                        transaction.id.toString(),
                        transaction.occurredAt.toLocalDateTime().format(dateTimeFormatter),
                        transaction.type.label,
                        detail.categoryName,
                        decimalAmount(transaction.amountCents),
                        transaction.note,
                        transaction.merchant,
                        transaction.source.label,
                        transaction.externalId.orEmpty(),
                    ),
                )
            }
        }
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.toByteArray(Charsets.UTF_8)
    }

    private fun StringBuilder.appendRow(values: List<String>) {
        append(values.joinToString(",") { value -> "\"${safeText(value).replace("\"", "\"\"")}\"" })
        append("\r\n")
    }

    private fun safeText(value: String): String {
        val first = value.firstOrNull()
        return if (first != null && first in charArrayOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
    }

    private fun decimalAmount(cents: Long): String = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}
