package com.local.wxbotprobe.api

import com.local.wxbotprobe.bot.ChatClient
import com.local.wxbotprobe.bot.ChatImage
import com.local.wxbotprobe.bot.ChatMessage
import com.local.wxbotprobe.bot.Completion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiBudgetPolicyTest {
    private class FakeUsage(
        var spent: Double = 0.0,
    ) : ApiUsageRecorder {
        var recordedPromptTokens = 0
        var recordedCompletionTokens = 0

        override fun spentToday(nowMillis: Long): Double = spent

        override fun record(
            promptTokens: Int,
            completionTokens: Int,
            model: String,
            nowMillis: Long,
        ) {
            recordedPromptTokens += promptTokens
            recordedCompletionTokens += completionTokens
            spent += ApiCostCalculator.calculateUsd(model, promptTokens, completionTokens)
        }
    }

    private class FakeClient(
        private val completion: Completion = Completion("ok", 10, 5, "deepseek-flash"),
    ) : ChatClient {
        var calls = 0

        override fun chat(
            messages: List<ChatMessage>,
            temperature: Double,
            maxTokens: Int,
        ): Completion {
            calls++
            return completion
        }
    }

    @Test
    fun `reservation includes current spend and in-flight requests`() {
        assertFalse(
            ApiBudgetPolicy.canReserve(
                spentUsd = 0.80,
                reservedUsd = 0.15,
                limitUsd = 1.00,
                requestUsd = 0.06,
            ),
        )
        assertTrue(
            ApiBudgetPolicy.canReserve(
                spentUsd = 0.80,
                reservedUsd = 0.10,
                limitUsd = 1.00,
                requestUsd = 0.05,
            ),
        )
    }

    @Test
    fun `worst case output tokens are included`() {
        val messages = listOf(ChatMessage("system", "测试提示词"))
        val low = ApiBudgetPolicy.worstCaseUsd(messages, 100, "deepseek-flash")
        val high = ApiBudgetPolicy.worstCaseUsd(messages, 1_000, "deepseek-flash")
        assertTrue(high > low)
    }

    @Test
    fun `prompt estimate includes image tokens`() {
        val textOnly = listOf(ChatMessage("user", "[[图片]]"))
        val withImage = listOf(
            ChatMessage(
                role = "user",
                content = "[[图片]]",
                images = listOf(
                    ChatImage(
                        bytes = byteArrayOf(1, 2, 3),
                        width = 1024,
                        height = 1024,
                    ),
                ),
            ),
        )

        assertTrue(
            ApiBudgetPolicy.estimatePromptTokens(withImage) >
                ApiBudgetPolicy.estimatePromptTokens(textOnly),
        )
    }

    @Test
    fun `budget is checked before the delegate is called`() {
        val delegate = FakeClient()
        val usage = FakeUsage(spent = 0.02)
        val client = MeteredChatClient(
            delegate = delegate,
            usageStore = usage,
            dailyLimitUsd = { 0.0201 },
            model = "deepseek-flash",
            nowMillis = { 1_700_000_000_000L },
        )

        runCatching {
            client.chat(
                listOf(ChatMessage("user", "这是一条需要调用模型的测试消息")),
                temperature = 1.0,
                maxTokens = 1_000,
            )
        }.onSuccess {
            throw AssertionError("budget check should have blocked the request")
        }.onFailure { error ->
            assertTrue(error is ApiBudgetExceededException)
        }
        assertEquals(0, delegate.calls)
    }

    @Test
    fun `successful call records actual usage and releases its reservation`() {
        val delegate = FakeClient()
        val usage = FakeUsage()
        val client = MeteredChatClient(
            delegate = delegate,
            usageStore = usage,
            dailyLimitUsd = { 1.0 },
            model = "deepseek-flash",
            nowMillis = { 1_700_000_000_000L },
        )

        val result = client.chat(
            listOf(ChatMessage("user", "在吗")),
            temperature = 1.0,
            maxTokens = 100,
        )

        assertEquals("ok", result.text)
        assertEquals(1, delegate.calls)
        assertEquals(10, usage.recordedPromptTokens)
        assertEquals(5, usage.recordedCompletionTokens)
    }
}
