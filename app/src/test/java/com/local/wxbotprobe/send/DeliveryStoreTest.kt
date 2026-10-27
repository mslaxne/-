package com.local.wxbotprobe.send

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeliveryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): DeliveryStore {
        return DeliveryStore(File(temporaryFolder.root, "outbox"))
    }

    private fun queued(store: DeliveryStore, fingerprint: Long = 42L): Delivery {
        return store.enqueue(
            sourceReply = "一\n二\n三",
            sourceReplyFile = "/tmp/reply.json",
            chatFingerprint = fingerprint,
            conversationKey = "chat-$fingerprint",
            parts = listOf("一", "二", "三"),
            nowMillis = 1_000L,
        )
    }

    @Test
    fun `stable delivery id is reused without creating a duplicate`() {
        val store = store()
        val first = store.enqueue(
            sourceReply = "回复",
            sourceReplyFile = "/tmp/reply.json",
            chatFingerprint = 42L,
            conversationKey = "chat-42",
            parts = listOf("回复"),
            deliveryId = "generation-1",
            nowMillis = 1_000L,
        )
        val second = store.enqueue(
            sourceReply = "回复",
            sourceReplyFile = "/tmp/reply.json",
            chatFingerprint = 42L,
            conversationKey = "chat-42",
            parts = listOf("回复"),
            deliveryId = "generation-1",
            nowMillis = 2_000L,
        )

        assertEquals(first.id, second.id)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `partial progress survives a store reload`() {
        val first = store()
        val delivery = queued(first)
        first.save(delivery.recordPartSent(0, 1_100L))

        val reloaded = store().nextReady(42L, 1_200L)
        assertNotNull(reloaded)
        assertEquals(1, reloaded!!.nextPartIndex)
        assertEquals(DeliveryStatus.PARTIAL, reloaded.status)
    }

    @Test
    fun `retry waits for backoff and keeps the unsent index`() {
        val store = store()
        val delivery = queued(store)
        val failed = delivery.recordFailure(1, "second bubble failed", 2_000L)
        store.save(failed)

        assertNull(store.nextReady(42L, 2_100L))
        val ready = store.nextReady(42L, failed.nextAttemptAt)
        assertNotNull(ready)
        assertEquals(1, ready!!.nextPartIndex)
        assertEquals(DeliveryStatus.PARTIAL, ready.status)
    }

    @Test
    fun `a delivery is only ready in its original chat`() {
        val store = store()
        queued(store, fingerprint = 42L)
        assertNull(store.nextReady(99L, 10_000L))
        assertNotNull(store.nextReady(42L, 10_000L))
    }

    @Test
    fun `a ready newer delivery can proceed while an older one waits for backoff`() {
        val store = store()
        val first = queued(store)
        store.save(first.recordFailure(0, "temporary", 2_000L))
        val second = store.enqueue(
            sourceReply = "新回复",
            sourceReplyFile = "/tmp/newer.json",
            chatFingerprint = 42L,
            conversationKey = "chat-42",
            parts = listOf("新回复"),
            nowMillis = 3_000L,
        )

        assertEquals(second.id, store.nextReady(42L, 3_100L)?.id)
        assertEquals(first.id, store.nextReady(42L, first.recordFailure(0, "", 2_000L).nextAttemptAt)?.id)
    }

    @Test
    fun `completed delivery leaves the retry queue`() {
        val store = store()
        val delivery = queued(store)
        val complete = delivery.recordPartSent(0, 1_100L)
            .recordPartSent(1, 1_200L)
            .recordPartSent(2, 1_300L)
        store.save(complete)

        assertEquals(DeliveryStatus.SENT, complete.status)
        assertNull(store.nextReady(42L, 10_000L))
    }

    @Test
    fun `delivery stops retrying after the attempt cap`() {
        var delivery = queued(store())
        repeat(Delivery.MAX_ATTEMPTS) {
            delivery = delivery.recordFailure(0, "still failing", 2_000L)
        }
        assertEquals(DeliveryStatus.FAILED, delivery.status)
        assertFalse(delivery.waiting)
        assertTrue(delivery.attempts == Delivery.MAX_ATTEMPTS)
    }

    @Test
    fun `manual retry resets a failed delivery without losing progress`() {
        val store = store()
        val delivery = queued(store)
            .recordPartSent(0, 1_100L)
            .recordFailure(1, "failed", 2_000L)
            .copy(status = DeliveryStatus.FAILED, attempts = Delivery.MAX_ATTEMPTS)
        store.save(delivery)

        val retried = store.manualRetry(delivery.id, 3_000L)!!

        assertEquals(DeliveryStatus.PARTIAL, retried.status)
        assertEquals(1, retried.nextPartIndex)
        assertEquals(0, retried.attempts)
        assertEquals(0L, retried.nextAttemptAt)
        assertEquals("", retried.lastError)
    }

    @Test
    fun `delivery can be removed by the operator`() {
        val store = store()
        val delivery = queued(store)
        assertTrue(store.remove(delivery.id))
        assertNull(store.find(delivery.id))
    }

    @Test
    fun `waiting delivery can be retargeted to a changed chat fingerprint`() {
        val store = store()
        val delivery = queued(store, fingerprint = 42L)

        val updated = store.retarget(
            id = delivery.id,
            chatFingerprint = 99L,
            conversationKey = "chat-99",
            nowMillis = 2_000L,
        )!!

        assertEquals(99L, updated.chatFingerprint)
        assertNull(store.nextReady(42L, 2_000L))
        assertEquals(delivery.id, store.nextReady(99L, 2_000L)?.id)
    }

    @Test
    fun `interrupted send from a previous process requires review`() {
        val store = store()
        val delivery = queued(store)
        store.save(delivery.copy(sendInFlight = true, sendRunId = "old-process"))

        assertEquals(1, store.recoverInterruptedSends("new-process", 2_000L))

        val recovered = store.find(delivery.id)!!
        assertEquals(DeliveryStatus.UNKNOWN, recovered.status)
        assertFalse(recovered.sendInFlight)
        assertEquals("", recovered.sendRunId)
        assertNull(store.nextReady(42L, 10_000L))
    }

    @Test
    fun `send marker from the current process remains active`() {
        val store = store()
        val delivery = queued(store)
        store.save(delivery.copy(sendInFlight = true, sendRunId = "current-process"))

        assertEquals(0, store.recoverInterruptedSends("current-process", 2_000L))
        assertEquals(DeliveryStatus.PENDING, store.find(delivery.id)?.status)
        assertTrue(store.nextReady(42L, 2_000L) != null)
    }

    @Test
    fun `unknown send requires explicit operator acknowledgement to retry`() {
        val store = store()
        val delivery = queued(store)
        store.save(delivery.copy(sendInFlight = true, sendRunId = "old-process"))
        store.recoverInterruptedSends("new-process", 2_000L)

        assertNull(store.manualRetry(delivery.id, 3_000L))
        assertEquals(DeliveryStatus.UNKNOWN, store.find(delivery.id)?.status)

        val retried = store.manualRetry(
            delivery.id,
            nowMillis = 4_000L,
            acknowledgeUnknownResult = true,
        )
        assertNotNull(retried)
        assertEquals(DeliveryStatus.PENDING, retried?.status)
        assertFalse(retried?.sendInFlight ?: true)
        assertNotNull(store.nextReady(42L, 4_000L))
    }

    @Test
    fun `ambiguous send after tapping does not become an automatic retry`() {
        val store = store()
        val delivery = queued(store)
            .recordPartSent(0, 1_100L)
            .copy(sendInFlight = true, sendRunId = "current-process")
        store.save(delivery)

        val unknown = delivery.markSendResultUnknown("confirmation failed", 2_000L)
        store.save(unknown)

        assertEquals(DeliveryStatus.UNKNOWN, unknown.status)
        assertEquals(1, unknown.nextPartIndex)
        assertEquals(0, unknown.attempts)
        assertFalse(unknown.sendInFlight)
        assertNull(store.nextReady(42L, 10_000L))
    }

    @Test
    fun `operator can confirm an unknown part was sent`() {
        val store = store()
        val delivery = queued(store)
            .copy(status = DeliveryStatus.UNKNOWN)
        store.save(delivery)

        val confirmed = delivery.confirmUnknownPartSent(3_000L)
        store.save(confirmed)

        assertEquals(DeliveryStatus.PARTIAL, confirmed.status)
        assertEquals(1, confirmed.nextPartIndex)
        assertFalse(confirmed.sendInFlight)
        assertNotNull(store.nextReady(42L, 3_000L))
    }

    @Test
    fun `operator confirmation of the final unknown part completes the delivery`() {
        val store = store()
        val delivery = queued(store)
            .copy(
                nextPartIndex = 2,
                status = DeliveryStatus.UNKNOWN,
            )

        val confirmed = delivery.confirmUnknownPartSent(3_000L)
        store.save(confirmed)

        assertEquals(DeliveryStatus.SENT, confirmed.status)
        assertEquals(3, confirmed.nextPartIndex)
        assertNull(store.nextReady(42L, 3_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-unknown delivery cannot be confirmed as sent`() {
        queued(store()).confirmUnknownPartSent(3_000L)
    }
}
