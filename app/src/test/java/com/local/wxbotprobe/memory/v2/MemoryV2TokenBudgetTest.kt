package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MemoryV2TokenBudgetTest {
    private val profile = MemoryModelProfile(
        profileId = "test-profile",
        provider = "deepseek",
        model = "deepseek-flash",
        modelRevision = "test-revision",
        contextWindowTokens = 1_048_576,
        outputMaxTokens = 2_048,
        verifiedAtEpochSeconds = 1_800_000_000L,
        textOnly = true,
        thinkingEnabled = false,
    )

    private fun request(
        maxTokens: Int = 500,
        hasTools: Boolean = false,
    ) = TokenBudgetRequest(
        model = "deepseek-flash",
        messages = listOf(
            MemoryTokenMessage("system", "遵守人设"),
            MemoryTokenMessage("user", "今天有点累"),
        ),
        maxTokens = maxTokens,
        thinkingDisabled = true,
        hasTools = hasTools,
    )

    @Test
    fun `valid text request returns conservative budget`() {
        val decision = MemoryV2TokenBudgetPolicy.estimate(
            request = request(),
            profile = profile,
            nowEpochSeconds = 1_800_000_100L,
        )

        assertEquals("conservative_utf8_estimate", decision.mode)
        assertEquals(1_048_576, decision.contextWindowTokens)
        assertEquals(1_047_564, decision.allowedInputTokens)
        assertEquals(500, decision.outputReserve)
        assertFalse(decision.guaranteedExact)
    }

    @Test
    fun `positive target can still impose an explicit caller budget`() {
        val decision = MemoryV2TokenBudgetPolicy.estimate(
            request = request(),
            profile = profile,
            nowEpochSeconds = 1_800_000_100L,
            config = TokenBudgetPolicyConfig(targetInputTokens = 12_288),
        )

        assertEquals(12_288, decision.allowedInputTokens)
    }

    @Test
    fun `unknown stale and unsupported profiles fail closed`() {
        assertError(MemoryV2ErrorCode.MODEL_CONTEXT_UNKNOWN) {
            MemoryV2TokenBudgetPolicy.estimate(
                request(),
                profile.copy(contextWindowTokens = null),
                1_800_000_100L,
            )
        }
        assertError(MemoryV2ErrorCode.MODEL_PROFILE_STALE) {
            MemoryV2TokenBudgetPolicy.estimate(
                request(),
                profile,
                1_800_000_100L + 31L * 86_400L,
            )
        }
        assertError(MemoryV2ErrorCode.UNSUPPORTED_TOKEN_MODE) {
            MemoryV2TokenBudgetPolicy.estimate(
                request(hasTools = true),
                profile,
                1_800_000_100L,
            )
        }
    }

    @Test
    fun `output and input budgets are checked before a model call`() {
        assertError(MemoryV2ErrorCode.OUTPUT_BUDGET_INVALID) {
            MemoryV2TokenBudgetPolicy.estimate(
                request(maxTokens = 2_049),
                profile,
                1_800_000_100L,
            )
        }
        assertError(MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED) {
            MemoryV2TokenBudgetPolicy.estimate(
                request(),
                profile.copy(contextWindowTokens = 1_000),
                1_800_000_100L,
            )
        }
    }

    private fun assertError(
        expected: MemoryV2ErrorCode,
        block: () -> Unit,
    ) {
        val error = runCatching(block).exceptionOrNull()
        assertEquals(expected, (error as MemoryV2Exception).code)
    }
}
