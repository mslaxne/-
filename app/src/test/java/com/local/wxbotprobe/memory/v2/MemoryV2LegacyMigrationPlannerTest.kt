package com.local.wxbotprobe.memory.v2

import com.local.wxbotprobe.bot.PersonaData
import com.local.wxbotprobe.chat.ChatIdentity
import com.local.wxbotprobe.memory.AgentSession
import com.local.wxbotprobe.memory.ChatMemoryStore
import com.local.wxbotprobe.memory.SessionMessage
import com.local.wxbotprobe.memory.SessionSummary
import com.local.wxbotprobe.memory.SummaryEvent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryV2LegacyMigrationPlannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `dry run imports sourced event and quarantines unsourced summaries`() {
        val store = ChatMemoryStore(File(temporaryFolder.root, "memory"))
        val now = 1_800_000_000_000L
        val entry = store.resolveChat(ChatIdentity("张三", 1L), now)
        var session = store.createSession(entry, now)
        session = store.appendMessage(
            session,
            SessionMessage(
                id = "m1",
                role = "user",
                text = "周六一起吃饭",
                timestamp = now,
                delivery = "received",
            ),
        )
        session = session.copy(
            status = AgentSession.STATUS_CLOSING,
            summaryStatus = "running",
            summaryRunId = "run-1",
        )
        store.saveSession(session)
        store.saveSummary(
            session,
            SessionSummary(
                summary = "聊了周末吃饭。",
                topics = listOf("周末"),
                events = listOf(
                    SummaryEvent(
                        type = "invitation",
                        text = "周六一起吃饭",
                        date = "",
                        status = "proposed",
                        importance = 0.8,
                        sourceMessageIds = listOf("m1"),
                    ),
                    SummaryEvent(
                        type = "fact",
                        text = "没有来源的旧事实",
                        date = "",
                        status = "pending",
                        importance = 0.5,
                        sourceMessageIds = emptyList(),
                    ),
                ),
            ),
            now,
        )

        val plan = MemoryV2LegacyMigrationPlanner(a2TtlSeconds = 30L * 86_400L)
            .plan(store, now) { chat ->
                ResolvedScopeInput(
                    key = MemoryScopeKey(
                        ownerId = "owner",
                        contactId = chat.chatId,
                        agentId = "agent",
                        personaId = "persona",
                        realmId = "real",
                    ),
                    personaRevision = MemoryV2PersonaRevision.of(
                        PersonaData("", emptyList()),
                    ),
                )
            }

        assertEquals(1, plan.contacts.size)
        assertEquals(1, plan.importable)
        assertEquals(2, plan.quarantined)
        assertEquals(0, plan.expired)
        assertEquals("m1", plan.contacts.single().items.first {
            it.state == "importable"
        }.sourceMessageId)
    }

    @Test
    fun `missing legacy source time is blocked instead of using import time`() {
        val store = ChatMemoryStore(File(temporaryFolder.root, "memory"))
        val now = 1_800_000_000_000L
        val entry = store.resolveChat(ChatIdentity("李四", 1L), now)
        var session = store.createSession(entry, now)
        session = store.appendMessage(
            session,
            SessionMessage(
                id = "m1",
                role = "user",
                text = "旧消息",
                timestamp = 0L,
                delivery = "received",
            ),
        )
        session = session.copy(
            status = AgentSession.STATUS_CLOSING,
            summaryStatus = "running",
            summaryRunId = "run-1",
        )
        store.saveSession(session)
        store.saveSummary(
            session,
            SessionSummary(
                summary = "旧摘要",
                topics = emptyList(),
                events = listOf(
                    SummaryEvent(
                        type = "fact",
                        text = "旧事实",
                        date = "",
                        status = "pending",
                        importance = 0.5,
                        sourceMessageIds = listOf("m1"),
                    ),
                ),
            ),
            now,
        )

        val plan = MemoryV2LegacyMigrationPlanner(a2TtlSeconds = 30L * 86_400L)
            .plan(store, now) { chat ->
                ResolvedScopeInput(
                    key = MemoryScopeKey(
                        ownerId = "owner",
                        contactId = chat.chatId,
                        agentId = "agent",
                        personaId = "persona",
                        realmId = "real",
                    ),
                    personaRevision = 1,
                )
            }

        assertEquals(0, plan.importable)
        assertEquals(1, plan.blocked)
        assertEquals("LEGACY_SOURCE_TIME_MISSING", plan.contacts.single().items.first {
            it.state == "blocked"
        }.reason)
    }
}
