package com.local.wxbotprobe.loop

import android.content.Context
import com.local.wxbotprobe.bot.PersonaData
import com.local.wxbotprobe.bot.PersonaLoader
import com.local.wxbotprobe.bot.PersonaSelectionService
import com.local.wxbotprobe.chat.ChatIdentity
import com.local.wxbotprobe.chat.TextRules
import com.local.wxbotprobe.memory.v2.AppendMessageRequest
import com.local.wxbotprobe.memory.v2.AppendMessageResult
import com.local.wxbotprobe.memory.v2.MemoryV2BackgroundWorker
import com.local.wxbotprobe.memory.v2.MemoryV2ConfigLoader
import com.local.wxbotprobe.memory.v2.MemoryV2ContactIdentityStore
import com.local.wxbotprobe.memory.v2.MemoryV2Database
import com.local.wxbotprobe.memory.v2.MemoryV2JobService
import com.local.wxbotprobe.memory.v2.MemoryV2LimitPolicy
import com.local.wxbotprobe.memory.v2.MemoryV2OutboxWorker
import com.local.wxbotprobe.memory.v2.MemoryV2Repository
import com.local.wxbotprobe.memory.v2.MemoryV2RetrievalService
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextPayloadBuilder
import com.local.wxbotprobe.memory.v2.MemoryV2ScopeResolver
import com.local.wxbotprobe.memory.v2.ReplyContextPayload
import com.local.wxbotprobe.memory.v2.TrustedScopeContext
import java.util.UUID

private object MemoryOperationGate {
    val lock = Any()
}

internal class MemoryV2SessionBinding(
    initialSessionId: String? = null,
) {
    var sessionId: String? = initialSessionId
        private set

    fun accept(result: AppendMessageResult): String {
        require(result.sessionId.isNotBlank()) { "sessionId is required" }
        val bound = sessionId ?: result.sessionId
        sessionId = bound
        return bound
    }
}

