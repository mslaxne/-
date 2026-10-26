package com.local.wxbotprobe.memory

import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContextBuilderTest {
    @Test
    fun `context contains recent summary and unresolved event`() {
        val now = 10 * 86_400_000L
        val memory = ChatMemory(
            chatId = "chat",
            identityKey = "张三",
            displayName = "张三",
            createdAt = 0L,
            updatedAt = now,
            sessions = listOf(
                SessionDigest(
                    "s1",
                    openedAt = now - 1_000L,
                    closedAt = now,
                    summary = "聊了周末吃饭",
                    topics = listOf("吃饭"),
                    messageCount = 4,
                    summaryStatus = "complete",
                ),
            ),
            events = listOf(
                MemoryEvent(
                    id = "e1",
                    type = "invitation",
                    text = "周六吃饭",
                    date = "2026-10-24",
                    status = "proposed",
                    importance = 0.9,
                    resolved = false,
                    lastMentionedAt = now,
                    sourceSessionId = "s1",
                    sourceMessageIds = listOf("m1"),
                ),
            ),
        )
        val note = MemoryContextBuilder.build(memory, now)
        assertTrue("聊了周末吃饭" in note)
        assertTrue("周六吃饭" in note)
        assertTrue("proposed" in note)
    }

    @Test
    fun `important unresolved event outranks old low importance event`() {
        val now = 10 * 86_400_000L
        val important = MemoryEvent(
            "a",
            "invitation",
            "明天见面",
            "",
            "pending",
            1.0,
            resolved = false,
            lastMentionedAt = now - 5 * 86_400_000L,
            sourceSessionId = "",
            sourceMessageIds = emptyList(),
        )
        val mundane = MemoryEvent(
            "b",
            "fact",
            "聊了游戏",
            "",
            "done",
            0.1,
            resolved = true,
            lastMentionedAt = now,
            sourceSessionId = "",
            sourceMessageIds = emptyList(),
        )
        assertTrue(
            MemoryContextBuilder.score(important, now) >
                MemoryContextBuilder.score(mundane, now),
        )
    }
}
