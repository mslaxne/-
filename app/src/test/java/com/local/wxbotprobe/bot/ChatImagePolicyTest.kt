package com.local.wxbotprobe.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImagePolicyTest {
    private fun image(index: Int): ChatImage {
        return ChatImage(
            bytes = byteArrayOf(index.toByte()),
            width = 640,
            height = 480,
            sha256 = "image-$index",
        )
    }

    @Test
    fun `only the newest four user images survive request capping`() {
        val messages = listOf(
            ChatMessage("user", "one", images = listOf(image(1))),
            ChatMessage("assistant", "reply", images = listOf(image(9))),
            ChatMessage("user", "two", images = listOf(image(2), image(3))),
            ChatMessage(
                "user",
                "three",
                images = listOf(image(4), image(5), image(6)),
            ),
        )

        val capped = ChatImagePolicy.capForRequest(messages)

        assertEquals(emptyList<ChatImage>(), capped[0].images)
        assertEquals(emptyList<ChatImage>(), capped[1].images)
        assertEquals(listOf("image-3"), capped[2].images.map { it.sha256 })
        assertEquals(
            listOf("image-4", "image-5", "image-6"),
            capped[3].images.map { it.sha256 },
        )
    }

    @Test
    fun `image estimate grows with image dimensions`() {
        val small = image(1).copy(width = 128, height = 128)
        val large = image(1).copy(width = 1024, height = 1024)

        assertTrue(
            ChatImagePolicy.estimatedPromptTokens(large) >
                ChatImagePolicy.estimatedPromptTokens(small),
        )
    }

    @Test
    fun `image estimate respects the provider per-image token ceiling`() {
        val large = image(1).copy(width = 5000, height = 5000)

        assertEquals(1024, ChatImagePolicy.estimatedPromptTokens(large))
    }
}
