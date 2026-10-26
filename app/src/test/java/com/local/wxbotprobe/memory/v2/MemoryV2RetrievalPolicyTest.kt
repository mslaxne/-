package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2RetrievalPolicyTest {
    @Test
    fun `query terms use chinese ngrams instead of requiring the full sentence`() {
        val terms = MemoryV2RetrievalPolicy.terms("你干嘛呢")

        assertTrue("干嘛" in terms)
        assertEquals(false, "你干嘛呢" in terms)
    }

    @Test
    fun `A0 is retained even without lexical overlap`() {
        val a0 = record(
            id = "contact-a",
            tier = "A0",
            text = "对方是你的同事。",
            predicate = "relationship.role",
            age = 400,
        )
        val a1 = record(
            id = "food",
            tier = "A1",
            text = "对方喜欢吃草莓冰淇淋。",
            predicate = "preference.food",
            age = 10,
        )

        val selected = MemoryV2RetrievalPolicy.select(
            records = listOf(a1, a0),
            query = "你干嘛呢",
            nowEpochSeconds = NOW,
            halfLifeDays = 30.0,
            maxItems = 1,
        )

        assertEquals(listOf("contact-a"), selected.map { it.memoryId })
    }

    @Test
    fun `zero selection limit keeps all active candidates`() {
        val records = listOf(
            record("a0", "A0", "对方是你的同事。", "relationship.role", 20),
            record("a1", "A1", "对方喜欢冰淇淋。", "preference.food", 10),
            record("a2", "A2", "对方说明天早起。", "event.summary", 1),
        )

        val selected = MemoryV2RetrievalPolicy.select(
            records = records,
            query = "今天怎么样",
            nowEpochSeconds = NOW,
            halfLifeDays = 30.0,
            maxItems = 0,
        )

        assertEquals(3, selected.size)
        assertEquals("a0", selected.first().memoryId)
    }

    private fun record(
        id: String,
        tier: String,
        text: String,
        predicate: String,
        age: Long,
    ): MemoryV2RetrievedRecord {
        return MemoryV2RetrievedRecord(
            memoryId = id,
            version = 1,
            tier = tier,
            predicate = predicate,
            text = text,
            temporalJson = "{}",
            expiresAt = null,
            sourceAnchor = NOW - age,
            updatedAt = NOW - age,
            retentionScore = 0.5,
            pinned = tier == "A0",
        )
    }

    companion object {
        private const val NOW = 1_800_000_000L
    }
}
