package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2ReplyContextSnapshotTest {
    private val options = MemoryV2ReplyContextOptions(
        exactRecentDays = 3,
        expandedRecentDays = 7,
        similarityThreshold = 0.65,
        timezone = "Asia/Shanghai",
        maxPromptUtf8Bytes = 0,
        recentRawMessages = 8,
    )

    @Test
    fun `mandatory facts and layered event windows are assembled`() {
        val layers = assemble(
            memories = listOf(
                memory("a0", "A0", "对方是你的同事。", NOW - DAY),
                memory("a1", "A1", "对方喜欢草莓冰淇淋。", NOW - DAY * 30),
                memory("recent", "A2", "对方说明天要去医院。", NOW - DAY),
                memory("expanded", "A2", "对方提到周末去公园。", NOW - DAY * 5),
                memory(
                    "old",
                    "A2",
                    "对方以前讨论过旅行计划。",
                    NOW - DAY * 12,
                ),
            ),
            query = "旅行计划怎么样",
        )

        assertEquals(listOf("a0"), layers.a0.map { it.memoryId })
        assertEquals(listOf("a1"), layers.a1.map { it.memoryId })
        assertEquals(
            listOf("recent"),
            layers.a2Exact.map { it.memory.memoryId },
        )
        assertEquals(
            listOf("expanded"),
            layers.a2Expanded.map { it.memory.memoryId },
        )
        assertEquals(
            listOf("old"),
            layers.olderRelated.map { it.memoryId },
        )
    }

    @Test
    fun `expanded duplicate events collapse but recent exact events remain visible`() {
        val layers = assemble(
            memories = listOf(
                memory(
                    "recent-a",
                    "A2",
                    "对方问你在不在。",
                    NOW - DAY,
                ),
                memory(
                    "recent-b",
                    "A2",
                    "对方再次询问你是否在线。",
                    NOW - DAY - 60,
                ),
                memory(
                    "expanded-a",
                    "A2",
                    "对方说周六去公园散步。",
                    NOW - DAY * 5,
                ),
                memory(
                    "expanded-b",
                    "A2",
                    "对方再次说周六要去公园散步。",
                    NOW - DAY * 5 - 60,
                ),
            ),
            query = "",
        )

        assertEquals(2, layers.a2Exact.size)
        assertEquals(1, layers.a2Expanded.size)
        assertEquals(2, layers.a2Expanded.single().duplicateCount)
        assertEquals(1, layers.a2CollapsedCount)
    }

    @Test
    fun `render keeps stable semantic order and does not expose memory ids`() {
        val layers = assemble(
            memories = listOf(
                memory("hidden-a0", "A0", "对方是你的同事。", NOW - DAY),
                memory("hidden-a1", "A1", "对方喜欢草莓。", NOW - DAY),
                memory("hidden-a2", "A2", "对方说明天早起。", NOW - DAY),
            ),
            summaries = listOf(summary("unresolved", "周末还没有决定。")),
            messages = listOf(
                MemoryV2ReplyContextMessage(1, "user", "在吗", NOW - 30),
                MemoryV2ReplyContextMessage(2, "assistant", "在", NOW - 20),
            ),
            query = "在吗",
        )
        val text = MemoryV2ReplyContextPolicy.render(layers, options)

        val positions = listOf(
            "[A0 核心事实]",
            "[A1 长期事实]",
            "[A3 历史会话摘要]",
            "[A2 最近 3 天]",
            "[当前代理会话]",
            "[上下文边界]",
        ).map { text.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertFalse("hidden-a0" in text)
        assertTrue("对方 " in text)
        assertTrue("你 " in text)
    }

    @Test
    fun `duplicate summary items keep the newest copy`() {
        val older = summary(
            "goals",
            "下周一起吃饭。",
            NOW - DAY * 2,
            "older",
        )
        val newer = summary(
            "goals",
            "下周一起吃饭。",
            NOW - DAY,
            "newer",
        )
        val layers = assemble(
            memories = emptyList(),
            summaries = listOf(older, newer),
            query = "",
        )

        assertEquals(1, layers.summaryItemCount)
        assertEquals("newer", layers.sessionSummaries.single().sessionId)
    }

    @Test
    fun `snapshot hash changes with scope deletion epoch and prompt`() {
        val first = MemoryV2ReplyContextPolicy.snapshotSha256(
            "scope",
            1,
            "session",
            "prompt",
        )
        val second = MemoryV2ReplyContextPolicy.snapshotSha256(
            "scope",
            2,
            "session",
            "prompt",
        )

        assertEquals(64, first.length)
        assertFalse(first == second)
    }

    @Test
    fun `current session skips empty newer sessions`() {
        val selected = MemoryV2ReplyContextPolicy.selectCurrentSession(
            listOf(
                MemoryV2ReplyContextSessionCandidate(
                    "empty-newer",
                    "closed",
                    0,
                    NOW,
                    0,
                ),
                MemoryV2ReplyContextSessionCandidate(
                    "conversation",
                    "closed",
                    2,
                    NOW - 60,
                    8,
                ),
            ),
        )

        assertEquals("conversation", selected?.sessionId)
    }

    @Test
    fun `pending conflict is rendered as confirmation data before recent events`() {
        val layers = MemoryV2ReplyContextPolicy.assemble(
            nowEpochSeconds = NOW,
            query = "生日",
            memories = listOf(
                memory("birthday", "A1", "对方的生日是 10 月 27 日。", NOW),
                memory("event", "A2", "对方说明天过生日。", NOW),
            ),
            sessionSummaries = emptyList(),
            sessionMessages = emptyList(),
            omittedMessageCount = 0,
            options = options,
            pendingConflicts = listOf(
                MemoryV2ReplyContextConflict(
                    conflictId = "conflict",
                    memoryId = "birthday",
                    existingTier = "A1",
                    existingText = "对方的生日是 10 月 27 日。",
                    candidateText = "对方明天过生日。",
                    candidateValue = "10 月 26 日",
                    createdAt = NOW,
                ),
            ),
        )
        val text = MemoryV2ReplyContextPolicy.render(layers, options)

        assertTrue(text.indexOf("[待确认记忆冲突]") < text.indexOf("[A2 最近 3 天]"))
        assertTrue("在对方明确纠正前保留原记录" in text)
    }

    @Test
    fun `opposite preference creates a deterministic clarification`() {
        val layers = assemble(
            memories = listOf(
                memory(
                    "icecream",
                    "A1",
                    "对方喜欢吃草莓冰淇淋。",
                    NOW - DAY,
                    predicate = "preference.food",
                ),
            ),
            query = "我不喜欢草莓冰淇淋，喜欢咖啡",
        )
        val text = MemoryV2ReplyContextPolicy.render(layers, options)

        assertEquals("A1", layers.preflightConflict?.tier)
        assertTrue(
            layers.preflightConflict?.clarification.orEmpty()
                .contains("是我记错了吗？"),
        )
        assertTrue("[本轮记忆冲突预检]" in text)
    }

    @Test
    fun `explicit changed preference does not create a clarification preflight`() {
        val layers = assemble(
            memories = listOf(
                memory(
                    "icecream",
                    "A1",
                    "对方喜欢吃草莓冰淇淋。",
                    NOW - DAY,
                    predicate = "preference.food",
                ),
            ),
            query = "草莓我早就吃腻了，现在更偏爱咖啡。",
        )

        assertNull(layers.preflightConflict)
    }

    private fun assemble(
        memories: List<MemoryV2ReplyContextMemory>,
        summaries: List<MemoryV2ReplyContextSessionSummary> = emptyList(),
        messages: List<MemoryV2ReplyContextMessage> = emptyList(),
        query: String,
    ): MemoryV2ReplyContextLayers {
        return MemoryV2ReplyContextPolicy.assemble(
            nowEpochSeconds = NOW,
            query = query,
            memories = memories,
            sessionSummaries = summaries,
            sessionMessages = messages,
            omittedMessageCount = 0,
            options = options,
        )
    }

    private fun memory(
        id: String,
        tier: String,
        text: String,
        anchor: Long,
        predicate: String = if (tier == "A2") "event.summary" else "profile.fact",
    ): MemoryV2ReplyContextMemory {
        return MemoryV2ReplyContextMemory(
            memoryId = id,
            version = 1,
            tier = tier,
            predicate = predicate,
            text = text,
            epistemic = if (tier == "A0") "configured" else "declared",
            commitmentState = "",
            temporalJson = "{}",
            sourceAnchor = anchor,
            updatedAt = anchor,
            expiresAt = if (tier == "A2") NOW + DAY * 20 else null,
            retentionScore = 0.5,
            pinned = tier == "A0",
            evidenceCount = 1,
            pendingConflicts = 0,
        )
    }

    private fun summary(
        section: String,
        text: String,
        updatedAt: Long = NOW,
        sessionId: String = "session",
    ): MemoryV2ReplyContextSessionSummary {
        return MemoryV2ReplyContextSessionSummary(
            sessionId = sessionId,
            updatedAt = updatedAt,
            workingVersion = 1,
            items = listOf(
                MemoryV2ReplyContextSummaryItem(
                    section = section,
                    text = text,
                    evidenceCount = 1,
                ),
            ),
        )
    }

    companion object {
        private const val DAY = 86_400L
        private const val NOW = 1_800_000_000L
    }
}
