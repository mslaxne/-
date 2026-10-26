package com.local.wxbotprobe.ocr

import com.local.wxbotprobe.chat.BubbleKind
import com.local.wxbotprobe.chat.ChatIdentity
import com.local.wxbotprobe.chat.ScreenItem
import com.local.wxbotprobe.chat.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditCandidateMergerTest {
    private val chatA = ChatIdentity("文件传输助手", 0x1234_5678_90ab_cdefL)
    private val chatB = ChatIdentity("另一个聊天", 0x2234_5678_90ab_cdefL)

    @Test
    fun `local confirmation is merged once in y order`() {
        val merger = AuditCandidateMerger()
        val original = snapshot(
            chatA,
            listOf(
                item("上方消息", BubbleKind.OUT, 400),
                item("下方消息", BubbleKind.IN, 800),
            ),
        )
        merger.enqueue(
            listOf(confirmation(text = "补回消息", y = 600)),
            nowMillis = 1_000L,
        )

        val first = merger.merge(original, nowMillis = 2_000L)
        val second = merger.merge(first.snapshot, nowMillis = 3_000L)

        assertEquals(1, first.addedCount)
        assertEquals(listOf(400, 600, 800), first.snapshot.items.map { it.y })
        assertEquals("补回消息", first.snapshot.items[1].text)
        assertEquals(0, second.addedCount)
        assertEquals(3, second.snapshot.items.size)
    }

    @Test
    fun `full frame confirmation is not merged`() {
        val merger = AuditCandidateMerger()
        merger.enqueue(
            listOf(
                confirmation(
                    source = AuditCandidateTracker.ConfirmationSource.FULL_FRAME,
                ),
            ),
            nowMillis = 1_000L,
        )

        val result = merger.merge(snapshot(chatA, emptyList()), 2_000L)

        assertEquals(0, result.addedCount)
        assertTrue(result.snapshot.items.isEmpty())
    }

    @Test
    fun `existing item suppresses duplicate merge`() {
        val merger = AuditCandidateMerger()
        val original = snapshot(
            chatA,
            listOf(item("重复候选消息", BubbleKind.IN, 610)),
        )
        merger.enqueue(
            listOf(confirmation(text = "重复候选消息", y = 600)),
            nowMillis = 1_000L,
        )

        val result = merger.merge(original, 2_000L)

        assertEquals(0, result.addedCount)
        assertEquals(1, result.snapshot.items.size)
    }

    @Test
    fun `identical repeated message at another position is preserved`() {
        val merger = AuditCandidateMerger()
        val original = snapshot(
            chatA,
            listOf(item("重复消息", BubbleKind.IN, 300)),
        )
        merger.enqueue(
            listOf(confirmation(text = "重复消息", y = 800)),
            nowMillis = 1_000L,
        )

        val result = merger.merge(original, 2_000L)

        assertEquals(1, result.addedCount)
        assertEquals(2, result.snapshot.items.count { it.text == "重复消息" })
    }

    @Test
    fun `candidate from another chat is discarded`() {
        val merger = AuditCandidateMerger()
        merger.enqueue(
            listOf(confirmation(chatFingerprint = chatA.fingerprint)),
            nowMillis = 1_000L,
        )

        assertEquals(
            0,
            merger.merge(snapshot(chatB, emptyList()), 2_000L).addedCount,
        )
        assertEquals(0, merger.pendingCount())
        assertEquals(
            0,
            merger.merge(snapshot(chatA, emptyList()), 3_000L).addedCount,
        )
    }

    @Test
    fun `expired candidate is not merged`() {
        val merger = AuditCandidateMerger(maxAgeMillis = 5_000L)
        merger.enqueue(
            listOf(confirmation()),
            nowMillis = 1_000L,
        )

        val result = merger.merge(snapshot(chatA, emptyList()), 7_000L)

        assertEquals(0, result.addedCount)
        assertTrue(result.snapshot.items.isEmpty())
    }

    @Test
    fun `recently observed message suppresses its recovered fragments`() {
        val merger = AuditCandidateMerger()
        val fullText = "候选回复测试1745这是用于验证读屏补漏和单次回复链路的测试消息"
        val original = snapshot(
            chatA,
            listOf(item(fullText, BubbleKind.IN, 960)),
        )
        merger.merge(original, nowMillis = 1_000L)
        merger.enqueue(
            listOf(
                confirmation(
                    key = "fragment-1",
                    text = "候选回复测试1745这是用于验证读屏补漏和单",
                    y = 820,
                ),
                confirmation(
                    key = "fragment-2",
                    text = "次回复链路的测试消息",
                    y = 937,
                ),
            ),
            nowMillis = 2_000L,
        )

        val result = merger.merge(original, nowMillis = 3_000L)

        assertEquals(0, result.addedCount)
        assertEquals(1, result.snapshot.items.size)
    }

    @Test
    fun `unmatched fragments are not discarded by duplicate protection`() {
        val merger = AuditCandidateMerger()
        val original = snapshot(chatA, emptyList())
        merger.enqueue(
            listOf(
                confirmation(
                    key = "fragment-1",
                    text = "新的多行消息第一段",
                    y = 820,
                ),
                confirmation(
                    key = "fragment-2",
                    text = "新的多行消息第二段",
                    y = 937,
                ),
            ),
            nowMillis = 2_000L,
        )

        val result = merger.merge(original, nowMillis = 3_000L)

        assertEquals(2, result.addedCount)
        assertEquals(2, result.snapshot.items.size)
    }

    private fun confirmation(
        key: String = "candidate-key",
        chatFingerprint: Long = chatA.fingerprint,
        text: String = "补回消息",
        y: Int = 600,
        source: AuditCandidateTracker.ConfirmationSource =
            AuditCandidateTracker.ConfirmationSource.LOCAL_AUDIT,
    ): AuditCandidateTracker.Confirmation {
        return AuditCandidateTracker.Confirmation(
            key = key,
            chatFingerprint = chatFingerprint,
            text = text,
            kind = BubbleKind.IN,
            y = y,
            xLeft = 200,
            textLength = text.length,
            source = source,
            observations = 2,
        )
    }

    private fun snapshot(
        chat: ChatIdentity,
        items: List<ScreenItem>,
    ): ScreenSnapshot {
        return ScreenSnapshot(
            width = 1080,
            height = 2400,
            items = items,
            greyRows = emptyList(),
            isGroup = false,
            inputField = null,
            sendButtonY = null,
            ocrRowCount = 10,
            engine = "test",
            chat = chat,
        )
    }

    private fun item(
        text: String,
        kind: BubbleKind,
        y: Int,
    ): ScreenItem {
        return ScreenItem(
            text = text,
            kind = kind,
            y = y,
            xLeft = 200,
        )
    }
}
