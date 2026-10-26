package com.local.wxbotprobe.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiCostCalculatorTest {
    @Test
    fun `flash pricing uses conservative peak rates`() {
        val cost = ApiCostCalculator.calculateUsd(
            model = "deepseek-flash",
            promptTokens = 1_000_000,
            completionTokens = 1_000_000,
        )
        assertEquals(1.5, cost, 0.000001)
    }

    @Test
    fun `pro pricing is tracked separately`() {
        val cost = ApiCostCalculator.calculateUsd(
            model = "deepseek-v4-pro",
            promptTokens = 1_000_000,
            completionTokens = 1_000_000,
        )
        assertEquals(5.28, cost, 0.000001)
    }

}
