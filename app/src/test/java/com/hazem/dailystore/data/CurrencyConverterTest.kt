package com.hazem.dailystore.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyConverterTest {
    @Test
    fun convertsUsdCentsToSypWholePounds() {
        assertEquals(15_000L, CurrencyConverter.usdCentsToSyp(100L, 15_000_000L))
        assertEquals(1_500_000L, CurrencyConverter.usdCentsToSyp(10_000L, 15_000_000L))
    }

    @Test
    fun convertsSypToUsdCentsWithHalfUpRounding() {
        assertEquals(100L, CurrencyConverter.sypToUsdCents(15_000L, 15_000_000L))
        assertEquals(1L, CurrencyConverter.sypToUsdCents(225L, 15_000_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeUsdAmounts() {
        CurrencyConverter.usdCentsToSyp(-1L, 15_000_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveExchangeRates() {
        CurrencyConverter.usdCentsToSyp(100L, 0L)
    }
}
