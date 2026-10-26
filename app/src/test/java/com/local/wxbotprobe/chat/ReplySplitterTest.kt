package com.local.wxbotprobe.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySplitterTest {
    @Test
    fun `overflow is merged into last part without dropping text`() {
        val parts = ReplySplitter(maxParts = 3).split("一\n二\n三\n四\n五")
        assertEquals(3, parts.size)
        assertEquals("一", parts[0])
        assertTrue(parts[2].contains("三"))
        assertTrue(parts[2].contains("四"))
        assertTrue(parts[2].contains("五"))
    }

    @Test
    fun `punctuation is not doubled when merging`() {
        assertEquals(
            listOf("就是脾气有点冲。夸得挺有水平"),
            ReplySplitter(maxParts = 1).split("就是脾气有点冲。\n夸得挺有水平"),
        )
        assertEquals(
            listOf("就是脾气有点冲，夸得挺有水平"),
            ReplySplitter(maxParts = 1).split("就是脾气有点冲\n夸得挺有水平"),
        )
    }

    @Test
    fun `long paragraph is split at sentence endings`() {
        val parts = ReplySplitter(maxParts = 3).split("今天有点忙。晚点再聊。你先吃吧。")
        assertEquals(listOf("今天有点忙。", "晚点再聊。", "你先吃吧。"), parts)
    }
}
