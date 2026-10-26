package com.local.wxbotprobe.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChatStateTest {
    private fun item(text: String, kind: BubbleKind = BubbleKind.IN) =
        ScreenItem(text, kind, 0, 200)

    @Test
    fun `first frame is baseline and does not start a reply`() {
        val state = LiveChatState()
        assertNull(state.observe(listOf(item("旧消息")), false, 0))
        assertFalse(state.isPrimed)
        assertNull(state.observe(listOf(item("旧消息")), false, 1_000))
        assertTrue(state.isPrimed)
        assertNull(state.observe(listOf(item("旧消息")), false, 5_000))
    }

    @Test
    fun `fingerprint becoming known restarts a stable baseline`() {
        val state = LiveChatState()
        val old = listOf(item("旧消息"))
        assertNull(state.observe(old, false, 0, chatFingerprint = 0L, chatTitle = "A"))
        assertNull(state.observe(old, false, 1_000, chatFingerprint = 0L, chatTitle = "A"))
        assertTrue(state.isPrimed)

        val newlyVisibleOldMessages = listOf(item("旧消息"), item("更早的旧消息"))
        assertNull(
            state.observe(
                newlyVisibleOldMessages,
                false,
                2_000,
                chatFingerprint = 11L,
                chatTitle = "A",
            ),
        )
        assertFalse(state.isPrimed)
        assertNull(
            state.observe(
                newlyVisibleOldMessages,
                false,
                3_000,
                chatFingerprint = 11L,
                chatTitle = "A",
            ),
        )
        assertTrue(state.isPrimed)
        assertNull(
            state.observe(
                newlyVisibleOldMessages,
                false,
                8_000,
                chatFingerprint = 11L,
                chatTitle = "A",
            ),
        )
    }

    @Test
    fun `explicit baseline reset discards seen messages and pending bursts`() {
        val state = LiveChatState(
            burstWaitMillis = 3_000,
            baselineObservations = 1,
        )
        state.observe(emptyList(), false, 0)
        state.observe(listOf(item("旧消息")), false, 1_000)
        assertTrue(state.hasPendingBurst)

        state.resetForNewBaseline()

        assertFalse(state.isPrimed)
        assertFalse(state.hasPendingBurst)
        assertEquals(0, state.lastFreshCount)
        assertNull(state.observe(listOf(item("旧消息")), false, 2_000))
        assertTrue(state.isPrimed)
    }

    @Test
    fun `new incoming messages wait for the last message in a burst`() {
        val state = LiveChatState(
            burstWaitMillis = 3_000,
            baselineObservations = 1,
        )
        state.observe(emptyList(), false, 0)
        assertNull(state.observe(listOf(item("有点")), false, 1_000))
        assertTrue(state.hasPendingBurst)
        assertNull(
            state.observe(listOf(item("有点"), item("不知道吃什么")), false, 2_500),
        )
        assertNull(state.observe(listOf(item("有点"), item("不知道吃什么")), false, 5_499))
        val completed = state.observe(
            listOf(item("有点"), item("不知道吃什么")),
            false,
            5_500,
        )
        assertEquals(listOf("有点", "不知道吃什么"), completed?.items?.map { it.text })
        assertFalse(completed?.isGroup ?: true)
        assertFalse(state.hasPendingBurst)
    }

    @Test
    fun `short repeated message after leaving the screen starts a new burst`() {
        val state = LiveChatState(
            burstWaitMillis = 3_000,
            baselineObservations = 1,
        )
        state.observe(listOf(item("好")), false, 0)
        assertNull(state.observe(emptyList(), false, 1_000))
        assertNull(state.observe(listOf(item("好")), false, 2_000))
        val completed = state.observe(listOf(item("好")), false, 5_001)
        assertEquals(listOf("好"), completed?.items?.map { it.text })
    }

    @Test
    fun `outgoing items do not enter a reply burst`() {
        val state = LiveChatState(baselineObservations = 1)
        state.observe(emptyList(), false, 0)
        assertNull(state.observe(listOf(item("我发的", BubbleKind.OUT)), false, 1_000))
        assertNull(state.observe(listOf(item("我发的", BubbleKind.OUT)), false, 10_000))
    }

    @Test
    fun `switching chats starts a new baseline`() {
        val state = LiveChatState(
            burstWaitMillis = 3_000,
            baselineObservations = 1,
        )
        state.observe(listOf(item("聊天一旧消息")), false, 0, chatFingerprint = 11L)
        assertNull(
            state.observe(
                listOf(item("聊天二旧消息")),
                false,
                1_000,
                chatFingerprint = 22L,
            ),
        )
        assertNull(
            state.observe(
                listOf(item("聊天二旧消息"), item("聊天二新消息")),
                false,
                2_000,
                chatFingerprint = 22L,
            ),
        )
        val completed = state.observe(
            listOf(item("聊天二旧消息"), item("聊天二新消息")),
            false,
            5_100,
            chatFingerprint = 22L,
        )
        assertEquals(listOf("聊天二新消息"), completed?.items?.map { it.text })
    }

    @Test
    fun `title change starts a new baseline even when fingerprint collides`() {
        val state = LiveChatState(
            burstWaitMillis = 3_000,
            baselineObservations = 1,
        )
        state.observe(
            listOf(item("微信主列表")),
            false,
            0,
            chatFingerprint = 11L,
            chatTitle = "微信",
        )
        assertNull(
            state.observe(
                listOf(item("A 的旧消息"), item("A 的测试消息")),
                false,
                1_000,
                chatFingerprint = 11L,
                chatTitle = "A",
            ),
        )
        assertTrue(state.isPrimed)
        assertEquals(0, state.lastFreshCount)
    }

    @Test
    fun `generic wechat title does not prime or collect messages`() {
        val state = LiveChatState(burstWaitMillis = 3_000)
        assertNull(
            state.observe(
                listOf(item("主列表旧消息"), item("主列表新消息")),
                false,
                0,
                chatFingerprint = 11L,
                chatTitle = "微信(8)",
            ),
        )
        assertFalse(state.isPrimed)
        assertEquals(0, state.lastFreshCount)
    }

    @Test
    fun `pending burst can be drained before the next OCR observation`() {
        val state = LiveChatState(
            burstWaitMillis = 3_000,
            baselineObservations = 1,
        )
        state.observe(emptyList(), false, 0)
        assertNull(state.observe(listOf(item("新消息")), false, 1_000))
        assertNull(state.drainBurstIfReady(3_999))
        val completed = state.drainBurstIfReady(4_000)
        assertEquals(listOf("新消息"), completed?.items?.map { it.text })
        assertFalse(state.hasPendingBurst)
    }
}
