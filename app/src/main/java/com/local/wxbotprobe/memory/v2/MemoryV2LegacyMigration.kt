package com.local.wxbotprobe.memory.v2

import com.local.wxbotprobe.chat.TextRules
import com.local.wxbotprobe.memory.ChatIndexEntry
import com.local.wxbotprobe.memory.ChatMemoryStore

data class LegacyMigrationItem(
    val legacyId: String,
    val kind: String,
    val state: String,
    val reason: String,
    val tier: String? = null,
    val sourceMessageId: String? = null,
    val sourceReceivedAt: Long? = null,
)

data class LegacyMigrationContactPlan(
    val contactId: String,
    val scopeKey: MemoryScopeKey,
    val items: List<LegacyMigrationItem>,
)

data class LegacyMigrationPlan(
    val contacts: List<LegacyMigrationContactPlan>,
    val totalItems: Int,
    val importable: Int,
    val expired: Int,
    val quarantined: Int,
    val blocked: Int,
)

class MemoryV2LegacyMigrationPlanner(
    private val a2TtlSeconds: Long,
    private val maxQuoteUtf8Bytes: Int = 2_048,
) {
    fun plan(
        store: ChatMemoryStore,
        nowMillis: Long,
        resolveScope: (ChatIndexEntry) -> ResolvedScopeInput,
    ): LegacyMigrationPlan {
        val contacts = store.allChats().map { entry ->
            val resolved = resolveScope(entry)
            val memory = store.loadMemory(entry)
            val items = mutableListOf<LegacyMigrationItem>()
            memory.sessions.forEach { digest ->
                items += LegacyMigrationItem(
                    legacyId = "session:${digest.sessionId}",
                    kind = "session_summary",
                    state = "quarantined",
                    reason = "LEGACY_EVIDENCE_MISSING",
                )
            }
            val seen = mutableSetOf<String>()
            memory.events.forEach { event ->
                val key = TextRules.normalise(event.text) +
                    "|" + event.sourceMessageIds.sorted().joinToString(",")
                if (!seen.add(key)) {
                    items += LegacyMigrationItem(
                        legacyId = "event:${event.id}",
                        kind = "event",
                        state = "quarantined",
                        reason = "LEGACY_DUPLICATE",
                    )
                    return@forEach
                }
                if (event.sourceMessageIds.isEmpty()) {
                    items += LegacyMigrationItem(
                        legacyId = "event:${event.id}",
                        kind = "event",
                        state = "quarantined",
                        reason = "LEGACY_EVIDENCE_MISSING",
                    )
                    return@forEach
                }
                val messages = store.sessionMessages(event.sourceSessionId)
                val source = event.sourceMessageIds.asSequence()
                    .mapNotNull { id -> messages.firstOrNull { it.id == id } }
                    .firstOrNull()
                if (source == null || source.text.isBlank()) {
                    items += LegacyMigrationItem(
                        legacyId = "event:${event.id}",
                        kind = "event",
                        state = "quarantined",
                        reason = "LEGACY_SOURCE_UNAVAILABLE",
                        sourceMessageId = event.sourceMessageIds.firstOrNull(),
                    )
                    return@forEach
                }
                if (source.timestamp <= 0L) {
                    items += LegacyMigrationItem(
                        legacyId = "event:${event.id}",
                        kind = "event",
                        state = "blocked",
                        reason = "LEGACY_SOURCE_TIME_MISSING",
                        sourceMessageId = source.id,
                    )
                    return@forEach
                }
                if (event.text.isBlank()) {
                    items += LegacyMigrationItem(
                        legacyId = "event:${event.id}",
                        kind = "event",
                        state = "quarantined",
                        reason = "LEGACY_EVENT_EMPTY",
                        sourceMessageId = source.id,
                        sourceReceivedAt = source.timestamp,
                    )
                    return@forEach
                }
                if (source.text.toByteArray(Charsets.UTF_8).size > maxQuoteUtf8Bytes) {
                    items += LegacyMigrationItem(
                        legacyId = "event:${event.id}",
                        kind = "event",
                        state = "quarantined",
                        reason = "LEGACY_QUOTE_TOO_LARGE",
                        sourceMessageId = source.id,
                        sourceReceivedAt = source.timestamp,
                    )
                    return@forEach
                }
                val expiresAt = source.timestamp +
                    a2TtlSeconds.coerceAtLeast(0L) * 1_000L
                val state = if (expiresAt <= nowMillis) "expired" else "importable"
                items += LegacyMigrationItem(
                    legacyId = "event:${event.id}",
                    kind = "event",
                    state = state,
                    reason = if (state == "expired") {
                        "MEMORY_EXPIRED"
                    } else {
                        "LEGACY_EVENT_WITH_SOURCE"
                    },
                    tier = "A2",
                    sourceMessageId = source.id,
                    sourceReceivedAt = source.timestamp,
                )
            }
            LegacyMigrationContactPlan(
                contactId = entry.chatId,
                scopeKey = resolved.key,
                items = items,
            )
        }
        val allItems = contacts.flatMap { it.items }
        return LegacyMigrationPlan(
            contacts = contacts,
            totalItems = allItems.size,
            importable = allItems.count { it.state == "importable" },
            expired = allItems.count { it.state == "expired" },
            quarantined = allItems.count { it.state == "quarantined" },
            blocked = allItems.count { it.state == "blocked" },
        )
    }
}
