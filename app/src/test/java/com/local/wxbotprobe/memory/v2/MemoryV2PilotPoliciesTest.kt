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
    fun `A1 and A2 updates do not use programmatic semantic confirmation`() {
        assertFalse(
            MemoryV2ConflictPolicy.requiresManualReview(
                existingTier = "A1",
                candidateTier = "A1",
                subjectKind = "person",
                predicate = "profile.city",
            ),
        )
        assertFalse(
            MemoryV2ConflictPolicy.requiresManualReview(
                existingTier = "A2",
                candidateTier = "A2",
                subjectKind = "agent",
                predicate = "event.shared_dinner",
            ),
        )
    }

    @Test
    fun `new or existing A0 memory requires manual review`() {
        assertTrue(
            MemoryV2ConflictPolicy.requiresManualReview(
                existingTier = "A1",
                candidateTier = "A0",
                subjectKind = "person",
                predicate = "relationship.role",
            ),
        )
        assertTrue(
            MemoryV2ConflictPolicy.requiresManualReview(
                existingTier = "A0",
                candidateTier = "A1",
                subjectKind = "person",
                predicate = "relationship.role",
            ),
        )
    }

    @Test
    fun `persona config is protected without becoming a review candidate`() {
        assertTrue(
            MemoryV2ConflictPolicy.isProtectedPersona(
                "agent",
                "profile.personality",
            ),
        )
        assertFalse(
            MemoryV2ConflictPolicy.requiresManualReview(
                existingTier = "A1",
                candidateTier = "A1",
                subjectKind = "agent",
                predicate = "profile.personality",
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
