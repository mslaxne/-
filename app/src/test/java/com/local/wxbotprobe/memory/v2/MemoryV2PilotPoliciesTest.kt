package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2PilotPoliciesTest {
    @Test
    fun `provider windows produce different conservative reply budgets`() {
        val windows = mapOf(
            "deepseek" to 1_048_576,
            "gemini_free" to 131_072,
            "openrouter_free" to 32_768,
            "groq_free" to 32_768,
            "disabled" to 0,
        )

        val deepseek = budget("deepseek", windows)
        val gemini = budget("gemini_free", windows)
        val openrouter = budget("openrouter_free", windows)
        val groq = budget("groq_free", windows)
        val disabled = budget("disabled", windows)

        assertTrue(deepseek.maxPromptUtf8Bytes > gemini.maxPromptUtf8Bytes)
        assertTrue(gemini.maxPromptUtf8Bytes > openrouter.maxPromptUtf8Bytes)
        assertEquals(groq.maxPromptUtf8Bytes, openrouter.maxPromptUtf8Bytes)
        assertEquals(0, disabled.maxPromptUtf8Bytes)
        assertFalse(disabled.bounded)
    }

    @Test
    fun `budget omits oldest covered raw history but keeps recent turns`() {
        val messages = listOf(
            message(1, "old-1", "a".repeat(20)),
            message(2, "old-2", "b".repeat(20)),
            message(3, "recent-1", "c".repeat(20)),
            message(4, "recent-2", "d".repeat(20)),
        )

        val selected = MemoryV2ReplyContextBudgetPolicy.selectWithinBudget(
            messages = messages,
            coveredMessageIds = setOf("old-1", "old-2"),
            recentRawMessages = 2,
            maxPromptUtf8Bytes = 45,
            renderBytes = { values -> values.sumOf { it.text.length } },
        )

        assertEquals(listOf("recent-1", "recent-2"), selected.messages.map { it.messageId })
        assertEquals(2, selected.omittedMessageCount)
    }

    @Test
    fun `budget never drops uncovered old raw history`() {
        val error = assertThrows(MemoryV2Exception::class.java) {
            MemoryV2ReplyContextBudgetPolicy.selectWithinBudget(
                messages = listOf(
                    message(1, "uncovered", "a".repeat(40)),
                    message(2, "recent", "b".repeat(20)),
                ),
                coveredMessageIds = emptySet(),
                recentRawMessages = 1,
                maxPromptUtf8Bytes = 30,
                renderBytes = { values -> values.sumOf { it.text.length } },
            )
        }

        assertEquals(MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED, error.code)
    }

    @Test
    fun `budget stops omitting covered history once the prompt fits`() {
        val selected = MemoryV2ReplyContextBudgetPolicy.selectWithinBudget(
            messages = listOf(
                message(1, "old-1", "a".repeat(20)),
                message(2, "old-2", "b".repeat(20)),
                message(3, "recent", "c".repeat(10)),
            ),
            coveredMessageIds = setOf("old-1", "old-2"),
            recentRawMessages = 1,
            maxPromptUtf8Bytes = 30,
            renderBytes = { values -> values.sumOf { it.text.length } },
        )

        assertEquals(listOf("old-2", "recent"), selected.messages.map { it.messageId })
        assertEquals(1, selected.omittedMessageCount)
    }

    @Test
    fun `budget rejects even when covered history is exhausted before recent turns fit`() {
        val error = assertThrows(MemoryV2Exception::class.java) {
            MemoryV2ReplyContextBudgetPolicy.selectWithinBudget(
                messages = listOf(
                    message(1, "old", "a".repeat(100)),
                    message(2, "recent-1", "b".repeat(20)),
                    message(3, "recent-2", "c".repeat(20)),
                ),
                coveredMessageIds = setOf("old"),
                recentRawMessages = 2,
                maxPromptUtf8Bytes = 39,
                renderBytes = { values -> values.sumOf { it.text.length } },
            )
        }

        assertEquals(MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED, error.code)
    }

    @Test
    fun `A3 checkpoint waits for configured new messages and stays bounded`() {
        assertEquals(
            listOf("old", "new"),
            MemoryV2WorkingSummaryPolicy.mergeCoveredSources(
                previousSourceIds = listOf("old"),
                newSourceIds = listOf("new", "new"),
            ),
        )
        assertFalse(MemoryV2WorkingSummaryPolicy.shouldCheckpoint(7, 8))
        assertTrue(MemoryV2WorkingSummaryPolicy.shouldCheckpoint(8, 8))
        assertTrue(MemoryV2WorkingSummaryPolicy.withinBound("{}", 8_192))
        assertFalse(
            MemoryV2WorkingSummaryPolicy.withinBound(
                "a".repeat(8_193),
                8_192,
            ),
        )
    }

    @Test
    fun `three consecutive A3 checkpoints retain all twenty four sources`() {
        var covered = emptyList<String>()
        repeat(3) { round ->
            val sourceIds = (1..8).map { index ->
                "round-${round + 1}-message-$index"
            }
            covered = MemoryV2WorkingSummaryPolicy.mergeCoveredSources(
                previousSourceIds = covered,
                newSourceIds = sourceIds,
            )
        }

        assertEquals(24, covered.size)
        assertEquals(24, covered.distinct().size)
        assertEquals("round-1-message-1", covered.first())
        assertEquals("round-3-message-8", covered.last())
    }

    @Test
    fun `extraction output reserves enough room for the frozen envelope`() {
        assertEquals(
            2_048,
            MemoryV2BatchOutputPolicy.extractionMaxTokens(2_048),
        )
        assertEquals(
            1_200,
            MemoryV2BatchOutputPolicy.extractionMaxTokens(1_200),
        )
        assertEquals(
            2_048,
            MemoryV2BatchOutputPolicy.extractionMaxTokens(null),
        )
    }

    @Test
    fun `compact output envelope accepts only the frozen job id`() {
        val compact = org.json.JSONObject().put("job_id", "job-1")

        assertTrue(
            MemoryV2OutputEnvelopePolicy.matchesCompact(compact, "job-1"),
        )
        assertFalse(
            MemoryV2OutputEnvelopePolicy.matchesCompact(compact, "job-2"),
        )
        assertFalse(
            MemoryV2OutputEnvelopePolicy.matchesCompact(
                compact.put("scope_id", "scope"),
                "job-1",
            ),
        )
    }

    @Test
    fun `birthday mismatch asks before changing long term memory`() {
        val decision = MemoryV2ConflictConfirmationPolicy.decide(
            existingTier = "A1",
            candidateOperation = MemoryV2ReconciliationPolicy.SUPERSEDE,
            subjectKind = "person",
            predicate = "profile.birthday",
            userSourceTexts = listOf("明天就是我的生日了，你准备好送什么了吗？"),
        )

        assertEquals(
            MemoryV2ConflictConfirmationDecision.ASK_CONFIRMATION,
            decision,
        )
    }

    @Test
    fun `explicit correction and explicit changed state authorize update`() {
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("你记错了，是10月26日。"),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("你确实记错了，是10月26日。"),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("你肯定记反了，我根本没在杭州住过。"),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            MemoryV2ConflictConfirmationPolicy.decide(
                existingTier = "A1",
                candidateOperation = MemoryV2ReconciliationPolicy.SUPERSEDE,
                subjectKind = "person",
                predicate = "profile.city",
                userSourceTexts = listOf(
                    "你明明搞错了，我根本没在杭州住过。",
                    "我已经搬去上海了。",
                ),
            ),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("现在已经改成10月26日了。"),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("不是10月27日，是10月26日。"),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("不是这样啊，你记错了。"),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("我已经离婚了。"),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("草莓我早就吃腻了，现在更偏爱咖啡。"),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("我现在不喜欢草莓冰淇淋了。"),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("我改主意了，以后更喜欢咖啡。"),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            MemoryV2ConflictConfirmationPolicy.decide(
                existingTier = "A1",
                candidateOperation = MemoryV2ReconciliationPolicy.SUPERSEDE,
                subjectKind = "person",
                predicate = "profile.city",
                userSourceTexts = listOf(
                    "你之前在杭州对吧？",
                    "以后不在杭州了，我住苏州。",
                ),
            ),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("可能是吧，但你确实记错了，我现在只喝燕麦奶。"),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            decision("我上个月搬到上海了，现在一直住在上海。"),
        )
    }

    @Test
    fun `ambiguous agreement preserves the old memory`() {
        listOf(
            "好像是哦",
            "好像是吧，我也记不清了",
            "可能是吧",
            "也许是吧",
            "这样啊",
            "我记错了",
        ).forEach { text ->
            assertEquals(
                text,
                MemoryV2ConflictConfirmationDecision.KEEP_EXISTING,
                decision(text),
            )
        }
        assertEquals(
            MemoryV2ConflictConfirmationDecision.KEEP_EXISTING,
            MemoryV2ConflictConfirmationPolicy.decide(
                existingTier = "A1",
                candidateOperation = MemoryV2ReconciliationPolicy.SUPERSEDE,
                subjectKind = "person",
                predicate = "profile.city",
                userSourceTexts = listOf(
                    "我之前说可能搬到上海了。",
                    "可能是吧，我记不清了。",
                ),
            ),
        )
    }

    @Test
    fun `persona config cannot be changed by chat`() {
        assertTrue(
            MemoryV2ConflictConfirmationPolicy.isProtectedPersona(
                "agent",
                "profile.personality",
            ),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.PROTECTED,
            MemoryV2ConflictConfirmationPolicy.decide(
                existingTier = "A1",
                candidateOperation = MemoryV2ReconciliationPolicy.SUPERSEDE,
                subjectKind = "agent",
                predicate = "profile.personality",
                userSourceTexts = listOf("你记错了，你其实很外向"),
            ),
        )
    }

    @Test
    fun `shared event can be corrected but A3 never adjudicates`() {
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE,
            MemoryV2ConflictConfirmationPolicy.decide(
                existingTier = "A2",
                candidateOperation = MemoryV2ReconciliationPolicy.SUPERSEDE,
                subjectKind = "agent",
                predicate = "event.shared_dinner",
                userSourceTexts = listOf("你记错了，我们那天是在家里吃饭。"),
            ),
        )
        assertEquals(
            MemoryV2ConflictConfirmationDecision.IGNORE_A3,
            MemoryV2ConflictConfirmationPolicy.decide(
                existingTier = "A3",
                candidateOperation = MemoryV2ReconciliationPolicy.SUPERSEDE,
                subjectKind = "person",
                predicate = "profile.birthday",
                userSourceTexts = listOf("你记错了"),
            ),
        )
    }

    @Test
    fun `A0 conflict remains review gated even after explicit correction`() {
        assertEquals(
            MemoryV2ConflictConfirmationDecision.ASK_CONFIRMATION,
            MemoryV2ConflictConfirmationPolicy.decide(
                existingTier = "A0",
                candidateOperation = MemoryV2ReconciliationPolicy.SUPERSEDE,
                subjectKind = "person",
                predicate = "relationship.role",
                userSourceTexts = listOf("你记错了"),
            ),
        )
    }

    private fun budget(
        provider: String,
        windows: Map<String, Int>,
    ): MemoryV2ReplyContextBudget {
        return MemoryV2ReplyContextBudgetPolicy.resolve(
            providerId = provider,
            providerWindows = windows,
            outputReserveTokens = 500,
            safetyMarginTokens = 512,
            framingFixedTokens = 256,
            targetInputTokens = 0,
        )
    }

    private fun decision(text: String): MemoryV2ConflictConfirmationDecision {
        return MemoryV2ConflictConfirmationPolicy.decide(
            existingTier = "A1",
            candidateOperation = MemoryV2ReconciliationPolicy.SUPERSEDE,
            subjectKind = "person",
            predicate = "profile.birthday",
            userSourceTexts = listOf(text),
        )
    }

    private fun message(
        seq: Int,
        id: String,
        text: String,
    ): MemoryV2ReplyContextMessage {
        return MemoryV2ReplyContextMessage(
            seq = seq,
            role = if (seq % 2 == 0) "assistant" else "user",
            text = text,
            occurredAt = 1_800_000_000L + seq,
            messageId = id,
        )
    }
}
