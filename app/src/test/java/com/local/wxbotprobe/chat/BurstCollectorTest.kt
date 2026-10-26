package com.local.wxbotprobe.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstCollectorTest {
    private fun item(text: String) = ScreenItem(text, BubbleKind.IN, 0, 200)

    @Test
    fun `later messages extend and merge into one burst`() {
        val collector = BurstCollector(waitMillis = 3_000)
        collector.add(listOf(item("有点")), nowMillis = 1_000)
        collector.add(listOf(item("不知道吃什么")), nowMillis = 2_000)
        assertFalse(collector.isReady(4_999))
        assertTrue(collector.isReady(5_000))
        assertEquals(
            listOf("有点", "不知道吃什么"),
            collector.drain().map { it.text },
        )
        assertFalse(collector.isReady(10_000))
    }

    @Test
    fun `empty list does not start a timer`() {
        val collector = BurstCollector(waitMillis = 3_000)
        collector.add(emptyList(), nowMillis = 1_000)
        assertFalse(collector.isReady(10_000))
        assertTrue(collector.drain().isEmpty())
    }

    @Test
    fun `continuous additions cannot extend a burst past its maximum age`() {
        val collector = BurstCollector(
            waitMillis = 4_000,
            maxAgeMillis = 12_000,
        )
        collector.add(listOf(item("第一条")), nowMillis = 1_000)
        collector.add(listOf(item("第二条")), nowMillis = 10_000)
        assertFalse(collector.isReady(12_999))
        assertTrue(collector.isReady(13_000))
        assertEquals(
            listOf("第一条", "第二条"),
            collector.drain().map { it.text },
        )
    }
}
