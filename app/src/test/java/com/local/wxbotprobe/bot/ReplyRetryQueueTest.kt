package com.local.wxbotprobe.bot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplyRetryQueueTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): ReplyRetryStore {
        return ReplyRetryStore(File(temporaryFolder.root, "reply-retries"))
    }

    private fun queued(
        store: ReplyRetryStore,
        fingerprint: Long = 42L,
    ): ReplyRetryTask {
        return store.enqueue(
            sourceFile = "/tmp/burst.json",
            chatTitle = "联系人",
            chatFingerprint = fingerprint,
            conversationKey = "chat",
            text = "在吗",
            isGroup = false,
            nowMillis = 1_000L,
        )
    }

    @Test
    fun `retry task persists across store recreation`() {
        val task = queued(store())
        val loaded = store().find(task.id)
        assertEquals(task, loaded)
    }

    @Test
    fun `new burst is immediately recoverable before its first generation`() {
        val task = queued(store())
        val recovered = store().nextReady(42L, 1_000L)
        assertEquals(task.id, recovered?.id)
        assertEquals(0, recovered?.attempts)
        assertEquals(ReplyRetryStatus.PENDING, recovered?.status)
    }

    @Test
    fun `retry backoff grows and stops at the attempt cap`() {
        var task = queued(store())
        val delays = listOf(5_000L, 10_000L, 20_000L, 40_000L)
        delays.forEachIndexed { index, expected ->
            task = task.recordFailure(1_000L, "failure")
            assertEquals(1_000L + expected, task.nextAttemptAt)
            assertEquals(index + 1, task.attempts)
            assertEquals(ReplyRetryStatus.PENDING, task.status)
        }
        task = task.recordFailure(1_000L, "final failure")
        assertEquals(ReplyRetryTask.MAX_ATTEMPTS, task.attempts)
        assertEquals(ReplyRetryStatus.FAILED, task.status)
        assertFalse(task.waiting)
    }

    @Test
    fun `retry waits for backoff and stays scoped to its chat`() {
        val retryStore = store()
        val task = queued(retryStore)
        assertEquals(task.id, retryStore.nextReady(42L, 1_000L)?.id)
        retryStore.save(task.recordFailure(1_000L, "network"))
        assertNull(retryStore.nextReady(99L, 7_000L))
        assertNull(retryStore.nextReady(42L, 5_999L))
        assertEquals(task.id, retryStore.nextReady(42L, 6_000L)?.id)
    }

    @Test
    fun `a ready newer retry can proceed while an older one waits for backoff`() {
        val retryStore = store()
        val first = queued(retryStore)
        retryStore.save(first.recordFailure(1_000L, "network"))
        val second = retryStore.enqueue(
            sourceFile = "/tmp/burst-2.json",
            chatTitle = "联系人",
            chatFingerprint = 42L,
            conversationKey = "chat",
            text = "第二条",
            isGroup = false,
            nowMillis = 2_000L,
        )
        assertEquals(second.id, retryStore.nextReady(42L, 3_000L)?.id)
        assertEquals(first.id, retryStore.nextReady(42L, 6_000L)?.id)
        retryStore.remove(first.id)
        assertEquals(second.id, retryStore.nextReady(42L, 6_000L)?.id)
    }

    @Test
    fun `removed retry disappears`() {
        val retryStore = store()
        val task = queued(retryStore)
        retryStore.remove(task.id)
        assertTrue(retryStore.all().isEmpty())
        assertNull(retryStore.find(task.id))
    }

    @Test
    fun `explicit stop clears every pending retry`() {
        val retryStore = store()
        queued(retryStore)
        retryStore.enqueue(
            sourceFile = "/tmp/burst-2.json",
            chatTitle = "联系人",
            chatFingerprint = 42L,
            conversationKey = "chat",
            text = "第二条",
            isGroup = false,
            nowMillis = 2_000L,
        )

        assertEquals(2, retryStore.removeAll())
        assertTrue(retryStore.all().isEmpty())
        assertEquals(0, retryStore.removeAll())
    }

    @Test
    fun `manual retry resets attempts and backoff`() {
        val retryStore = store()
        val task = queued(retryStore).recordFailure(1_000L, "network")
        retryStore.save(task)

        val retried = retryStore.manualRetry(task.id, 2_000L)!!

        assertEquals(0, retried.attempts)
        assertEquals(0L, retried.nextAttemptAt)
        assertEquals(ReplyRetryStatus.PENDING, retried.status)
        assertEquals("", retried.lastError)
        assertEquals(task.id, retryStore.nextReady(42L, 2_000L)?.id)
    }
}
