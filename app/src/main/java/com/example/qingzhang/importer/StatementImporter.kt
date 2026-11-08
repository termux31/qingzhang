package com.example.qingzhang.importer

import com.example.qingzhang.data.CategoryEntity
import com.example.qingzhang.data.ImportSource
import com.example.qingzhang.data.TransactionEntity
import com.example.qingzhang.data.TransactionType
import com.example.qingzhang.domain.parseAmountCents
import com.example.qingzhang.domain.toMillis
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private const val MAX_ROWS = 5_000
internal const val MAX_FILE_ROWS = MAX_ROWS + 30
internal const val MAX_PARSED_CELLS = 250_000
internal const val ROW_LIMIT_MESSAGE = "单次最多解析 5000 条账目"
internal const val CELL_LIMIT_MESSAGE = "账单表格单元格过多，请拆分文件后导入"

data class ImportCandidate(
    val id: Int,
    val source: ImportSource,
    val amountCents: Long,
    val type: TransactionType,
    val occurredAt: Long,
    val categoryId: Long?,
    val suggestedCategoryName: String,
    val merchant: String,
    val note: String,
    val externalId: String?,
    val importKey: String,
    val originalRow: Int,
    val selected: Boolean = true,
    val duplicate: Boolean = false,
    val needsReview: Boolean = false,
    val legacyMatches: List<LegacyImportMatch> = emptyList(),
    val merchantOrderId: String? = null,
)

data class LegacyImportMatch(
    val key: String,
    val fields: List<String>,
    val sameSource: Boolean,
) {
    fun matches(record: TransactionEntity): Boolean {
        val storedFields = fingerprintFields(
            record.source, record.occurredAt.toString(), record.amountCents, record.type, record.merchant, record.note,
        )
        if (storedFields == fields) return true
        // An edited legacy row no longer tells us which original fields produced this key.
        return sameSource && legacyFieldKey(storedFields, key.startsWith("v2:")) != key
    }
}

data class ImportParseResult(
    val source: ImportSource,
    val candidates: List<ImportCandidate>,
    val issues: List<String>,
    val skippedCount: Int,
)

object StatementImporter {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    private const val MAX_ISSUES = 200

    private val timeAliases = listOf("交易时间", "时间", "交易创建时间", "付款时间", "交易日期")
    private val amountAliases = listOf("金额", "金额(元)", "交易金额", "交易金额(元)")
    private val directionAliases = listOf("收/支", "收支", "资金流向", "收支类型")
    private val merchantAliases = listOf("交易对方", "商户名称", "对方", "商户")
    private val noteAliases = listOf("商品", "商品名称", "商品说明", "交易备注", "说明", "交易说明", "备注")
    private val orderAliases = listOf("交易单号", "交易订单号", "订单号")
    private val merchantOrderAliases = listOf("商户单号", "商户订单号", "商家订单号")
    private val missingOrderIds = setOf("/", "-", "--")
    private val categoryAliases = listOf("交易分类", "消费类型", "账单分类")
    private val incomeAmountAliases = listOf("收入金额", "收入金额(元)", "收入")
    private val expenseAmountAliases = listOf("支出金额", "支出金额(元)", "支出")
    private val statusAliases = listOf("当前状态", "交易状态")
    private val unpaidStatuses = setOf("支付失败", "付款失败", "交易失败", "未支付", "未付款", "等待付款")
    private val settledStatuses = setOf(
        "交易成功", "支付成功", "付款成功", "收款成功", "转账成功", "退款成功",
        "已付款", "已支付", "已收款", "已收钱", "对方已收钱", "已存入零钱", "已到账", "已完成",
    )

