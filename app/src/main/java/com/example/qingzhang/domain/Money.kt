package com.example.qingzhang.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

private val amountPattern = Regex("""\d{1,9}(\.\d{1,2})?""")
private val groupedAmountPattern = Regex("""\d{1,3}(,\d{3})+(\.\d{1,2})?""")

fun parseAmountCents(raw: String): Long? {
    if (raw.length > 40) return null
    val normalized = raw.trim()
        .removePrefix("¥")
        .removePrefix("￥")
        .replace(" ", "")
    if ("¥" in normalized || "￥" in normalized) return null
    if ("," in normalized && !groupedAmountPattern.matches(normalized)) return null
    val decimal = normalized.replace(",", "")
    if (!amountPattern.matches(decimal)) return null
    return runCatching {
        BigDecimal(decimal)
            .setScale(2, RoundingMode.UNNECESSARY)
            .movePointRight(2)
            .longValueExact()
            .takeIf { it > 0 }
    }.getOrNull()
}

fun formatCents(cents: Long, currency: Boolean = true): String {
    val negative = cents < 0
    val absolute = if (negative) -cents else cents
    val whole = String.format(Locale.CHINA, "%,d", absolute / 100)
    val value = "$whole.${(absolute % 100).toString().padStart(2, '0')}"
    return buildString {
        if (negative) append('-')
        if (currency) append('¥')
        append(value)
    }
}

fun signedCents(cents: Long, typeIsIncome: Boolean): String = buildString {
    append(if (typeIsIncome) '+' else '-')
    append(formatCents(cents))
}

fun percentage(numerator: Long, denominator: Long): Double =
    if (denominator <= 0L) 0.0 else numerator.toDouble() * 100.0 / denominator.toDouble()
