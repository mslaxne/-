package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import com.local.wxbotprobe.bot.PersonaSelectionService
import com.local.wxbotprobe.memory.v2.AppendMessageRequest
import com.local.wxbotprobe.memory.v2.ForgetMemoryCommand
import com.local.wxbotprobe.memory.v2.MemoryScopeKey
import com.local.wxbotprobe.memory.v2.MemoryCommandEvidence
import com.local.wxbotprobe.memory.v2.MemoryV2BatchCommitService
import com.local.wxbotprobe.memory.v2.MemoryV2BackgroundWorker
import com.local.wxbotprobe.memory.v2.MemoryV2BatchGenerator
import com.local.wxbotprobe.memory.v2.MemoryV2CallBudgetStore
import com.local.wxbotprobe.memory.v2.MemoryV2CommandService
import com.local.wxbotprobe.memory.v2.MemoryV2Database
import com.local.wxbotprobe.memory.v2.MemoryV2JobService
import com.local.wxbotprobe.memory.v2.MemoryV2LegacyImportService
import com.local.wxbotprobe.memory.v2.MemoryV2ErrorCode
import com.local.wxbotprobe.memory.v2.MemoryV2Exception
import com.local.wxbotprobe.memory.v2.MemoryV2OutboxPolicy
import com.local.wxbotprobe.memory.v2.MemoryV2OutboxProcessor
import com.local.wxbotprobe.memory.v2.MemoryV2OutboxWorker
import com.local.wxbotprobe.memory.v2.MemoryV2ReadPath
import com.local.wxbotprobe.memory.v2.MemoryV2ReadPathService
import com.local.wxbotprobe.memory.v2.MemoryV2Repository
import com.local.wxbotprobe.memory.v2.MemoryV2RetrievalService
import com.local.wxbotprobe.memory.v2.MemoryV2ScopeResolver
import com.local.wxbotprobe.memory.v2.MemoryV2Settings
import com.local.wxbotprobe.memory.v2.LeasedMemoryJob
import com.local.wxbotprobe.memory.v2.PinMemoryCommand
import com.local.wxbotprobe.memory.v2.ResolvedScopeInput
import com.local.wxbotprobe.memory.v2.SaveMemoryCommand
import com.local.wxbotprobe.memory.v2.toJsonObject
import com.local.wxbotprobe.chat.ChatIdentity
import com.local.wxbotprobe.memory.AgentSession
import com.local.wxbotprobe.memory.ChatMemoryStore
import com.local.wxbotprobe.memory.SessionMessage
import com.local.wxbotprobe.memory.SessionSummary
import com.local.wxbotprobe.memory.SummaryEvent
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class DebugMemoryV2Receiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread(
            {
                val result = runCatching {
                    selfTest(context.applicationContext)
                }
                val output = File(context.filesDir, "debug/memory-v2-selftest.json")
                output.parentFile?.mkdirs()
                output.writeText(
                    result.fold(
                        onSuccess = { it.toString(2) },
                        onFailure = {
                            JSONObject()
                                .put("status", "failed")
                                .put("error", it.message.orEmpty())
                                .put("stack", it.stackTraceToString())
                                .toString(2)
                        },
                    ),
                    Charsets.UTF_8,
                )
                android.util.Log.i("MemoryV2SelfTest", output.readText())
                pending.finish()
            },
            "memory-v2-selftest",
        ).start()
    }

    private fun selfTest(context: Context): JSONObject {
        val databaseName = "memory-v2-selftest.db"
        context.deleteDatabase(databaseName)
        val database = MemoryV2Database(context, databaseName)
        return try {
            val db = database.writableDatabase
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master " +
                    "WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name<>'android_metadata' ORDER BY name",
                null,
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }
            val expectedTables = setOf(
                "aliases",
                "audit_events",
                "capacity_ledger",
                "coverage",
                "dependencies",
                "entities",
                "evidence",
                "jobs",
                "memories",
                "memory_history",
                "memory_conflicts",
                "memory_relations",
                "messages",
                "message_cleanup",
                "migration_items",
                "migration_runs",
                "operations",
                "outbox",
                "scheduled_tasks",
                "scopes",
                "sessions",
                "tombstones",
            )
            if (tables != expectedTables) {
                error("table mismatch: $tables")
            }
            val foreignKeys = db.rawQuery("PRAGMA foreign_keys", null).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) == 1
            }
            if (!foreignKeys) {
                error("foreign keys are disabled")
            }
            val repository = MemoryV2Repository(
                context = context,
                database = database,
                now = { 1_800_000_000L },
            )
            val scope = repository.ensureScope(
                resolved = ResolvedScopeInput(
                    key = MemoryScopeKey(
                        ownerId = "owner-selftest",
                        contactId = "contact-selftest",
                        agentId = "agent-selftest",
                        personaId = "persona-selftest",
                        realmId = "real",
                    ),
                    personaRevision = 1,
                ),
                contactDisplayName = "Self Test",
            )
            val first = repository.appendMessage(
                scope,
                AppendMessageRequest(
                    clientMessageId = "client-selftest-1",
                    sessionId = null,
                    role = "user",
                    text = "杭州",
                    occurredAt = 1_800_000_000L,
                    receivedAt = 1_800_000_000L,
                    timezone = "Asia/Shanghai",
                ),
            )
            val duplicate = repository.appendMessage(
                scope,
                AppendMessageRequest(
                    clientMessageId = "client-selftest-1",
                    sessionId = first.sessionId,
                    role = "user",
                    text = "杭州",
                    occurredAt = 1_800_000_000L,
                    receivedAt = 1_800_000_000L,
                    timezone = "Asia/Shanghai",
                ),
            )
            if (duplicate.status != "unchanged" || duplicate.messageId != first.messageId) {
                error("message idempotency failed")
            }
            val commandService = MemoryV2CommandService(
                context = context,
                database = database,
                now = { 1_800_000_000L },
            )
            val save = commandService.save(
                scope,
                SaveMemoryCommand(
                    operationId = "operation-save",
                    commandSeq = 1,
                    tier = "A1",
                    predicate = "profile.city",
                    subjectId = "user-contact-selftest",
                    viewpointId = null,
                    text = "测试用户住在杭州。",
                    value = "杭州",
                    evidence = listOf(
                        MemoryCommandEvidence(
                            messageId = first.messageId,
                            sourceRevision = 1,
                            quote = "杭州",
                        ),
                    ),
                ),
            )
            if (save.status != "saved" || save.version != 1) {
                error("explicit save failed: $save")
            }
            val retrieval = MemoryV2RetrievalService(
                context = context,
                database = database,
                now = { 1_800_000_000L },
            )
            val contextResult = retrieval.buildContext(scope.scopeId, "杭州")
            if (
                contextResult.records.size != 1 ||
                "杭州" !in contextResult.text
            ) {
                error("scoped retrieval/context assembly failed")
            }
            val jobs = MemoryV2JobService(
                context = context,
                database = database,
                now = { 1_800_000_000L },
            )
            jobs.enqueue(
                scope = scope,
                sessionId = first.sessionId,
                kind = "extract",
                sourceIds = listOf(first.messageId),
            )
            val leased = jobs.lease("memory-v2-selftest").single()
            val emptyBatch = JSONObject()
                .put("contract", "ExtractionBatch")
                .put("envelope", leased.envelope.toJsonObject())
                .put(
                    "processed_message_ids",
                    JSONArray(listOf(first.messageId)),
                )
                .put("candidates", JSONArray())
            val validated = jobs.validateExtraction(
                leased,
                emptyBatch.toString(),
            )
            jobs.completeEmpty(leased, validated)
            val replay = commandService.save(
                scope,
                SaveMemoryCommand(
                    operationId = "operation-save",
                    commandSeq = 1,
                    tier = "A1",
                    predicate = "profile.city",
                    subjectId = "user-contact-selftest",
                    viewpointId = null,
                    text = "测试用户住在杭州。",
                    value = "杭州",
                    evidence = listOf(
                        MemoryCommandEvidence(
                            messageId = first.messageId,
                            sourceRevision = 1,
                            quote = "杭州",
                        ),
                    ),
                ),
            )
            if (replay.status != "unchanged") {
                error("operation replay was not idempotent")
            }
            val pin = commandService.pin(
                scope,
                PinMemoryCommand(
                    operationId = "operation-pin",
                    commandSeq = 2,
                    memoryId = save.memoryId!!,
                    expectedVersion = save.version!!,
                    pinned = true,
                ),
            )
            if (pin.status != "saved" || pin.version != 2) {
                error("pin failed")
            }
            val forget = commandService.forget(
                scope,
                ForgetMemoryCommand(
                    operationId = "operation-forget",
                    commandSeq = 3,
                    memoryId = save.memoryId,
                ),
            )
            if (
                forget.status != "saved" ||
                forget.deletionEpoch != 1 ||
                forget.removedMemoryIds != listOf(save.memoryId)
            ) {
                error("forget failed: $forget")
            }
            val forgottenMessage = db.rawQuery(
                "SELECT state FROM messages WHERE scope_id=? AND message_id=?",
                arrayOf(scope.scopeId, first.messageId),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0)
            }
            if (forgottenMessage != "forgotten") {
                error("source was not forgotten")
            }
            val second = repository.appendMessage(
                scope,
                AppendMessageRequest(
                    clientMessageId = "client-selftest-2",
                    sessionId = first.sessionId,
                    role = "user",
                    text = "我们明天一起吃饭",
                    occurredAt = 1_800_000_100L,
                    receivedAt = 1_800_000_100L,
                    timezone = "Asia/Shanghai",
                ),
            )
            jobs.enqueue(
                scope = scope,
                sessionId = first.sessionId,
                kind = "extract",
                sourceIds = listOf(second.messageId),
            )
            val extractionJob = jobs.lease("memory-v2-selftest").single()
            val candidate = JSONObject()
                .put("candidate_id", "candidate-dinner")
                .put("subject_id", "user-contact-selftest")
                .put("viewpoint_id", JSONObject.NULL)
                .put("predicate", "experience.event")
                .put("text", "测试用户约明天一起吃饭。")
                .put("value", "明天一起吃饭")
                .put("epistemic", "declared")
                .put("reason", "recent_event")
                .put("target_memory_id", JSONObject.NULL)
                .put("expected_version", JSONObject.NULL)
                .put(
                    "temporal",
                    JSONObject()
                        .put("occurred_at", JSONObject.NULL)
                        .put("local_date", JSONObject.NULL)
                        .put("timezone", "Asia/Shanghai")
                        .put("precision", "unknown")
                        .put("original", "明天"),
                )
                .put("commitment_state", JSONObject.NULL)
                .put("importance_hint", 0.7)
                .put(
                    "evidence",
                    JSONArray().put(
                        JSONObject()
                            .put("message_id", second.messageId)
                            .put("source_revision", 1)
                            .put("quote", "明天一起吃饭"),
                    ),
                )
            val extractionBatch = JSONObject()
                .put("contract", "ExtractionBatch")
                .put("envelope", extractionJob.envelope.toJsonObject())
                .put(
                    "processed_message_ids",
                    JSONArray(listOf(second.messageId)),
                )
                .put("candidates", JSONArray().put(candidate))
            val validatedExtraction = jobs.validateExtraction(
                extractionJob,
                extractionBatch.toString(),
            )
            val commitService = MemoryV2BatchCommitService(
                context = context,
                database = database,
                now = { 1_800_000_100L },
            )
            val committedExtraction = commitService.commitExtraction(
                extractionJob,
                validatedExtraction,
            )
            if (committedExtraction.memoryIds.size != 1) {
                error("candidate batch was not committed")
            }
            jobs.enqueue(
                scope = scope,
                sessionId = first.sessionId,
                kind = "summary",
                sourceIds = listOf(second.messageId),
            )
            val summaryJob = jobs.lease("memory-v2-selftest").single()
            val workingEvidence = JSONArray().put(
                JSONObject()
                    .put("message_id", second.messageId)
                    .put("source_revision", 1)
                    .put("quote", "明天一起吃饭"),
            )
            val summaryBatch = JSONObject()
                .put("contract", "SummaryBatch")
                .put("envelope", summaryJob.envelope.toJsonObject())
                .put(
                    "processed_message_ids",
                    JSONArray(listOf(second.messageId)),
                )
                .put("events", JSONArray())
                .put(
                    "working_summary",
                    JSONObject()
                        .put(
                            "goals",
                            JSONArray().put(
                                JSONObject()
                                    .put("text", "准备明天一起吃饭。")
                                    .put("evidence", workingEvidence),
                            ),
                        )
                        .put("constraints", JSONArray())
                        .put("unresolved", JSONArray())
                        .put("references", JSONArray()),
                )
            val validatedSummary = jobs.validateSummary(
                summaryJob,
                summaryBatch.toString(),
            )
            val committedSummary = commitService.commitSummary(
                summaryJob,
                validatedSummary,
            )
            if (committedSummary.workingVersion != 2) {
                error("working summary was not committed: $committedSummary")
            }
            val third = repository.appendMessage(
                scope,
                AppendMessageRequest(
                    clientMessageId = "client-selftest-3",
                    sessionId = first.sessionId,
                    role = "user",
                    text = "后天去公园",
                    occurredAt = 1_800_000_200L,
                    receivedAt = 1_800_000_200L,
                    timezone = "Asia/Shanghai",
                ),
            )
            jobs.enqueue(
                scope = scope,
                sessionId = first.sessionId,
                kind = "extract",
                sourceIds = listOf(third.messageId),
            )
            val fakeGenerator = object : MemoryV2BatchGenerator {
                override fun generate(job: LeasedMemoryJob): String {
                    val messageId = job.envelope.newSourceIds.single()
                    return JSONObject()
                        .put("contract", "ExtractionBatch")
                        .put("envelope", job.envelope.toJsonObject())
                        .put(
                            "processed_message_ids",
                            JSONArray(listOf(messageId)),
                        )
                        .put(
                            "candidates",
                            JSONArray().put(
                                JSONObject()
                                    .put("candidate_id", "candidate-park")
                                    .put(
                                        "subject_id",
                                        "user-contact-selftest",
                                    )
                                    .put("viewpoint_id", JSONObject.NULL)
                                    .put("predicate", "experience.event")
                                    .put("text", "测试用户后天去公园。")
                                    .put("value", "后天去公园")
                                    .put("epistemic", "declared")
                                    .put("reason", "recent_event")
                                    .put("target_memory_id", JSONObject.NULL)
                                    .put("expected_version", JSONObject.NULL)
                                    .put(
                                        "temporal",
                                        JSONObject()
                                            .put("occurred_at", JSONObject.NULL)
                                            .put("local_date", JSONObject.NULL)
                                            .put(
                                                "timezone",
                                                "Asia/Shanghai",
                                            )
                                            .put("precision", "unknown")
                                            .put("original", "后天"),
                                    )
                                    .put(
                                        "commitment_state",
                                        JSONObject.NULL,
                                    )
                                    .put("importance_hint", 0.6)
                                    .put(
                                        "evidence",
                                        JSONArray().put(
                                            JSONObject()
                                                .put("message_id", messageId)
                                                .put("source_revision", 1)
                                                .put("quote", "后天去公园"),
                                        ),
                                    ),
                            ),
                        )
                        .toString()
                }
            }
            MemoryV2Settings.setEnabledForTesting(context, true)
            try {
                val worker = MemoryV2BackgroundWorker(
                    context = context,
                    database = database,
                    generator = fakeGenerator,
                    budget = MemoryV2CallBudgetStore(context),
                    now = { 1_800_000_200L },
                )
                if (!worker.runOnce("memory-v2-worker-selftest")) {
                    error("background worker did not process a job")
                }
            } finally {
                MemoryV2Settings.setEnabledForTesting(context, false)
            }
            var outboxClock = 1_800_000_400L
            val drainWorker = MemoryV2OutboxWorker(
                context = context,
                database = database,
                now = { outboxClock },
            )
            var drained = 0
            while (drained < 100 && drainWorker.runOnce("outbox-selftest-drain")) {
                drained++
            }
            fun insertOutbox(eventId: String) {
                db.insertOrThrow(
                    "outbox",
                    null,
                    ContentValues().apply {
                        put("scope_id", scope.scopeId)
                        put("event_id", eventId)
                        put("kind", "scope_resync")
                        put("target_id", scope.scopeId)
                        put("target_version", 0)
                        put("deletion_epoch", scope.deletionEpoch)
                        put("state", "pending")
                        put("attempts", 0)
                        putNull("lease_owner")
                        putNull("lease_until")
                        put("next_attempt_at", 0L)
                        putNull("error_code")
                        put("created_at", outboxClock)
                    },
                )
            }
            insertOutbox("outbox-selftest-retry")
            var failures = 1
            val retryWorker = MemoryV2OutboxWorker(
                context = context,
                database = database,
                processor = MemoryV2OutboxProcessor {
                    if (failures-- > 0) {
                        throw MemoryV2Exception(
                            MemoryV2ErrorCode.STORAGE_UNAVAILABLE,
                            "injected outbox failure",
                        )
                    }
                },
                now = { outboxClock },
            )
            if (!retryWorker.runOnce("outbox-selftest-retry")) {
                error("outbox retry event was not leased")
            }
            val retryState = db.rawQuery(
                "SELECT state,attempts,error_code FROM outbox " +
                    "WHERE scope_id=? AND event_id=?",
                arrayOf(scope.scopeId, "outbox-selftest-retry"),
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    error("outbox retry event disappeared")
                }
                Triple(
                    cursor.getString(0),
                    cursor.getInt(1),
                    cursor.getString(2),
                )
            }
            if (
                retryState.first != "pending" ||
                retryState.second != 1 ||
                retryState.third != MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name
            ) {
                error("outbox retry state mismatch: $retryState")
            }
            outboxClock += MemoryV2OutboxPolicy.backoffMillis(1)
            var retryRemaining = 1
            var retryPasses = 0
            while (retryRemaining != 0 && retryPasses < 30) {
                if (!retryWorker.runOnce("outbox-selftest-retry")) {
                    error("outbox retry event was not processed")
                }
                retryPasses++
                retryRemaining = db.rawQuery(
                    "SELECT COUNT(*) FROM outbox WHERE scope_id=? AND event_id=?",
                    arrayOf(scope.scopeId, "outbox-selftest-retry"),
                ).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
            }
            if (retryRemaining != 0) {
                error("outbox retry event was not acknowledged")
            }
            insertOutbox("outbox-selftest-expired")
            db.update(
                "outbox",
                ContentValues().apply {
                    put("state", "leased")
                    put("lease_owner", "dead-worker")
                    put("lease_until", outboxClock - 1)
                },
                "scope_id=? AND event_id=?",
                arrayOf(scope.scopeId, "outbox-selftest-expired"),
            )
            val expiredReconciled = retryWorker.reconcileExpiredLeases()
            if (expiredReconciled < 1) {
                error("expired outbox lease was not reconciled")
            }
            var expiredRemaining = 1
            var expiredPasses = 0
            while (expiredRemaining != 0 && expiredPasses < 30) {
                if (!retryWorker.runOnce("outbox-selftest-expired")) {
                    error("expired outbox event was not processed")
                }
                expiredPasses++
                expiredRemaining = db.rawQuery(
                    "SELECT COUNT(*) FROM outbox WHERE scope_id=? AND event_id=?",
                    arrayOf(scope.scopeId, "outbox-selftest-expired"),
                ).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
            }
            if (expiredRemaining != 0) {
                error("expired outbox event was not acknowledged")
            }
            val health = repository.health(scope.scopeId)
            if (health.activeSources != 2 || health.activeMemories != 2) {
                error("worker health/ledger mismatch")
            }
            val operationCount = countRows(db, "operations", scope.scopeId)
            val tombstoneCount = countRows(db, "tombstones", scope.scopeId)
            val jobState = db.rawQuery(
                "SELECT COUNT(*) FROM jobs WHERE scope_id=? AND state='saved'",
                arrayOf(scope.scopeId),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            if (operationCount != 3 || tombstoneCount < 2) {
                error("receipts or tombstones missing")
            }
            if (jobState != 4) {
                error("jobs were not completed: $jobState")
            }
            val secondContext = retrieval.buildContext(
                scope.scopeId,
                "吃饭",
            )
            if (secondContext.records.size != 1) {
                error("committed candidate was not retrievable")
            }
            val parkContext = retrieval.buildContext(scope.scopeId, "公园")
            if (parkContext.records.size != 1) {
                error("worker commit was not retrievable")
            }
            val recoveryScope = repository.ensureScope(
                resolved = ResolvedScopeInput(
                    key = MemoryScopeKey(
                        ownerId = "owner-selftest",
                        contactId = "contact-recovery-selftest",
                        agentId = "agent-selftest",
                        personaId = "persona-selftest",
                        realmId = "real",
                    ),
                    personaRevision = 1,
                ),
                contactDisplayName = "Recovery Self Test",
            )
            val recoveryFirst = repository.appendMessage(
                recoveryScope,
                AppendMessageRequest(
                    clientMessageId = "client-recovery-1",
                    sessionId = null,
                    role = "user",
                    text = "重启前",
                    occurredAt = 1_800_000_240L,
                    receivedAt = 1_800_000_240L,
                    timezone = "Asia/Shanghai",
                ),
            )
            val recovered = repository.recoverOpenSession(
                recoveryScope,
                1_800_000_250L,
            ) ?: error("open session was not recoverable")
            if (recovered.session.sessionId != recoveryFirst.sessionId) {
                error("recovered the wrong session")
            }
            val recoverySecond = repository.appendMessage(
                recoveryScope,
                AppendMessageRequest(
                    clientMessageId = "client-recovery-2",
                    sessionId = recovered.session.sessionId,
                    role = "assistant",
                    text = "重启后",
                    occurredAt = 1_800_000_255L,
                    receivedAt = 1_800_000_255L,
                    timezone = "Asia/Shanghai",
                ),
            )
            if (
                recoverySecond.sessionId != recoveryFirst.sessionId ||
                recoverySecond.seq != 2
            ) {
                error("recovered session did not continue sequence")
            }
            if (!repository.closeSession(
                    recoveryScope,
                    recoverySecond.sessionId,
                    1_800_000_260L,
                )
            ) {
                error("recovered session close failed")
            }
            if (!repository.closeSession(scope, first.sessionId, 1_800_000_300L)) {
                error("session close failed")
            }
            val sessionState = db.rawQuery(
                "SELECT state FROM sessions WHERE scope_id=? AND session_id=?",
                arrayOf(scope.scopeId, first.sessionId),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0)
            }
            if (sessionState != "closed") {
                error("session did not close")
            }
            val legacyImport = legacyImportSelfTest(context, database)
            repository.close()
            JSONObject()
                .put("status", "passed")
                .put("tables", tables.size)
                .put("foreign_keys", foreignKeys)
                .put("scope_id", scope.scopeId)
                .put("session_id", first.sessionId)
                .put("message_seq", first.seq)
                .put("idempotent_status", duplicate.status)
                .put("active_sources", health.activeSources)
                .put("ledger_bytes", health.usedBytes)
                .put("explicit_save_version", save.version)
                .put("pin_version", pin.version)
                .put("deletion_epoch", forget.deletionEpoch)
                .put("operation_receipts", operationCount)
                .put("tombstones", tombstoneCount)
                .put("forgotten_message_state", forgottenMessage)
                .put("context_memory_count", contextResult.records.size)
                .put("saved_jobs", jobState)
                .put("committed_memories", committedExtraction.memoryIds.size)
                .put("working_version", committedSummary.workingVersion)
                .put("second_context_memories", secondContext.records.size)
                .put("worker_context_memories", parkContext.records.size)
                .put("outbox_drained", drained)
                .put("outbox_retry_attempts", retryState.second)
                .put("outbox_expired_reconciled", expiredReconciled)
                .put("recovered_session_id", recovered.session.sessionId)
                .put("recovered_session_closed_duplicates", recovered.closedDuplicates)
                .put("recovered_append_seq", recoverySecond.seq)
                .put("session_state", sessionState)
                .put("legacy_import", legacyImport)
        } finally {
            runCatching { database.close() }
            context.deleteDatabase(databaseName)
        }
    }

    private fun legacyImportSelfTest(
        context: Context,
        database: MemoryV2Database,
    ): JSONObject {
        val root = File(context.filesDir, "debug/legacy-migration-selftest")
        root.deleteRecursively()
        root.mkdirs()
        return try {
            val store = ChatMemoryStore(root)
            val nowMs = 1_800_000_300_000L
            val entry = store.resolveChat(ChatIdentity("Legacy Self Test", 1L), nowMs)
            var session = store.createSession(entry, nowMs)
            session = store.appendMessage(
                session,
                SessionMessage(
                    id = "legacy-m1",
                    role = "user",
                    text = "周六一起吃饭",
                    timestamp = nowMs,
                    delivery = "received",
                ),
            )
            session = session.copy(
                status = AgentSession.STATUS_CLOSING,
                summaryStatus = "running",
                summaryRunId = "legacy-selftest-run",
            )
            store.saveSession(session)
            store.saveSummary(
                session,
                SessionSummary(
                    summary = "旧会话摘要",
                    topics = listOf("吃饭"),
                    events = listOf(
                        SummaryEvent(
                            type = "invitation",
                            text = "周六一起吃饭",
                            date = "",
                            status = "proposed",
                            importance = 0.8,
                            sourceMessageIds = listOf("legacy-m1"),
                        ),
                        SummaryEvent(
                            type = "fact",
                            text = "无来源旧事实",
                            date = "",
                            status = "pending",
                            importance = 0.5,
                            sourceMessageIds = emptyList(),
                        ),
                    ),
                ),
                nowMs,
            )
            val service = MemoryV2LegacyImportService(
                context = context,
                database = database,
                now = { 1_800_000_300L },
            )
            val plan = service.plan(store)
            if (plan.importable != 1 || plan.quarantined != 2) {
                error("legacy dry-run counts mismatch: $plan")
            }
            val firstCheckpoint = service.importWithCheckpointLimit(
                store = store,
                legacyRoot = root,
                plan = plan,
                approvedContactIds = setOf(entry.chatId),
                maxCommittedItems = 1,
            )
            if (
                firstCheckpoint.status != "paused" ||
                firstCheckpoint.runId == null
            ) {
                error("legacy checkpoint did not pause: $firstCheckpoint")
            }
            val checkpointRunId = firstCheckpoint.runId
            val checkpointReceipts = database.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM migration_items WHERE run_id=?",
                arrayOf(checkpointRunId),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            val checkpointState = database.readableDatabase.rawQuery(
                "SELECT state FROM migration_runs WHERE run_id=?",
                arrayOf(checkpointRunId),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0)
            }
            if (checkpointReceipts != 1 || checkpointState != "pending") {
                error(
                    "legacy checkpoint state mismatch: " +
                        "receipts=$checkpointReceipts state=$checkpointState",
                )
            }
            val result = service.import(
                store = store,
                legacyRoot = root,
                plan = plan,
                approvedContactIds = setOf(entry.chatId),
            )
            if (
                result.status != "saved" ||
                result.runId != checkpointRunId ||
                result.imported != 1 ||
                result.quarantined != 2
            ) {
                error("legacy import failed: $result")
            }
            val replay = service.import(
                store = store,
                legacyRoot = root,
                plan = plan,
                approvedContactIds = setOf(entry.chatId),
            )
            if (replay.status != "unchanged" || replay.runId != result.runId) {
                error("legacy import replay failed: $replay")
            }
            val runId = result.runId ?: error("legacy import run id is missing")
            val importedMemoryCount = database.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM migration_items WHERE run_id=? " +
                    "AND state='imported' AND target_id IS NOT NULL",
                arrayOf(runId),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            val importedSelection = PersonaSelectionService(context)
                .selectedForContact(entry.chatId)
            val importedScope = MemoryV2Repository(context, database).ensureScope(
                MemoryV2ScopeResolver(context).resolve(
                    contactId = entry.chatId,
                    personaId = importedSelection.personaId,
                    persona = importedSelection.persona,
                ),
                entry.displayName,
            )
            val ledgerRows = database.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM capacity_ledger WHERE scope_id=? " +
                    "AND resource IN ('memories','evidence','messages','sessions') " +
                    "AND used_bytes>0",
                arrayOf(importedScope.scopeId),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            if (importedMemoryCount != 1 || ledgerRows != 4) {
                error(
                    "legacy import receipt/ledger mismatch: " +
                        "items=$importedMemoryCount ledger=$ledgerRows",
                )
            }
            val readPath = MemoryV2ReadPathService(context, database)
            val activated = readPath.activate(runId)
            if (
                activated.status != "activated" ||
                activated.snapshot.path != MemoryV2ReadPath.V2 ||
                !activated.snapshot.enabled
            ) {
                error("read path activation failed: $activated")
            }
            val rolledBack = readPath.rollback()
            if (
                rolledBack.status != "rolled_back" ||
                rolledBack.snapshot.path != MemoryV2ReadPath.LEGACY ||
                rolledBack.snapshot.enabled
            ) {
                error("read path rollback failed: $rolledBack")
            }
            JSONObject()
                .put("status", result.status)
                .put("checkpoint_status", firstCheckpoint.status)
                .put("checkpoint_receipts", checkpointReceipts)
                .put("checkpoint_state", checkpointState)
                .put("run_id", result.runId)
                .put("imported", result.imported)
                .put("quarantined", result.quarantined)
                .put("expired", result.expired)
                .put("blocked", result.blocked)
                .put("replay", replay.status)
                .put("ledger_rows", ledgerRows)
                .put("read_path", rolledBack.snapshot.path.name)
        } finally {
            root.deleteRecursively()
            MemoryV2ReadPathService(context, database).rollback()
        }
    }

    private fun countRows(
        database: android.database.sqlite.SQLiteDatabase,
        table: String,
        scopeId: String,
    ): Int {
        return database.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE scope_id=?",
            arrayOf(scopeId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }
}
