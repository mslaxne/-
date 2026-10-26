package com.local.wxbotprobe.ocr

import com.local.wxbotprobe.chat.BubbleKind
import com.local.wxbotprobe.chat.ChatIdentity
import com.local.wxbotprobe.chat.LiveChatState
import com.local.wxbotprobe.chat.ScreenItem
import com.local.wxbotprobe.chat.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditCandidateLiveStateTest {
    private val chat = ChatIdentity("测试聊天", 0x1234_5678_90ab_cdefL)

    @Test
    fun `confirmed incoming candidate produces one burst`() {
        val merger = AuditCandidateMerger()
        val live = LiveChatState(
            burstWaitMillis = 3_000L,
            baselineObservations = 1,
        )
        val baseline = snapshot(
            listOf(item("已有消息", BubbleKind.OUT, 300)),
        )
        assertNull(observe(live, baseline, 1_000L))

        merger.enqueue(
            listOf(
                confirmation(
                    key = "incoming-candidate",
                    text = "补漏的新消息",
                    kind = BubbleKind.IN,
                    y = 600,
                ),
            ),
            nowMillis = 2_000L,
        )
        val merged = merger.merge(baseline, 3_000L)

        assertEquals(1, merged.addedCount)
        assertTrue(merged.snapshot.items.any { it.text == "补漏的新消息" })
        assertNull(observe(live, merged.snapshot, 3_000L))
        val completed = observe(live, merged.snapshot, 6_000L)
        assertEquals(
            listOf("补漏的新消息"),
            completed?.items?.map { it.text },
        )
        assertNull(observe(live, merged.snapshot, 9_000L))
    }

    @Test
    fun `duplicate incoming candidate does not create a second burst`() {
        val merger = AuditCandidateMerger()
        val live = LiveChatState(
            burstWaitMillis = 3_000L,
            baselineObservations = 1,
        )
        val baseline = snapshot(
            listOf(item("已有消息", BubbleKind.OUT, 300)),
        )
        assertNull(observe(live, baseline, 1_000L))

        val candidate = confirmation(
            key = "incoming-candidate",
            text = "补漏的新消息",
            kind = BubbleKind.IN,
            y = 600,
        )
        merger.enqueue(listOf(candidate), nowMillis = 2_000L)
        val merged = merger.merge(baseline, 3_000L).snapshot
        assertNull(observe(live, merged, 3_000L))
        assertEquals("补漏的新消息", observe(live, merged, 6_000L)?.items?.single()?.text)

        val queued = merger.enqueue(listOf(candidate), nowMillis = 7_000L)
        val repeated = merger.merge(merged, 8_000L)

        assertEquals(0, queued)
        assertEquals(0, repeated.addedCount)
        assertNull(observe(live, repeated.snapshot, 9_000L))
    }

    private fun observe(
        live: LiveChatState,
        snapshot: ScreenSnapshot,
        nowMillis: Long,
    ) = live.observe(
        items = snapshot.items,
        isGroup = snapshot.isGroup,
        nowMillis = nowMillis,
        chatFingerprint = snapshot.chat.fingerprint,
    )

    private fun confirmation(
        key: String,
        text: String,
        kind: BubbleKind,
        y: Int,
    ): AuditCandidateTracker.Confirmation {
        return AuditCandidateTracker.Confirmation(
            key = key,
            chatFingerprint = chat.fingerprint,
            text = text,
            kind = kind,
            y = y,
            xLeft = 200,
            textLength = text.length,
            source = AuditCandidateTracker.ConfirmationSource.LOCAL_AUDIT,
            observations = 2,
        )
    }

    private fun snapshot(items: List<ScreenItem>): ScreenSnapshot {
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
