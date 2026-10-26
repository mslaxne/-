package com.local.wxbotprobe.memory

import com.local.wxbotprobe.bot.ChatClient
import com.local.wxbotprobe.bot.ChatMessage
import com.local.wxbotprobe.bot.Completion
import com.local.wxbotprobe.chat.ChatIdentity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentMemoryManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class SummaryClient : ChatClient {
        var calls: Int = 0
            private set

        override fun chat(
            messages: List<ChatMessage>,
            temperature: Double,
            maxTokens: Int,
        ): Completion {
            calls++
            return Completion(
                """
                {
                  "summary": "聊了周末吃饭。",
                  "topics": ["周末"],
                  "events": [{
                    "type": "invitation",
                    "text": "周六一起吃饭",
                    "date": "2026-10-24",
                    "status": "proposed",
                    "importance": 0.9,
                    "sourceIds": []
                  }]
                }
                """.trimIndent(),
            )
        }
    }

    private class BlockingSummaryClient : ChatClient {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)

        override fun chat(
            messages: List<ChatMessage>,
            temperature: Double,
            maxTokens: Int,
        ): Completion {
            calls.incrementAndGet()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "summary test timed out" }
            return Completion(
                """
                {
                  "summary": "并发总结",
                  "topics": [],
                  "events": []
                }
                """.trimIndent(),
            )
        }
    }

    private fun store() = ChatMemoryStore(File(temporaryFolder.root, "memory"))

    @Test
    fun `closing a session writes summary and next open loads it`() {
        val memoryStore = store()
        val client = SummaryClient()
        val manager = AgentMemoryManager(memoryStore)
        manager.open(ChatIdentity("张三", 1L), 1_000L, client)
        manager.recordIncoming("周六一起吃饭吗", 1_100L)
        manager.recordDeliveredParts(listOf("可以，到时候看"), 1_200L)
        manager.close(2_000L, client)

        val recap = memoryStore.latestRecap()
        assertNotNull(recap)
        assertTrue(recap!!.contains("周六一起吃饭"))

        val reopened = AgentMemoryManager(memoryStore)
        val context = reopened.open(ChatIdentity("张三", 2L), 3_000L, client)
        assertTrue(context.note.contains("聊了周末吃饭"))
        assertTrue(context.note.contains("周六一起吃饭"))
    }

    @Test
    fun `switching chat detaches the previous session without waiting for summary`() {
        val memoryStore = store()
        val client = SummaryClient()
        val manager = AgentMemoryManager(memoryStore)
        manager.open(ChatIdentity("张三", 1L), 1_000L, client)
        manager.recordIncoming("你好", 1_100L)
        manager.open(ChatIdentity("李四", 2L), 2_000L, client)

        assertEquals(0, client.calls)
        val detached = memoryStore.awaitingSummary().single()
        assertEquals(AgentSession.STATUS_CLOSING, detached.status)
        assertEquals("pending", detached.summaryStatus)

        val outcome = manager.summarizeSession(detached.sessionId, 3_000L, client)
        assertTrue(outcome.completed)
        assertEquals(1, client.calls)
        val completed = memoryStore.loadSession(detached.sessionId)
        assertEquals(AgentSession.STATUS_CLOSED, completed?.status)
        assertEquals("complete", completed?.summaryStatus)
    }

    @Test
    fun `paused open session is reattached instead of summarized on next manager start`() {
        val memoryStore = store()
        val entry = memoryStore.resolveChat(ChatIdentity("张三", 1L), 1_000L)
        var session = memoryStore.createSession(entry, 1_000L)
        session = memoryStore.appendMessage(
            session,
            SessionMessage("m1", "user", "明天见", 1_100L, "received"),
        )

        val manager = AgentMemoryManager(memoryStore)
        manager.open(ChatIdentity("张三", 2L), 3_000L, SummaryClient())
        manager.recordIncoming("刚刚又想到一件事", 3_100L)

        val recovered = memoryStore.loadSession(session.sessionId)
        assertEquals(AgentSession.STATUS_OPEN, recovered?.status)
        assertEquals("pending", recovered?.summaryStatus)
        assertEquals(2, recovered?.messages?.size)
        assertTrue(memoryStore.awaitingSummary().isEmpty())
    }

    @Test
    fun `explicit stop summarizes a reattached open session`() {
        val memoryStore = store()
        val entry = memoryStore.resolveChat(ChatIdentity("张三", 1L), 1_000L)
        var session = memoryStore.createSession(entry, 1_000L)
        session = memoryStore.appendMessage(
            session,
            SessionMessage("m1", "user", "明天见", 1_100L, "received"),
        )

        val manager = AgentMemoryManager(memoryStore)
        manager.open(ChatIdentity("张三", 2L), 3_000L, SummaryClient())
        manager.close(4_000L, SummaryClient())

        val recovered = memoryStore.loadSession(session.sessionId)
        assertEquals(AgentSession.STATUS_CLOSED, recovered?.status)
        assertEquals("complete", recovered?.summaryStatus)
    }

    @Test
    fun `stop recovery works without an active session`() {
        val memoryStore = store()
        val entry = memoryStore.resolveChat(ChatIdentity("张三", 1L), 1_000L)
        var session = memoryStore.createSession(entry, 1_000L)
        session = memoryStore.appendMessage(
            session,
            SessionMessage("m1", "user", "周六见", 1_100L, "received"),
        )

        val manager = AgentMemoryManager(memoryStore)
        manager.recoverOpenSessions(3_000L, SummaryClient())

        val recovered = memoryStore.loadSession(session.sessionId)
        assertEquals(AgentSession.STATUS_CLOSED, recovered?.status)
        assertEquals("complete", recovered?.summaryStatus)
    }

    @Test
    fun `opening a chat prunes with that chat's own retention setting`() {
        val memoryStore = store()
        val entry = memoryStore.resolveChat(ChatIdentity("张三", 1L), 1_000L)
        val session = memoryStore.createSession(entry, 1_000L)
        memoryStore.saveSummary(
            session,
            SessionSummary("旧总结", emptyList(), emptyList()),
            2_000L,
        )
        val cutoff = 2_000L + 2 * 86_400_000L
        memoryStore.setRetentionDays(entry.chatId, 1, cutoff)

        AgentMemoryManager(memoryStore).open(
            ChatIdentity("张三", 2L),
            cutoff,
            SummaryClient(),
        )

        val updated = memoryStore.allChats().single()
        assertTrue(memoryStore.loadMemory(updated).sessions.isEmpty())
        assertEquals(1, updated.retentionDays)
    }

    @Test
    fun `concurrent summary claim only calls the model once`() {
        val memoryStore = store()
        var session = memoryStore.createSession(
            memoryStore.resolveChat(ChatIdentity("张三", 1L), 1_000L),
            1_000L,
        )
        session = memoryStore.appendMessage(
            session,
            SessionMessage("m1", "user", "周六见", 1_100L, "received"),
        )
        val client = BlockingSummaryClient()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<MemorySummaryOutcome> {
                AgentMemoryManager(memoryStore).summarizeSession(
                    session.sessionId,
                    2_000L,
                    client,
                )
            }
            assertTrue(client.started.await(5, TimeUnit.SECONDS))

            val second = AgentMemoryManager(memoryStore).summarizeSession(
                session.sessionId,
                2_100L,
                client,
            )
            assertTrue(second.skipped)
            assertEquals(1, client.calls.get())

            client.release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS).completed)
        } finally {
            client.release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stale running summary is reclaimed after process restart`() {
        val memoryStore = store()
        val entry = memoryStore.resolveChat(ChatIdentity("张三", 1L), 1_000L)
        var session = memoryStore.createSession(entry, 1_000L)
        session = memoryStore.appendMessage(
            session,
            SessionMessage("m1", "user", "周六见", 1_100L, "received"),
        )
        memoryStore.saveSession(
            session.copy(
                closedAt = 2_000L,
                status = AgentSession.STATUS_CLOSING,
                summaryStatus = "running",
                summaryRunId = "previous-process",
            ),
        )
        val client = SummaryClient()

        val outcome = AgentMemoryManager(memoryStore).summarizeSession(
            session.sessionId,
            3_000L,
            client,
        )

        assertTrue(outcome.completed)
        assertEquals(1, client.calls)
        assertEquals("complete", memoryStore.loadSession(session.sessionId)?.summaryStatus)
    }

    @Test
    fun `delete during summary does not resurrect memory`() {
        val memoryStore = store()
        val entry = memoryStore.resolveChat(ChatIdentity("张三", 1L), 1_000L)
        var session = memoryStore.createSession(entry, 1_000L)
        session = memoryStore.appendMessage(
            session,
            SessionMessage("m1", "user", "周六见", 1_100L, "received"),
        )
        val client = BlockingSummaryClient()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<MemorySummaryOutcome> {
                AgentMemoryManager(memoryStore).summarizeSession(
                    session.sessionId,
                    2_000L,
                    client,
                )
            }
            assertTrue(client.started.await(5, TimeUnit.SECONDS))

            assertTrue(memoryStore.deleteChat(entry.chatId))
            client.release.countDown()
            assertFalse(future.get(5, TimeUnit.SECONDS).completed)

            assertTrue(memoryStore.allChats().isEmpty())
            assertNull(memoryStore.loadSession(session.sessionId))
        } finally {
            client.release.countDown()
            executor.shutdownNow()
        }
    }
}
