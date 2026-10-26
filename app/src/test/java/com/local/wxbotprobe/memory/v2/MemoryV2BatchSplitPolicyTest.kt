package com.local.wxbotprobe.memory.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryV2BatchSplitPolicyTest {
    @Test
    fun `thirty two sources split into two ordered halves`() {
        val sources = (1..32).map { "message-$it" }

        val partitions = MemoryV2BatchSplitPolicy.splitExtraction(sources)

        assertEquals(2, partitions.size)
        assertEquals(sources.take(16), partitions[0])
        assertEquals(sources.drop(16), partitions[1])
    }

    @Test
    fun `odd batch keeps prefix larger and preserves order`() {
        val sources = listOf("a", "b", "c", "d", "e")

        val partitions = MemoryV2BatchSplitPolicy.splitExtraction(sources)

        assertEquals(listOf("a", "b", "c"), partitions[0])
        assertEquals(listOf("d", "e"), partitions[1])
    }

    @Test
    fun `duplicates are removed before splitting`() {
        val partitions = MemoryV2BatchSplitPolicy.splitExtraction(
            listOf("a", "b", "a", "c"),
        )

        assertEquals(listOf("a", "b"), partitions[0])
        assertEquals(listOf("c"), partitions[1])
    }

    @Test
    fun `single or empty batch is not splittable`() {
        assertTrue(MemoryV2BatchSplitPolicy.splitExtraction(emptyList()).isEmpty())
        assertTrue(MemoryV2BatchSplitPolicy.splitExtraction(listOf("only")).isEmpty())
    }
}
