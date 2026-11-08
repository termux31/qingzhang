package com.example.qingzhang

import com.example.qingzhang.domain.formatCents
import com.example.qingzhang.domain.parseAmountCents
import com.example.qingzhang.domain.signedCents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun parsesExactDecimalAmounts() {
        assertEquals(1L, parseAmountCents("0.01"))
        assertEquals(1234L, parseAmountCents("12.34"))
        assertEquals(123450L, parseAmountCents("¥1,234.50"))
        assertEquals(100L, parseAmountCents("1"))
        assertEquals(99999999999L, parseAmountCents("999999999.99"))
    }

    @Test
    fun rejectsInvalidOrNonPositiveAmounts() {
        listOf("", "0", "0.00", "-1", "1.234", "abc", "1,2,3", "1000000000").forEach {
            assertNull("应拒绝：$it", parseAmountCents(it))
        }
    }

    @Test
    fun formatsWithoutFloatingPointArtifacts() {
        assertEquals("¥0.01", formatCents(1))
        assertEquals("¥1,234.50", formatCents(123450))
        assertEquals("-¥20.00", formatCents(-2000))
        assertEquals("+¥8.00", signedCents(800, true))
        assertEquals("-¥8.00", signedCents(800, false))
    }
}
