package com.local.wxbotprobe.ocr

import com.local.wxbotprobe.chat.BubbleKind
import com.local.wxbotprobe.chat.ScreenItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditCandidateTrackerTest {
    private val chatA = 0x1234_5678_90ab_cdefL
    private val chatB = 0x2234_5678_90ab_cdefL

    @Test
    fun `candidate must appear in a second local audit`() {
        val tracker = AuditCandidateTracker()
        val candidate = candidate("新消息", BubbleKind.IN, 1_200)

        assertTrue(tracker.observeAudit(listOf(candidate), chatA, 1_000L).isEmpty())
        val confirmed = tracker.observeAudit(listOf(candidate), chatA, 2_000L)

        assertEquals(1, confirmed.size)
        assertEquals(
            AuditCandidateTracker.ConfirmationSource.LOCAL_AUDIT,
            confirmed.single().source,
        )
        assertEquals(chatA, confirmed.single().chatFingerprint)
        assertEquals(1_200, confirmed.single().y)
        assertEquals(3, confirmed.single().textLength)
        assertEquals(64, confirmed.single().key.length)
    }

    @Test
    fun `candidate survives a slow second local audit`() {
        val tracker = AuditCandidateTracker()
        val candidate = candidate("长消息候选", BubbleKind.OUT, 1_200)

        assertTrue(tracker.observeAudit(listOf(candidate), chatA, 1_000L).isEmpty())
        val confirmed = tracker.observeAudit(
            listOf(candidate),
            chatA,
            21_000L,
        )

        assertEquals(1, confirmed.size)
        assertEquals(
            AuditCandidateTracker.ConfirmationSource.LOCAL_AUDIT,
            confirmed.single().source,
        )
        assertEquals(2, confirmed.single().observations)
    }

    @Test
    fun `confirmation keeps raw text while matching normalised text`() {
        val tracker = AuditCandidateTracker()
        val candidate = candidate("你好，世界!", BubbleKind.IN, 1_200)

        tracker.observeAudit(listOf(candidate), chatA, 1_000L)
        val confirmed = tracker.observeAudit(
            listOf(candidate.copy(text = "你好 世界")),
            chatA,
            2_000L,
        )

        assertEquals(1, confirmed.size)
        assertEquals("你好，世界!", confirmed.single().text)
        assertEquals(6, confirmed.single().textLength)
    }

    @Test
    fun `full frame can confirm a pending local candidate`() {
        val tracker = AuditCandidateTracker()
        tracker.observeAudit(
            listOf(candidate("新消息", BubbleKind.IN, 1_200)),
            chatA,
            1_000L,
        )

        val confirmed = tracker.observeFullFrame(
            listOf(item("新消息", BubbleKind.IN, 1_220)),
            chatA,
            2_000L,
        )

        assertEquals(1, confirmed.size)
        assertEquals(
            AuditCandidateTracker.ConfirmationSource.FULL_FRAME,
            confirmed.single().source,
        )
    }

    @Test
    fun `different kind position or expired candidate does not confirm`() {
        val tracker = AuditCandidateTracker(maxAgeMillis = 5_000L)
        tracker.observeAudit(
            listOf(candidate("新消息", BubbleKind.IN, 1_200)),
            chatA,
            1_000L,
        )

        assertTrue(
            tracker.observeFullFrame(
                listOf(item("新消息", BubbleKind.OUT, 1_200)),
                chatA,
                2_000L,
            ).isEmpty(),
        )
        assertTrue(
            tracker.observeFullFrame(
                listOf(item("新消息", BubbleKind.IN, 1_500)),
                chatA,
                3_000L,
            ).isEmpty(),
        )
        assertTrue(
            tracker.observeFullFrame(
                listOf(item("新消息", BubbleKind.IN, 1_200)),
                chatA,
                7_000L,
            ).isEmpty(),
        )
    }

    @Test
    fun `candidate cannot be confirmed by another chat`() {
        val tracker = AuditCandidateTracker()
        val candidate = candidate("新消息", BubbleKind.IN, 1_200)
        tracker.observeAudit(listOf(candidate), chatA, 1_000L)

        assertTrue(
            tracker.observeFullFrame(
                listOf(item("新消息", BubbleKind.IN, 1_200)),
                chatB,
                2_000L,
            ).isEmpty(),
        )
        assertTrue(
            tracker.observeAudit(
                listOf(candidate),
                chatB,
                3_000L,
            ).isEmpty(),
        )

        val confirmedB = tracker.observeAudit(
            listOf(candidate),
            chatB,
            4_000L,
        )
        assertEquals(1, confirmedB.size)
        assertEquals(chatB, confirmedB.single().chatFingerprint)

        val confirmedA = tracker.observeAudit(
            listOf(candidate),
            chatA,
            5_000L,
        )
        assertEquals(1, confirmedA.size)
        assertEquals(chatA, confirmedA.single().chatFingerprint)
    }

    @Test
    fun `candidate key includes chat kind position and text`() {
        fun confirmedKey(
            text: String,
            chat: Long,
            kind: BubbleKind,
            y: Int,
        ): String {
            val tracker = AuditCandidateTracker()
            val candidate = candidate(text, kind, y)
            tracker.observeAudit(listOf(candidate), chat, 1_000L)
            return tracker.observeAudit(listOf(candidate), chat, 2_000L)
                .single()
                .key
        }

        val base = confirmedKey("新消息", chatA, BubbleKind.IN, 1_200)
        assertEquals(base, confirmedKey("新消息", chatA, BubbleKind.IN, 1_200))
        assertTrue(base != confirmedKey("另一条", chatA, BubbleKind.IN, 1_200))
        assertTrue(base != confirmedKey("新消息", chatB, BubbleKind.IN, 1_200))
        assertTrue(base != confirmedKey("新消息", chatA, BubbleKind.OUT, 1_200))
        assertTrue(base != confirmedKey("新消息", chatA, BubbleKind.IN, 1_300))
    }

    private fun candidate(
        text: String,
        kind: BubbleKind,
        y: Int,
    ): LocalAuditCandidate {
        return LocalAuditCandidate(
            text = text,
            kind = kind,
            y = y,
            xLeft = 300,
            score = 0.9,
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
            xLeft = 300,
        )
    }
}