    private val dateTimeFormatters = listOf(
        "uuuu-MM-dd HH:mm:ss",
        "uuuu-MM-dd HH:mm",
        "uuuu/M/d H:mm:ss",
        "uuuu/M/d H:mm",
        "uuuu年M月d日 H:mm:ss",
        "uuuu年M月d日 H:mm",
    ).map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }
    private val dateFormatters = listOf(
        "uuuu-MM-dd",
        "uuuu/M/d",
        "uuuu年M月d日",
        "uuuuMMdd",
    ).map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }

    fun parse(
        bytes: ByteArray,
        fileName: String,
        requestedSource: ImportSource?,
        categories: List<CategoryEntity>,
    ): ImportParseResult {
        require(bytes.isNotEmpty()) { "文件为空，请选择有效的账单文件" }
        require(bytes.size <= MAX_FILE_BYTES) { "账单文件不能超过 10 MiB" }
        require(requestedSource != ImportSource.MANUAL) { "账单来源只能是微信或支付宝" }

        val xlsx = if (
            fileName.endsWith(".xlsx", ignoreCase = true) ||
            (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
        ) {
            XlsxReader.readFirstSheet(bytes)
        } else null
        val xlsxRows = xlsx?.rows
        val rows = xlsxRows?.map { it.values } ?: CsvParser.parse(CsvParser.decode(bytes))
        require(rows.isNotEmpty()) { "未读取到账单内容" }

        val headerIndex = rows.indexOfFirst { row -> row.hasTimeAndAmountHeader() }
        require(headerIndex >= 0) { "未找到包含时间和金额的表头，请确认文件为平台导出的原始账单" }
        val detectedSource = detectSource(rows.take(headerIndex))
        require(requestedSource == null || detectedSource == null || requestedSource == detectedSource) {
            "文件是${detectedSource?.label}账单，请选择自动识别或${detectedSource?.label}"
        }
        val source = detectedSource ?: requestedSource
            ?: throw IllegalArgumentException("无法识别账单来源，请手动选择微信或支付宝")
        val oldSource = if (source == detectedSource) legacySource(rows)?.takeIf { it != source } else null

        val header = rows[headerIndex].map(::normalizeHeader)
        val timeColumn = header.findAlias(timeAliases)
        val amountColumn = header.findAlias(amountAliases)
        val directionColumn = header.findAlias(directionAliases)
        val merchantColumn = header.findAlias(merchantAliases)
        val noteColumn = header.findAlias(noteAliases)
        val orderColumn = header.findAlias(orderAliases)
        val merchantOrderColumn = header.findAlias(merchantOrderAliases)
        val categoryColumn = header.findAlias(categoryAliases)
        val incomeAmountColumn = header.findAlias(incomeAmountAliases)
        val expenseAmountColumn = header.findAlias(expenseAmountAliases)
        val statusColumn = header.findAlias(statusAliases)

        val issues = mutableListOf<String>()
        val candidates = mutableListOf<ImportCandidate>()
        val occurrences = mutableMapOf<String, Int>()
        var skippedCount = 0

        fun report(rowNumber: Int, message: String) {
            if (issues.size < MAX_ISSUES) issues.add("第 $rowNumber 行：$message")
        }

        rows.drop(headerIndex + 1).forEachIndexed { offset, row ->
            val rowNumber = headerIndex + offset + 2
            if (row.all { it.isBlank() }) return@forEachIndexed
            if (candidates.size >= MAX_ROWS) {
                skippedCount++
                return@forEachIndexed
            }
            try {
                val status = row.cell(statusColumn)
                if (status in unpaidStatuses) {
                    skippedCount++
                    report(rowNumber, "交易状态为「$status」，已跳过")
                    return@forEachIndexed
                }
                val numericDate = xlsxRows?.get(rowNumber - 1)?.numericColumns?.contains(timeColumn) == true
                val dateTime = parseDateTime(row.cell(timeColumn), numericDate, xlsx?.uses1904Dates == true)
                    ?: throw IllegalArgumentException("时间无法识别")
                val directionText = row.cell(directionColumn)
                if (directionText.contains("不计收支") || directionText.contains("不计入")) {
                    skippedCount++
                    return@forEachIndexed
                }

                val direction = parseDirection(directionText)
                val income = parseStatementAmount(row.cell(incomeAmountColumn))
                val expense = parseStatementAmount(row.cell(expenseAmountColumn))
                val bothAmounts = income != null && expense != null
                val splitDirection = when {
                    bothAmounts -> null
                    income != null -> TransactionType.INCOME
                    expense != null -> TransactionType.EXPENSE
                    else -> null
                }
                val splitAmount = when {
                    direction == TransactionType.INCOME && income != null -> income
                    direction == TransactionType.EXPENSE && expense != null -> expense
                    bothAmounts -> 0L
                    else -> income ?: expense
                }
                val totalAmount = parseStatementAmount(row.cell(amountColumn))
                val amountCents = (if (amountColumn >= 0) totalAmount else splitAmount)
                    ?: throw IllegalArgumentException("金额缺失或格式不正确")
                val type = direction ?: splitDirection
                val amountConflict = bothAmounts ||
                    (direction != null && splitDirection != null && direction != splitDirection) ||
                    (totalAmount != null && splitAmount != null && totalAmount != splitAmount)
                val statusNeedsReview = status.isNotBlank() && status != "/" && status !in settledStatuses
                val needsReview = amountConflict || statusNeedsReview || type == null
                if (amountConflict) report(rowNumber, "收支方向或金额列冲突，请核对原始账单")
                else if (type == null) report(rowNumber, "收支方向未识别，请先选择收入或支出")
                if (statusNeedsReview) report(rowNumber, "请核对交易状态「${status.take(40)}」")
                val resolvedType = type ?: TransactionType.EXPENSE

                val merchant = row.cell(merchantColumn).cleanText().take(100)
                val sourceCategory = row.cell(categoryColumn).cleanText()
                val statementNote = row.cell(noteColumn).cleanText().ifBlank { sourceCategory }
                val note = statementNote.ifBlank { "${source.label}导入" }.take(200)
                val categoryName = suggestCategory("$merchant $note $sourceCategory", resolvedType)
                val categoryId = categories.firstOrNull {
                    it.type == resolvedType && it.name == categoryName
                }?.id ?: categories.firstOrNull {
                    it.type == resolvedType && it.name == if (resolvedType == TransactionType.EXPENSE) "其他支出" else "其他收入"
                }?.id
                val occurredAt = dateTime.toMillis()
                val oldOrderId = row.cell(orderColumn).orderId()
                val externalId = oldOrderId?.takeUnless { it in missingOrderIds }
                val merchantOrderId = row.cell(merchantOrderColumn).orderId()?.takeUnless { it in missingOrderIds }
                val fields = fingerprintFields(source, occurredAt.toString(), amountCents, resolvedType, merchant, note)
                var repeatedWithoutId = false
                val importKey = if (externalId != null) legacyFingerprint(source, externalId, fields) else {
                    val stableFields = fingerprintFields(source, dateTime.toString(), amountCents, resolvedType, merchant, note)
                    val digest = framedFingerprint(stableFields + merchantOrderId.orEmpty())
                    // shortcut: indistinguishable rows across separate exports share occurrence numbers until the source supplies IDs.
                    val occurrence = (occurrences[digest] ?: 0) + 1
                    occurrences[digest] = occurrence
                    repeatedWithoutId = occurrence > 1
                    "v3:$digest:$occurrence"
                }
                if (repeatedWithoutId) report(rowNumber, "与本文件另一笔账目无法区分，确认是另一笔后可勾选")
                val legacyMatches = buildList {
                    if (externalId == null) {
                        // shortcut: old keys contain a device-local epoch; reuse the original device zone for legacy matching.
                        if (fields.any { '|' in it }) add(LegacyImportMatch(legacyFieldKey(fields, true), fields, sameSource = true))
                        add(LegacyImportMatch(legacyFieldKey(fields, false), fields, sameSource = true))
                        if (oldOrderId != null) {
                            add(LegacyImportMatch(legacyFingerprint(source, oldOrderId, fields), fields, sameSource = false))
                        }
                    }
                    if (oldSource != null) {
                        val oldNote = statementNote.ifBlank { "${oldSource.label}导入" }.take(200)
                        val oldFields = fingerprintFields(oldSource, occurredAt.toString(), amountCents, resolvedType, merchant, oldNote)
                        add(LegacyImportMatch(legacyFingerprint(oldSource, oldOrderId, oldFields), oldFields, sameSource = false))
                    }
                }
                candidates.add(
                    ImportCandidate(
                        id = rowNumber,
                        source = source,
                        amountCents = amountCents,
                        type = resolvedType,
                        occurredAt = occurredAt,
                        categoryId = categoryId,
                        suggestedCategoryName = categoryName,
                        merchant = merchant,
                        note = note,
                        externalId = externalId,
                        importKey = importKey,
                        originalRow = rowNumber,
                        selected = !needsReview && !repeatedWithoutId,
                        needsReview = needsReview,
                        legacyMatches = legacyMatches,
                        merchantOrderId = merchantOrderId,
                    ),
                )
            } catch (_: DateTimeException) {
                skippedCount++
                report(rowNumber, "时间无法识别")
            } catch (error: IllegalArgumentException) {
                skippedCount++
                report(rowNumber, error.message ?: "内容无法识别")
            } catch (error: ArithmeticException) {
                skippedCount++
                report(rowNumber, "数值超出可处理范围")
            }
        }
        if (issues.size == MAX_ISSUES) issues.add("其余错误已省略，请修正源文件后重新导入")

        val seen = mutableSetOf<String>()
        val deduplicated = candidates.map { candidate ->
            if (seen.add(candidate.importKey)) candidate else candidate.copy(selected = false, duplicate = true)
        }
        return ImportParseResult(source, deduplicated, issues, skippedCount)
    }

    private fun List<String>.hasTimeAndAmountHeader(): Boolean {
        val normalized = map(::normalizeHeader)
        return normalized.findAlias(timeAliases) >= 0 &&
            (normalized.findAlias(amountAliases) >= 0 ||
                normalized.findAlias(incomeAmountAliases) >= 0 ||
                normalized.findAlias(expenseAmountAliases) >= 0)
    }

    private fun detectSource(preamble: List<List<String>>): ImportSource? =
        preamble.asSequence().flatten().mapNotNull { cell ->
            val text = cell.trim()
            when {
                text == "微信" || text.contains("微信支付账单") || text.contains("微信账单") -> ImportSource.WECHAT
                text == "支付宝" || text.contains("支付宝交易") || text.contains("支付宝账单") -> ImportSource.ALIPAY
                else -> null
            }
        }.distinct().singleOrNull()

    private fun legacySource(rows: List<List<String>>): ImportSource? {
        val cells = rows.asSequence().take(25).flatten()
        return when {
            cells.any { "微信" in it } -> ImportSource.WECHAT
            cells.any { "支付宝" in it } -> ImportSource.ALIPAY
            else -> null
        }
    }

    private fun List<String>.findAlias(aliases: List<String>): Int = indexOfFirst { cell ->
        aliases.any { alias -> cell == normalizeHeader(alias) }
    }

    private fun normalizeHeader(value: String): String = value.trim()
        .trimStart('\uFEFF')
        .replace("（", "(")
        .replace("）", ")")
        .replace(Regex("\\s+"), "")
        .lowercase()

    private fun List<String>.cell(index: Int): String = if (index >= 0) getOrElse(index) { "" }.trim() else ""

    private fun String.cleanText(): String = replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
        .trim()

    private fun String.orderId(): String? = cleanText().trim('`', '\'').take(128).ifBlank { null }

    private fun parseStatementAmount(raw: String): Long? {
        if (raw.isBlank()) return null
        val normalized = raw.trim()
            .removePrefix("-")
            .removePrefix("+")
            .removePrefix("(")
            .removeSuffix(")")
            .replace("元", "")
        return parseAmountCents(normalized)
    }

    private fun parseDirection(value: String): TransactionType? = when {
        value.contains("收入") || value.contains("收款") || value.contains("转入") || value == "收" -> TransactionType.INCOME
        value.contains("支出") || value.contains("付款") || value.contains("转出") || value == "支" -> TransactionType.EXPENSE
        else -> null
    }

    private fun parseDateTime(raw: String, numericXlsxCell: Boolean, uses1904Dates: Boolean): LocalDateTime? {
        val value = raw.trim().trim('`', '\'')
        if (value.isBlank()) return null
        dateTimeFormatters.forEach { formatter ->
            runCatching { return LocalDateTime.parse(value, formatter).takeIf { it.year in 1..9999 } }
        }
        dateFormatters.forEach { formatter ->
            runCatching { return LocalDate.parse(value, formatter).takeIf { it.year in 1..9999 }?.atTime(LocalTime.NOON) }
        }
        if (!numericXlsxCell) return null
        val serial = value.toBigDecimalOrNull() ?: return null
        if (serial >= BigDecimal.valueOf(2_958_466)) return null
        val epoch = if (uses1904Dates) {
            if (serial.signum() < 0) return null
            LocalDateTime.of(1904, 1, 1, 0, 0)
        } else {
            if (serial <= BigDecimal.ONE) return null
            LocalDateTime.of(1899, 12, 30, 0, 0)
        }
        val seconds = serial.multiply(BigDecimal(86_400)).setScale(0, RoundingMode.HALF_UP).longValueExact()
        return epoch.plusSeconds(seconds).takeIf { it.year <= 9999 }
    }

    private fun suggestCategory(text: String, type: TransactionType): String {
        if (type == TransactionType.INCOME) {
            return when {
                text.containsAny("工资", "薪资", "薪水") -> "工资"
                text.containsAny("奖金", "报销", "绩效") -> "奖金"
                text.containsAny("理财", "利息", "分红", "收益") -> "理财"
                text.containsAny("红包") -> "红包"
                else -> "其他收入"
            }
        }
        return when {
            text.containsAny("餐", "饭", "咖啡", "麦当劳", "肯德基", "外卖", "美团", "饿了么", "奶茶") -> "餐饮"
            text.containsAny("地铁", "公交", "滴滴", "打车", "火车", "高铁", "加油", "停车", "单车", "航空") -> "交通"
            text.containsAny("房租", "物业", "水费", "电费", "燃气", "住房") -> "住房"
            text.containsAny("医院", "药", "门诊", "医疗", "挂号") -> "医疗"
            text.containsAny("电影", "游戏", "视频", "音乐", "娱乐", "KTV", "旅游") -> "娱乐"
            text.containsAny("教育", "课程", "培训", "书店", "学费") -> "教育"
            text.containsAny("淘宝", "京东", "拼多多", "超市", "商城", "购物") -> "购物"
            else -> "其他支出"
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean = keywords.any(::contains)

    private fun legacyFingerprint(source: ImportSource, externalId: String?, fields: List<String>): String =
        if (externalId == null) legacyFieldKey(fields, false) else sha256("${source.name}|$externalId")
}

private val fingerprintWhitespace = Regex("\\s+")

private fun fingerprintFields(
    source: ImportSource,
    time: String,
    amountCents: Long,
    type: TransactionType,
    merchant: String,
    note: String,
): List<String> = listOf(source.name, time, amountCents.toString(), type.name, merchant, note)
    .map { it.trim().replace(fingerprintWhitespace, " ").lowercase() }

private fun legacyFieldKey(fields: List<String>, versioned: Boolean): String =
    if (versioned) "v2:${framedFingerprint(fields)}" else sha256(fields.joinToString("|"))

private fun framedFingerprint(fields: List<String>): String =
    sha256(fields.joinToString("") { "${it.length}:$it" })

private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
    .digest(raw.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
