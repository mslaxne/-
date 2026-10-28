package com.local.wxbotprobe.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSendPolicyTest {
    private fun input(
        chatTitle: String = "张三",
        sentToday: Int = 0,
        remainingParts: Int = 1,
        spentUsdToday: Double = 0.0,
        dailyMessageLimit: Int = 50,
        dailyCostLimitUsd: Double = 1.0,
        quietHoursEnabled: Boolean = false,
        quietStartMinute: Int = 23 * 60,
        quietEndMinute: Int = 7 * 60,
        currentMinute: Int = 12 * 60,
    ): AutoSendPolicyInput {
        return AutoSendPolicyInput(
            chatTitle = chatTitle,
            sentToday = sentToday,
            remainingParts = remainingParts,
            spentUsdToday = spentUsdToday,
            dailyMessageLimit = dailyMessageLimit,
            dailyCostLimitUsd = dailyCostLimitUsd,
            quietHoursEnabled = quietHoursEnabled,
            quietStartMinute = quietStartMinute,
            quietEndMinute = quietEndMinute,
            currentMinute = currentMinute,
        )
    }

    @Test
    fun `recognized contact inside limits can send without a whitelist`() {
        assertNull(AutoSendPolicy.check(input(chatTitle = "任意联系人")))
    }

    @Test
    fun `quiet hours support ranges that cross midnight`() {
        assertEquals(
            AutoSendBlockReason.QUIET_HOURS,
            AutoSendPolicy.check(
                input(
                    quietHoursEnabled = true,
                    currentMinute = 23 * 60 + 30,
                ),
            ),
        )
        assertEquals(
            AutoSendBlockReason.QUIET_HOURS,
            AutoSendPolicy.check(
                input(
                    quietHoursEnabled = true,
                    currentMinute = 6 * 60,
                ),
            ),
        )
        assertNull(
            AutoSendPolicy.check(
                input(
                    quietHoursEnabled = true,
                    currentMinute = 12 * 60,
                ),
            ),
        )
    }

    @Test
    fun `whole delivery must fit in remaining daily capacity`() {
        assertEquals(
            AutoSendBlockReason.DAILY_MESSAGE_LIMIT,
            AutoSendPolicy.check(
                input(
                    sentToday = 49,
                    remainingParts = 2,
                ),
            ),
        )
    }

    @Test
    fun `cost cap blocks at the budget boundary`() {
        assertEquals(
            AutoSendBlockReason.DAILY_COST_LIMIT,
            AutoSendPolicy.check(input(spentUsdToday = 1.0)),
        )
    }

    @Test
    fun `unlimited message and cost caps never block`() {
        assertNull(
            AutoSendPolicy.check(
                input(
                    sentToday = 10_000,
                    remainingParts = 20,
                    spentUsdToday = 1_000.0,
                    dailyMessageLimit = com.local.wxbotprobe.SendSettings
                        .UNLIMITED_MESSAGE_LIMIT,
                    dailyCostLimitUsd = com.local.wxbotprobe.SendSettings
                        .UNLIMITED_DAILY_COST_LIMIT_USD,
                ),
            ),
        )
    }
}
