package com.local.wxbotprobe.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupConversationContextTest {
    private fun snapshot(
        items: List<ScreenItem>,
        fingerprint: Long = 42L,
        group: Boolean = false,
    ): ScreenSnapshot {
        return ScreenSnapshot(
            width = 1080,
            height = 2400,
            items = items,
            greyRows = emptyList(),
            isGroup = group,
            inputField = null,
            sendButtonY = null,
            ocrRowCount = items.size,
            engine = "test",
            chat = ChatIdentity(
                title = "联系人",
                fingerprint = fingerprint,
            ),
        )
    }

    @Test
    fun `visible messages keep their order and roles`() {
        val result = StartupConversationContextBuilder.build(
            snapshot(
                listOf(
                    ScreenItem("早", BubbleKind.OUT, y = 100, xLeft = 700),
                    ScreenItem("早呀", BubbleKind.IN, y = 200, xLeft = 100),
                    ScreenItem("我先去忙了", BubbleKind.OUT, y = 300, xLeft = 700),
                    ScreenItem("嗯嗯", BubbleKind.IN, y = 400, xLeft = 100),
                ),
            ),
            scopeKey = "contact-a",
        )

        assertNotNull(result)
        assertEquals(
            listOf("assistant", "user", "assistant", "user"),
            result!!.messages.map { it.role },
        )
        assertEquals("嗯嗯", result.lastMessageText)
        assertTrue(result.shouldRespond)
        assertTrue(result.transcript.contains("我: 我先去忙了"))
    }

    @Test
    fun `outgoing bottom message does not trigger a startup reply`() {
        val result = StartupConversationContextBuilder.build(
            snapshot(
                listOf(
                    ScreenItem("在吗", BubbleKind.IN, y = 100, xLeft = 100),
                    ScreenItem("在", BubbleKind.OUT, y = 200, xLeft = 700),
                ),
            ),
            scopeKey = "contact-a",
        )!!

        assertEquals(BubbleKind.OUT, result.lastMessageKind)
        assertFalse(result.shouldRespond)
    }

    @Test
    fun `unknown bottom message fails closed`() {
        val result = StartupConversationContextBuilder.build(
            snapshot(
                listOf(
                    ScreenItem("在吗", BubbleKind.IN, y = 100, xLeft = 100),
                    ScreenItem("看不清", BubbleKind.UNKNOWN, y = 200, xLeft = 400),
                ),
            ),
            scopeKey = "contact-a",
        )!!

        assertEquals(BubbleKind.UNKNOWN, result.lastMessageKind)
        assertEquals(1, result.messages.size)
        assertFalse(result.shouldRespond)
    }

    @Test
    fun `snapshot id is stable for the same visible context`() {
        val first = StartupConversationContextBuilder.build(
            snapshot(
                listOf(
                    ScreenItem("在吗", BubbleKind.IN, y = 100, xLeft = 100),
                    ScreenItem("在", BubbleKind.OUT, y = 200, xLeft = 700),
                ),
            ),
            scopeKey = "contact-a",
        )!!
        val second = StartupConversationContextBuilder.build(
            snapshot(
                listOf(
                    ScreenItem("在吗", BubbleKind.IN, y = 900, xLeft = 120),
                    ScreenItem("在", BubbleKind.OUT, y = 1_000, xLeft = 680),
                ),
                fingerprint = 99L,
            ),
            scopeKey = "contact-a",
        )!!

        assertEquals(first.snapshotId, second.snapshotId)
        assertEquals("startup-${first.snapshotId}", first.taskId)
    }

    @Test
    fun `snapshot id changes with contact or visible content`() {
        val baseline = StartupConversationContextBuilder.build(
            snapshot(listOf(ScreenItem("在吗", BubbleKind.IN, y = 100, xLeft = 100))),
            scopeKey = "contact-a",
        )!!
        val otherContact = StartupConversationContextBuilder.build(
            snapshot(listOf(ScreenItem("在吗", BubbleKind.IN, y = 100, xLeft = 100))),
            scopeKey = "contact-b",
        )!!
        val otherContent = StartupConversationContextBuilder.build(
            snapshot(listOf(ScreenItem("忙吗", BubbleKind.IN, y = 100, xLeft = 100))),
            scopeKey = "contact-a",
        )!!

        assertNotEquals(baseline.snapshotId, otherContact.snapshotId)
        assertNotEquals(baseline.snapshotId, otherContent.snapshotId)
    }

    @Test
    fun `image-only visible items use a stable placeholder`() {
        val result = StartupConversationContextBuilder.build(
            snapshot(
                listOf(
                    ScreenItem(
                        text = "",
                        kind = BubbleKind.IN,
                        y = 100,
                        xLeft = 100,
                        image = byteArrayOf(1, 2, 3),
                        imageWidth = 12,
                        imageHeight = 8,
                        imageSha256 = "image-a",
                    ),
                ),
            ),
            scopeKey = "contact-a",
        )!!

        assertEquals(IMAGE_PLACEHOLDER, result.lastMessageText)
        assertTrue(result.shouldRespond)
    }

    @Test
    fun `different images at the same position have different snapshot ids`() {
        fun image(sha256: String): ScreenItem {
            return ScreenItem(
                text = IMAGE_PLACEHOLDER,
                kind = BubbleKind.IN,
                y = 100,
                xLeft = 100,
                image = byteArrayOf(1, 2, 3),
                imageWidth = 640,
                imageHeight = 480,
                imageSha256 = sha256,
            )
        }

        val first = StartupConversationContextBuilder.build(
            snapshot(listOf(image("image-a"))),
            scopeKey = "contact-a",
        )!!
        val second = StartupConversationContextBuilder.build(
            snapshot(listOf(image("image-b"))),
            scopeKey = "contact-a",
        )!!

        assertNotEquals(first.snapshotId, second.snapshotId)
    }
}