class MemoryCoordinator(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    databaseName: String = MemoryV2Database.DATABASE_NAME,
    databaseOverride: MemoryV2Database? = null,
    private val scheduleBackgroundWork: Boolean = true,
) {
    private var v2Session: ActiveV2Session? = null
    private val v2Config = MemoryV2ConfigLoader.load(context)
    private val v2Database = databaseOverride ?: MemoryV2Database(context, databaseName)
    private val v2Repository = MemoryV2Repository(context, v2Database)
    private val v2Retrieval = MemoryV2RetrievalService(context, v2Database)
    private val v2ReplyContext = MemoryV2ReplyContextPayloadBuilder(
        context,
        v2Database,
        onLog = onLog,
    )
    private val v2Scopes = MemoryV2ScopeResolver(context)
    private val v2Contacts = MemoryV2ContactIdentityStore(context)
    private val v2Jobs = MemoryV2JobService(context, v2Database)
    private val personas = PersonaSelectionService(context)

    val chatId: String?
        get() = v2Session?.chatId

    val contextNote: String
        get() = replyContext.memoryText

    val replyContext: ReplyContextPayload
        get() = v2Session?.replyContext ?: ReplyContextPayload()

    val currentPersona: PersonaData
        get() = v2Session?.persona ?: PersonaLoader(context).load()

    val currentPersonaId: String?
        get() = v2Session?.personaId

    fun open(
        identity: ChatIdentity,
        nowMillis: Long,
    ) {
        val identityKey = TextRules.normalise(identity.title)
        if (identityKey.isBlank()) {
            return
        }
        synchronized(MemoryOperationGate.lock) {
            runCatching {
                openV2(identity, identityKey, nowMillis)
            }.onFailure {
                onLog("memory v2 open failed: ${it.stackTraceToString()}")
                onStatus("聊天记忆载入失败：${it.message}")
            }
        }
    }

    fun recordIncoming(
        text: String,
        nowMillis: Long,
    ) {
        recordV2(text, "user", nowMillis)
    }

    fun recordDeliveredParts(
        parts: List<String>,
        nowMillis: Long,
    ) {
        parts.filter { it.isNotBlank() }.forEach {
            recordV2(it, "assistant", nowMillis)
        }
    }

    fun closeAndRecover(nowMillis: Long) {
        synchronized(MemoryOperationGate.lock) {
            closeV2Session(nowMillis)
            val closed = v2Repository.closeOpenSessions(nowMillis / 1_000L)
            if (closed > 0) {
                onLog("memory v2 shutdown closed sessions=$closed")
            }
        }
    }

    fun detachForAsyncSummary(nowMillis: Long) {
        synchronized(MemoryOperationGate.lock) {
            closeV2Session(nowMillis)
        }
    }

    fun close() {
        v2Database.close()
    }

    private fun openV2(
        identity: ChatIdentity,
        identityKey: String,
        nowMillis: Long,
    ) {
        val current = v2Session
        if (current != null && current.identityKey == identityKey) {
            return
        }
        closeV2Session(nowMillis)
        val contactId = v2Contacts.resolve(identityKey, null)
        val selection = personas.selectedForContact(contactId)
        val scope = v2Repository.ensureScope(
            resolved = v2Scopes.resolve(
                contactId = contactId,
                personaId = selection.personaId,
                persona = selection.persona,
            ),
            contactDisplayName = identity.title.trim(),
        )
        val recovery = v2Repository.recoverOpenSession(
            scope,
            nowMillis / 1_000L,
        )
        val replyContext = v2ReplyContext.build(scope.scopeId, "")
        val sessionId = recovery?.session?.sessionId
        val pendingExtractionIds = sessionId?.let {
            v2Repository.pendingExtractionIds(scope, it)
        }.orEmpty()
        v2Session = ActiveV2Session(
            identityKey = identityKey,
            chatId = contactId,
            scope = scope,
            replyContext = replyContext,
            personaId = selection.personaId,
            persona = selection.persona,
            binding = MemoryV2SessionBinding(sessionId),
            sourceIds = pendingExtractionIds.toMutableList(),
            pendingExtractionIds = pendingExtractionIds.toMutableList(),
        )
        recovery?.let {
            onLog(
                "memory v2 session recovered session=${it.session.sessionId} " +
                    "nextSeq=${it.session.nextSeq} " +
                    "closedDuplicates=${it.closedDuplicates}",
            )
        }
        kickBackgroundWork()
    }

    private fun recordV2(
        text: String,
        role: String,
        nowMillis: Long,
    ) {
        if (text.isBlank()) {
            return
        }
        synchronized(MemoryOperationGate.lock) {
            val active = v2Session ?: return
            val epochSeconds = nowMillis / 1_000L
            val appended = v2Repository.appendMessage(
                active.scope,
                AppendMessageRequest(
                    clientMessageId = "live-${UUID.randomUUID()}",
                    sessionId = active.binding.sessionId,
                    role = role,
                    text = text.trim(),
                    occurredAt = epochSeconds,
                    receivedAt = epochSeconds,
                    timezone = v2Config.timezone,
                ),
            )
            active.binding.accept(appended)
            if (appended.status == "saved") {
                active.sourceIds += appended.messageId
                active.pendingExtractionIds += appended.messageId
            }
            active.pendingExtractionIds.retainAll(
                v2Repository.pendingExtractionIds(
                    active.scope,
                    active.binding.sessionId.orEmpty(),
                ).toSet(),
            )
            onLog(
                "memory v2 append role=$role seq=${appended.seq} " +
                    "session=${active.binding.sessionId}",
            )
            if (role == "user") {
                active.replyContext = v2ReplyContext.build(
                    active.scope.scopeId,
                    text.trim(),
                )
                if (active.replyContext.budgetOmittedMessageCount > 0) {
                    runCatching {
                        v2Jobs.enqueueNextSummary(
                            sessionId = active.binding.sessionId,
                            forceOpen = true,
                        )
                    }.onFailure {
                        onLog(
                            "memory v2 budget summary enqueue failed: " +
                                it.stackTraceToString(),
                        )
                    }
                }
            }
            if (
                MemoryV2LimitPolicy.reachedFlushThreshold(
                    active.pendingExtractionIds.size,
                    v2Config.backgroundMaxSources,
                )
            ) {
                enqueueExtraction(active)
            }
            kickBackgroundWork()
        }
    }

    private fun closeV2Session(nowMillis: Long) {
        v2Session?.let { active ->
            active.binding.sessionId?.let { sessionId ->
                active.pendingExtractionIds.retainAll(
                    v2Repository.pendingExtractionIds(
                        active.scope,
                        sessionId,
                    ).toSet(),
                )
                while (active.pendingExtractionIds.isNotEmpty()) {
                    val before = active.pendingExtractionIds.size
                    enqueueExtraction(active)
                    if (active.pendingExtractionIds.size >= before) {
                        break
                    }
                }
                runCatching {
                    v2Repository.closeSession(
                        active.scope,
                        sessionId,
                        nowMillis / 1_000L,
                    )
                }.onFailure {
                    onLog("memory v2 close failed: ${it.stackTraceToString()}")
                }
                runCatching {
                    v2Jobs.enqueueNextSummary(sessionId)
                }.onFailure {
                    onLog("memory v2 summary enqueue failed: ${it.stackTraceToString()}")
                }
                if (scheduleBackgroundWork) {
                    MemoryV2BackgroundWorker.kick(context)
                }
            }
        }
        v2Session = null
    }

    private fun kickBackgroundWork() {
        if (!scheduleBackgroundWork) {
            return
        }
        MemoryV2OutboxWorker.kick(context)
        MemoryV2BackgroundWorker.kick(context)
    }

    private fun enqueueExtraction(active: ActiveV2Session) {
        val sessionId = active.binding.sessionId ?: return
        if (active.pendingExtractionIds.isEmpty()) {
            return
        }
        val batch = MemoryV2LimitPolicy.takeWithinLimit(
            active.pendingExtractionIds,
            v2Config.backgroundMaxSources,
        )
        runCatching {
            v2Jobs.enqueue(
                scope = active.scope,
                sessionId = sessionId,
                kind = "extract",
                sourceIds = batch,
            )
        }.onSuccess {
            active.pendingExtractionIds.removeAll(batch.toSet())
        }.onFailure {
            onLog("memory v2 extract enqueue failed: ${it.stackTraceToString()}")
        }
    }

    private class ActiveV2Session(
        val identityKey: String,
        val chatId: String,
        val scope: TrustedScopeContext,
        var replyContext: ReplyContextPayload,
        val personaId: String,
        val persona: PersonaData,
        val binding: MemoryV2SessionBinding,
        val sourceIds: MutableList<String>,
        val pendingExtractionIds: MutableList<String>,
    )
}
