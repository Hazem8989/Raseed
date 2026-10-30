package com.hazem.dailystore.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Exact currency conversion helpers used by the store's pricing rules.
 *
 * USD is stored in cents. SYP is stored in whole pounds.
 * Exchange rate is SYP per USD multiplied by 1,000.
 */
object CurrencyConverter {

    private val conversionScale = BigDecimal("100000")

    fun usdCentsToSyp(
        usdCents: Long,
        rateSypPerUsdMilli: Long
    ): Long {
        require(usdCents >= 0) { "USD amount cannot be negative" }
        require(rateSypPerUsdMilli > 0) { "Exchange rate must be positive" }

        return BigDecimal.valueOf(usdCents)
            .multiply(BigDecimal.valueOf(rateSypPerUsdMilli))
            .divide(conversionScale, 0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    fun sypToUsdCents(
        sypAmount: Long,
        rateSypPerUsdMilli: Long
    ): Long {
        require(sypAmount >= 0) { "SYP amount cannot be negative" }
        require(rateSypPerUsdMilli > 0) { "Exchange rate must be positive" }

        return BigDecimal.valueOf(sypAmount)
            .multiply(conversionScale)
            .divide(
                BigDecimal.valueOf(rateSypPerUsdMilli),
                0,
                RoundingMode.HALF_UP
            )
            .longValueExact()
    }
}
