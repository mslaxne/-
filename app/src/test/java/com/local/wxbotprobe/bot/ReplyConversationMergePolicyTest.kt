package com.local.wxbotprobe.bot

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyConversationMergePolicyTest {
    @Test
    fun `visible suffix reuses complete persisted session`() {
        val persisted = listOf(
            ChatMessage("user", "第一句"),
            ChatMessage("assistant", "第二句"),
            ChatMessage("user", "第三句"),
        )
        val visible = listOf(
            ChatMessage("assistant", "屏幕外的旧回复"),
            ChatMessage("user", "第一句"),
            ChatMessage("assistant", "第二句"),
            ChatMessage("user", "第三句"),
        )

        assertEquals(
            listOf(
                ChatMessage("assistant", "屏幕外的旧回复"),
                ChatMessage("user", "第一句"),
                ChatMessage("assistant", "第二句"),
                ChatMessage("user", "第三句"),
            ),
            ReplyConversationMergePolicy.merge(persisted, visible),
        )
    }

    @Test
    fun `newer visible messages stay after persisted history`() {
        val persisted = listOf(
            ChatMessage("user", "较早问题"),
            ChatMessage("assistant", "较早回答"),
            ChatMessage("user", "第一条新消息"),
            ChatMessage("user", "第二条新消息"),
        )
        val visible = listOf(
            ChatMessage("user", "第一条新消息"),
            ChatMessage("user", "第二条新消息"),
        )

        assertEquals(persisted, ReplyConversationMergePolicy.merge(persisted, visible))
    }

    @Test
    fun `mismatched tail appends only unseen visible messages`() {
        val persisted = listOf(
            ChatMessage("user", "持久消息"),
            ChatMessage("assistant", "持久回复"),
        )
        val visible = listOf(
            ChatMessage("user", "屏幕旧消息"),
            ChatMessage("assistant", "持久回复"),
            ChatMessage("user", "OCR 差异消息"),
        )

        assertEquals(
            listOf(
                ChatMessage("user", "持久消息"),
                ChatMessage("assistant", "持久回复"),
                ChatMessage("user", "屏幕旧消息"),
                ChatMessage("user", "OCR 差异消息"),
            ),
            ReplyConversationMergePolicy.merge(persisted, visible),
        )
    }

    @Test
    fun `same placeholder with different image hash is not merged`() {
        fun image(sha: String) = ChatImage(
            bytes = sha.toByteArray(),
            width = 100,
            height = 100,
            sha256 = sha,
        )
        val persisted = listOf(
            ChatMessage("user", "[[图片]]", listOf(image("old"))),
        )
        val visible = listOf(
            ChatMessage("user", "[[图片]]", listOf(image("new"))),
        )

        assertEquals(
            persisted + visible,
            ReplyConversationMergePolicy.merge(persisted, visible),
        )
    }
}
