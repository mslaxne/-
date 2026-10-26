package com.local.wxbotprobe.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingConfirmationTrackerTest {
    @Test
    fun `new outgoing bubble confirms the expected token`() {
        val tracker = OutgoingConfirmationTracker()
        tracker.observe(42L, outgoing("旧消息"), 1_000L)
        tracker.expect("delivery:0", "delivery", 0, 42L, "新回复", 2_000L)

        val events = tracker.observe(
            42L,
            outgoing("旧消息", "新回复"),
            3_000L,
        )

        assertEquals(1, events.size)
        assertEquals(OutgoingConfirmationTracker.Outcome.CONFIRMED, events.single().outcome)
        assertEquals("delivery:0", events.single().token)
        assertEquals(1_000L, events.single().latencyMillis)
    }

    @Test
    fun `repeated text confirms only when a new occurrence appears`() {
        val tracker = OutgoingConfirmationTracker()
        tracker.observe(42L, outgoing("好"), 1_000L)
        tracker.expect("delivery:0", "delivery", 0, 42L, "好", 2_000L)

        assertTrue(tracker.observe(42L, outgoing("好"), 3_000L).isEmpty())
        val events = tracker.observe(42L, outgoing("好", "好"), 4_000L)

        assertEquals(OutgoingConfirmationTracker.Outcome.CONFIRMED, events.single().outcome)
    }

    @Test
    fun `one new identical bubble cannot confirm two pending sends`() {
        val tracker = OutgoingConfirmationTracker(timeoutMillis = 5_000L)
        tracker.observe(42L, outgoing("旧消息"), 1_000L)
        tracker.expect("delivery:0", "delivery", 0, 42L, "好", 2_000L)
        tracker.expect("delivery:1", "delivery", 1, 42L, "好", 2_100L)

        val events = tracker.observe(42L, outgoing("旧消息", "好"), 3_000L)

        assertEquals(1, events.size)
        assertEquals("delivery:0", events.single().token)
        assertTrue(
            tracker.observe(42L, outgoing("旧消息", "好"), 8_000L)
                .single()
                .outcome == OutgoingConfirmationTracker.Outcome.AMBIGUOUS,
        )
    }

    @Test
    fun `existing identical bubble times out as ambiguous`() {
        val tracker = OutgoingConfirmationTracker(timeoutMillis = 5_000L)
        tracker.observe(42L, outgoing("好"), 1_000L)
        tracker.expect("delivery:0", "delivery", 0, 42L, "好", 2_000L)

        assertTrue(tracker.observe(42L, outgoing("好"), 4_000L).isEmpty())
        val events = tracker.observe(42L, outgoing("好"), 7_000L)

        assertEquals(OutgoingConfirmationTracker.Outcome.AMBIGUOUS, events.single().outcome)
    }

    @Test
    fun `incoming bubble and other chat do not confirm outgoing text`() {
        val tracker = OutgoingConfirmationTracker(timeoutMillis = 5_000L)
        tracker.observe(42L, outgoing("旧消息"), 1_000L)
        tracker.expect("delivery:0", "delivery", 0, 42L, "新回复", 2_000L)

        assertTrue(
            tracker.observe(
                42L,
                listOf(item("新回复", BubbleKind.IN, 500)),
                3_000L,
            ).isEmpty(),
        )
        assertTrue(tracker.observe(99L, outgoing("新回复"), 4_000L).isEmpty())

        val events = tracker.observe(
            42L,
            listOf(item("新回复", BubbleKind.IN, 500)),
            7_000L,
        )
        assertEquals(
            OutgoingConfirmationTracker.Outcome.NOT_OBSERVED,
            events.single().outcome,
        )
    }

    @Test
    fun `long outgoing text tolerates OCR drift`() {
        val tracker = OutgoingConfirmationTracker()
        val expected = "今天下午三点开会记得带身份证和笔记本电脑还有充电器别忘了"
        val observed = "今天下午三点开会记得带身份证和笔记本还有充电器别忘了"
        tracker.observe(42L, outgoing("旧消息"), 1_000L)
        tracker.expect("delivery:0", "delivery", 0, 42L, expected, 2_000L)

        val events = tracker.observe(
            42L,
            outgoing("旧消息", observed),
            3_000L,
        )

        assertEquals(OutgoingConfirmationTracker.Outcome.CONFIRMED, events.single().outcome)
    }

    @Test
    fun `emoji-only outgoing text is ignored without blocking`() {
        val tracker = OutgoingConfirmationTracker()
        tracker.expect("delivery:0", "delivery", 0, 42L, "🙂", 2_000L)

        assertTrue(tracker.observe(42L, outgoing("🙂"), 3_000L).isEmpty())
    }

    private fun outgoing(vararg texts: String): List<ScreenItem> {
        return texts.mapIndexed { index, text ->
            item(text, BubbleKind.OUT, 300 + index * 120)
        }
    }

    private fun item(text: String, kind: BubbleKind, y: Int): ScreenItem {
        return ScreenItem(
            text = text,
            kind = kind,
            y = y,
            xLeft = 300,
        )
    }
}
