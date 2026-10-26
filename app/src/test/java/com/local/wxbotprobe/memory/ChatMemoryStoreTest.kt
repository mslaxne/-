package com.local.wxbotprobe.memory

import com.local.wxbotprobe.chat.ChatIdentity
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatMemoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store(): ChatMemoryStore {
        return ChatMemoryStore(File(temporaryFolder.root, "memory"))
    }

    private fun identity(title: String = "文件传输助手") = ChatIdentity(title, 123L)

    private fun claimed(
        store: ChatMemoryStore,
        session: AgentSession,
    ): AgentSession {
        val running = session.copy(
            status = AgentSession.STATUS_CLOSING,
            summaryStatus = "running",
            summaryRunId = "test-run",
        )
        store.saveSession(running)
        return running
    }

    private fun addSummary(
        store: ChatMemoryStore,
        entry: ChatIndexEntry,
        text: String,
        nowMillis: Long,
    ): AgentSession {
        val session = claimed(store, store.createSession(entry, nowMillis))
        store.saveSummary(
            session,
            SessionSummary(
                summary = text,
                topics = emptyList(),
                events = emptyList(),
            ),
            nowMillis,
        )
        return session
    }

    @Test
    fun `same normalized title keeps the same chat uuid`() {
        val first = store().resolveChat(identity(), nowMillis = 1_000L)
        val second = store().resolveChat(identity("文件 传输 助手"), nowMillis = 2_000L)
        assertEquals(first.chatId, second.chatId)
    }

    @Test
    fun `session messages survive a store reload`() {
        val first = store()
        val entry = first.resolveChat(identity())
        var session = first.createSession(entry, 1_000L)
        session = first.appendMessage(
            session,
            SessionMessage("m1", "user", "周六一起吃饭吗", 1_100L, "received"),
        )
        val reloaded = store().loadSession(session.sessionId)
        assertEquals("周六一起吃饭吗", reloaded?.messages?.single()?.text)
    }

    @Test
    fun `ttl removes internal sessions but keeps future events and display recaps`() {
        val store = store()
        val entry = store.resolveChat(identity())
        val session = claimed(store, store.createSession(entry, 1_000L))
        val summary = SessionSummary(
            summary = "聊了周六吃饭",
            topics = listOf("吃饭"),
            events = listOf(
                SummaryEvent(
                    type = "invitation",
                    text = "周六吃饭",
                    date = "2026-10-24",
                    status = "proposed",
                    importance = 0.9,
                    sourceMessageIds = listOf("m1"),
                ),
            ),
        )
        val memory = store.saveSummary(session, summary, 1_000L)!!
        assertNotNull(store.latestRecap())

        val pruned = store.prune(
            memory,
            1,
            nowMillis = 1_000L + 2 * 86_400_000L,
        )
        assertTrue(pruned.sessions.isEmpty())
        assertEquals(1, pruned.events.size)
        assertEquals("2026-10-24", pruned.events.single().date)
        assertNull(store.loadSession(session.sessionId))
        assertNotNull(store.latestRecap())
        assertEquals(1, store.recapsFor(entry, nowMillis = 1_000L + 2 * DAY).size)
    }

    @Test
    fun `permanent retention keeps old events`() {
        val store = store()
        val entry = store.resolveChat(identity())
        val session = claimed(store, store.createSession(entry, 1_000L))
        val memory = store.saveSummary(
            session,
            SessionSummary(
                summary = "旧事",
                topics = emptyList(),
                events = listOf(
                    SummaryEvent("fact", "记住这件事", "", "pending", 0.8, emptyList()),
                ),
            ),
            1_000L,
        )!!
        val kept = store.prune(memory, MemorySettings.PERMANENT_DAYS, 10_000_000L)
        assertEquals(1, kept.events.size)
    }

    @Test
    fun `duplicate event in a later session updates instead of duplicating`() {
        val store = store()
        val entry = store.resolveChat(identity())
        val first = claimed(store, store.createSession(entry, 1_000L))
        store.saveSummary(
            first,
            SessionSummary(
                "第一次",
                listOf("邀约"),
                listOf(SummaryEvent("invitation", "周六吃饭", "", "proposed", 0.7, emptyList())),
            ),
            2_000L,
        )
        val second = claimed(store, store.createSession(entry, 3_000L))
        val secondMemory = store.saveSummary(
            second,
            SessionSummary(
                "第二次",
                listOf("邀约"),
                listOf(SummaryEvent("invitation", "周六吃饭", "", "accepted", 0.9, emptyList())),
            ),
            4_000L,
        )!!
        assertEquals(1, secondMemory.events.size)
        assertEquals("accepted", secondMemory.events.single().status)
        assertEquals(0.9, secondMemory.events.single().importance, 0.001)
    }

    @Test
    fun `clear all removes the index and chat files`() {
        val store = store()
        store.resolveChat(identity())
        store.clearAll()
        assertTrue(store.allChats().isEmpty())
    }

    @Test
    fun `retention is stored and changed per chat`() {
        val store = store()
        val first = store.resolveChat(identity("张三"), 1_000L)
        val second = store.resolveChat(identity("李四"), 2_000L)

        store.setRetentionDays(first.chatId, 1, 3_000L)

        val reloaded = store().allChats().associateBy { it.chatId }
        assertEquals(1, reloaded.getValue(first.chatId).retentionDays)
        assertEquals(
            MemorySettings.DEFAULT_RETENTION_DAYS,
            reloaded.getValue(second.chatId).retentionDays,
        )
    }

    @Test
    fun `pruning one chat does not remove another chat history`() {
        val store = store()
        val first = store.resolveChat(identity("张三"), 1_000L)
        val second = store.resolveChat(identity("李四"), 1_000L)
        addSummary(store, first, "张三的旧事", 2_000L)
        addSummary(store, second, "李四的旧事", 2_000L)
        val cutoff = 2_000L + 2 * 86_400_000L

        store.setRetentionDays(first.chatId, 1, cutoff)
        val firstEntry = store.allChats().first { it.chatId == first.chatId }
        val secondEntry = store.allChats().first { it.chatId == second.chatId }
        val prunedFirst = store.prune(
            store.loadMemory(firstEntry),
            firstEntry.retentionDays,
            cutoff,
        )
        store.saveMemory(prunedFirst)

        assertTrue(store.loadMemory(firstEntry).sessions.isEmpty())
        assertEquals(1, store.loadMemory(secondEntry).sessions.size)
    }

    @Test
    fun `deleting one chat removes its memory but keeps other contacts`() {
        val store = store()
        val first = store.resolveChat(identity("张三"), 1_000L)
        val second = store.resolveChat(identity("李四"), 1_000L)
        val firstSession = addSummary(store, first, "张三", 2_000L)
        val secondSession = addSummary(store, second, "李四", 2_000L)

        assertTrue(store.deleteChat(first.chatId))

        assertEquals(listOf(second.chatId), store.allChats().map { it.chatId })
        assertNull(store.loadSession(firstSession.sessionId))
        assertNotNull(store.loadSession(secondSession.sessionId))
        assertTrue(store.latestRecap()!!.contains("李四"))
    }

    @Test
    fun `old index entries without a retention field migrate to the default`() {
        val memoryRoot = File(temporaryFolder.root, "memory")
        memoryRoot.mkdirs()
        File(memoryRoot, "index.json").writeText(
            """
            {
              "version": 1,
              "chats": {
                "张三": {
                  "chatId": "chat-1",
                  "displayName": "张三",
                  "file": "chats/chat-1.json",
                  "aliases": ["张三"],
                  "updatedAt": 1000
                }
              }
            }
            """.trimIndent(),
        )

        val entry = ChatMemoryStore(memoryRoot).allChats().single()

        assertEquals(MemorySettings.DEFAULT_RETENTION_DAYS, entry.retentionDays)
        assertEquals(
            MemorySettings.DEFAULT_SUMMARY_RETENTION_MODE,
            entry.summaryRetentionMode,
        )
        assertEquals(
            MemorySettings.DEFAULT_SUMMARY_RETENTION_VALUE,
            entry.summaryRetentionValue,
        )
    }

    @Test
    fun `summary retention count is per contact`() {
        val store = store()
        val first = store.resolveChat(identity("张三"), 1_000L)
        val second = store.resolveChat(identity("李四"), 1_000L)
        repeat(3) { index ->
            val session = claimed(
                store,
                store.createSession(first, 2_000L + index),
            )
            store.saveSummary(
                session,
                SessionSummary("张三 $index", emptyList(), emptyList()),
                3_000L + index,
            )
        }
        val secondSession = claimed(store, store.createSession(second, 2_000L))
        store.saveSummary(
            secondSession,
            SessionSummary("李四", emptyList(), emptyList()),
            3_000L,
        )

        store.setSummaryRetention(
            first.chatId,
            MemorySettings.SUMMARY_MODE_COUNT,
            1,
            4_000L,
        )

        assertEquals(
            1,
            store.recapsFor(
                store.allChats().first { it.chatId == first.chatId },
                nowMillis = 4_000L,
            ).size,
        )
        assertEquals(
            1,
            store.recapsFor(
                store.allChats().first { it.chatId == second.chatId },
                nowMillis = 4_000L,
            ).size,
        )
        val updated = store.allChats().first { it.chatId == first.chatId }
        assertEquals(MemorySettings.SUMMARY_MODE_COUNT, updated.summaryRetentionMode)
        assertEquals(1, updated.summaryRetentionValue)
    }

    @Test
    fun `recap archive is stored under its contact`() {
        val store = store()
        val entry = store.resolveChat(identity("张三"), 1_000L)
        val session = claimed(store, store.createSession(entry, 2_000L))
        store.saveSummary(
            session,
            SessionSummary("独立总结", emptyList(), emptyList()),
            3_000L,
        )

        val recap = File(
            temporaryFolder.root,
            "memory/recaps/${entry.chatId}/${session.sessionId}.md",
        )
        assertTrue(recap.isFile)
        assertTrue(
            store.recapsFor(entry, nowMillis = 4_000L)
                .single()
                .text
                .contains("独立总结"),
        )
    }

    @Test
    fun `summary retention by days removes only old display recaps`() {
        val store = store()
        val entry = store.resolveChat(identity("张三"), 1_000L)
        val oldSession = claimed(store, store.createSession(entry, 1_000L))
        store.saveSummary(
            oldSession,
            SessionSummary("旧总结", emptyList(), emptyList()),
            2_000L,
        )
        val newSession = claimed(store, store.createSession(entry, 2 * DAY))
        store.saveSummary(
            newSession,
            SessionSummary("新总结", emptyList(), emptyList()),
            2 * DAY + 1_000L,
        )

        store.setSummaryRetention(
            entry.chatId,
            MemorySettings.SUMMARY_MODE_DAYS,
            1,
            3 * DAY,
        )

        val recaps = store.recapsFor(
            store.allChats().first { it.chatId == entry.chatId },
            nowMillis = 3 * DAY,
        )
        assertEquals(1, recaps.size)
        assertTrue(recaps.single().text.contains("新总结"))
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
