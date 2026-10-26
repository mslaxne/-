package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.memory.v2.AppendMessageRequest
import com.local.wxbotprobe.memory.v2.MemoryScopeKey
import com.local.wxbotprobe.memory.v2.MemoryV2AdminStore
import com.local.wxbotprobe.memory.v2.MemoryV2BackgroundWorker
import com.local.wxbotprobe.memory.v2.MemoryV2BatchCommitService
import com.local.wxbotprobe.memory.v2.MemoryV2CallBudgetStore
import com.local.wxbotprobe.memory.v2.MemoryV2ConfigLoader
import com.local.wxbotprobe.memory.v2.MemoryV2Database
import com.local.wxbotprobe.memory.v2.MemoryV2DeepSeekBatchGenerator
import com.local.wxbotprobe.memory.v2.MemoryV2ErrorCode
import com.local.wxbotprobe.memory.v2.MemoryV2Exception
import com.local.wxbotprobe.memory.v2.MemoryV2ExpiryService
import com.local.wxbotprobe.memory.v2.MemoryV2JobService
import com.local.wxbotprobe.memory.v2.MemoryCleanupSettings
import com.local.wxbotprobe.memory.v2.MemoryMessageBodyRetention
import com.local.wxbotprobe.memory.v2.MemoryV2MessageCleanupService
import com.local.wxbotprobe.memory.v2.MemoryV2Repository
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextBudgetPolicy
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextSnapshotBuilder
import com.local.wxbotprobe.memory.v2.MemoryV2RetrievalService
import com.local.wxbotprobe.memory.v2.ResolvedScopeInput
import com.local.wxbotprobe.memory.v2.TrustedScopeContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class DebugMemoryV2ScenarioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val execute = intent?.getBooleanExtra(EXTRA_EXECUTE, false) == true
        val case = intent?.getStringExtra(EXTRA_CASE).orEmpty()
        val pending = goAsync()
        Thread(
            {
                val result = runCatching {
                    require(execute) { "execute=true is required" }
                    runScenario(context.applicationContext, case)
                }
                val output = File(
                    context.filesDir,
                    "debug/memory-v2-scenario.json",
                )
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
                android.util.Log.i("MemoryV2Scenario", output.readText())
                pending.finish()
            },
            "memory-v2-scenario",
        ).start()
    }

    private fun runScenario(context: Context, case: String): JSONObject {
        if (case == "budget") {
            return runReplyContextBudgetScenario(context)
        }
        if (case == "repair") {
            return repairProduction(context)
        }
        if (case == "summary") {
            return runProductionSummaryProbe(context)
        }
        if (case == "prodstate") {
            return productionState(context)
        }
        val scenario = if (case == "temporal") TEMPORAL_SCENARIO else FULL_SCENARIO
        val suffix = case.ifBlank { "full" }
        val databaseName = "memory-v2-scenario-$suffix.db"
        context.deleteDatabase(databaseName)
        val database = MemoryV2Database(context, databaseName)
        val repository = MemoryV2Repository(context, database)
        val scope = repository.ensureScope(
            resolved = ResolvedScopeInput(
                key = MemoryScopeKey(
                    ownerId = "owner-scenario-$suffix",
                    contactId = "contact-scenario-$suffix",
                    agentId = "agent-scenario-$suffix",
                    personaId = "persona-scenario-$suffix",
                    realmId = "real",
                ),
                personaRevision = 1,
            ),
            contactDisplayName = "Scenario Contact",
        )
        val now = System.currentTimeMillis() / 1_000L
        if (case == "cleanup") {
            return runCleanupScenario(
                context,
                database,
                repository,
                scope,
                now,
            )
        }
        var sessionId = ""
        val messages = JSONArray()
        scenario.forEachIndexed { index, text ->
            val result = repository.appendMessage(
                scope,
                AppendMessageRequest(
                    clientMessageId = "scenario-$index",
                    sessionId = sessionId.ifBlank { null },
                    role = "user",
                    text = text,
                    occurredAt = now + index,
                    receivedAt = now + index,
                    timezone = "Asia/Shanghai",
                ),
            )
            sessionId = result.sessionId
            messages.put(
                JSONObject()
                    .put("index", index + 1)
                    .put("text", text),
            )
        }
        repository.closeSession(scope, sessionId, now + 100)
        val jobs = MemoryV2JobService(
            context = context,
            database = database,
            summaryMigrationEnabled = false,
            extractionMigrationEnabled = false,
        )
        val commits = MemoryV2BatchCommitService(context, database)
        val client = ModelClientFactory.create(context)
            ?: error("model client is not configured")
        val generator = MemoryV2DeepSeekBatchGenerator(context, database, client)
        val budget = MemoryV2CallBudgetStore(context)
        val rounds = JSONArray()
        val maxRounds = if (case == "temporal") 1 else MAX_ROUNDS
        repeat(maxRounds) {
            jobs.enqueueMissingExtraction(sessionId)
            val jobId = nextExtractionJob(database, sessionId) ?: return@repeat
            val job = jobs.leaseJob("debug-scenario", jobId)
                ?: return@repeat
            val round = JSONObject()
                .put("job_id", job.jobId)
                .put("source_count", job.envelope.newSourceIds.size)
            rounds.put(round)
            if (!budget.tryAcquire(job.scopeId, now)) {
                round.put("status", "budget_exhausted")
                jobs.cancelForReschedule(
                    job,
                    MemoryV2ErrorCode.CALL_BUDGET_EXHAUSTED,
                )
                return@repeat
            }
            var raw = ""
            try {
                raw = generator.generate(job)
                val batch = jobs.validateExtraction(
                    job,
                    raw,
                )
                if (batch.candidates.isEmpty()) {
                    jobs.completeEmpty(job, batch)
                } else {
                    commits.commitExtraction(job, batch)
                }
                round
                    .put("status", "saved")
                    .put("candidate_count", batch.candidates.size)
            } catch (error: MemoryV2Exception) {
                if (error.code == MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED) {
                    budget.release(job.scopeId, now)
                    jobs.splitContextOverflow(job)
                    round.put("status", "resized")
                } else {
                    jobs.fail(job, error.code.name)
                    round
                        .put("status", "error")
                        .put("error", error.code.name)
                        .put("error_detail", error.message.orEmpty())
                        .put("raw_model_output", raw)
                }
            } catch (error: Throwable) {
                round
                    .put("status", "error")
                    .put("error", error.javaClass.simpleName)
                    .put("error_detail", error.message.orEmpty())
                    .put("stack", error.stackTraceToString())
            }
        }
        val db = database.readableDatabase
        val memories = JSONArray()
        db.rawQuery(
            "SELECT m.tier,m.predicate,m.text,m.value_json,e.quote," +
                "m.temporal_json,m.expires_at " +
                "FROM memories m JOIN evidence e ON e.scope_id=m.scope_id " +
                "AND e.memory_id=m.memory_id WHERE m.scope_id=? " +
                "ORDER BY m.tier,m.created_at",
            arrayOf(scope.scopeId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                memories.put(
                    JSONObject()
                        .put("tier", cursor.getString(0))
                        .put("predicate", cursor.getString(1))
                        .put("text", cursor.getString(2))
                        .put("value", cursor.getString(3))
                        .put("quote", cursor.getString(4))
                        .put("temporal", JSONObject(cursor.getString(5)))
                        .put(
                            "expires_at",
                            if (cursor.isNull(6)) JSONObject.NULL else cursor.getLong(6),
                        ),
                )
            }
        }
        val extractionStates = db.rawQuery(
            "SELECT extraction_state,COUNT(*) FROM messages " +
                "WHERE scope_id=? GROUP BY extraction_state",
            arrayOf(scope.scopeId),
        ).use { cursor ->
            JSONObject().apply {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), cursor.getInt(1))
                }
            }
        }
        val quickCheck = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        val foreignKeys = db.rawQuery("PRAGMA foreign_key_check", null).use {
            it.count
        }
        val result = JSONObject()
            .put("status", "passed")
            .put("messages", messages)
            .put("rounds", rounds)
            .put("memories", memories)
            .put("extraction_states", extractionStates)
            .put("quick_check", quickCheck)
            .put("foreign_key_violations", foreignKeys)
        if (case == "temporal") {
            val ttlSeconds = MemoryV2ConfigLoader.load(context)
                .tiers["A2"]?.ttlSeconds
                ?: error("A2 ttl is missing")
            db.execSQL(
                "UPDATE memories SET expires_at=? WHERE scope_id=?",
                arrayOf(now + ttlSeconds, scope.scopeId),
            )
            val reconciled = MemoryV2ExpiryService(context, database)
                .reconcileRecentEvents()
            val retrieval = MemoryV2RetrievalService(context, database)
            val admin = MemoryV2AdminStore(context, database)
            result
                .put("reconciled", reconciled)
                .put(
                    "active_context",
                    retrieval.buildContext(scope.scopeId, "").text,
                )
                .put(
                    "contact_memory_count_before_expiry",
                    admin.contacts()
                        .firstOrNull { it.scopeId == scope.scopeId }
                        ?.memoryCount,
                )
            db.execSQL(
                "UPDATE memories SET expires_at=? WHERE scope_id=?",
                arrayOf(now - 1L, scope.scopeId),
            )
            result
                .put(
                    "expired_context",
                    retrieval.buildContext(scope.scopeId, "").text,
                )
                .put(
                    "contact_memory_count_after_expiry",
                    admin.contacts()
                        .firstOrNull { it.scopeId == scope.scopeId }
                        ?.memoryCount,
                )
        }
        return result
    }

    private fun runReplyContextBudgetScenario(context: Context): JSONObject {
        val databaseName = "memory-v2-scenario-budget.db"
        context.deleteDatabase(databaseName)
        val database = MemoryV2Database(context, databaseName)
        try {
            val repository = MemoryV2Repository(context, database)
            val now = System.currentTimeMillis() / 1_000L
            val coveredScope = ensureBudgetScope(repository, "covered")
            val coveredA0Id = "budget-a0-covered"
            val coveredA1Id = "budget-a1-covered"
            insertBudgetMemory(
                db = database.writableDatabase,
                scope = coveredScope,
                memoryId = coveredA0Id,
                tier = "A0",
                factKey = "relationship.spouse",
                predicate = "relationship.spouse",
                text = "对方是你的同事。",
                now = now,
            )
            insertBudgetMemory(
                db = database.writableDatabase,
                scope = coveredScope,
                memoryId = coveredA1Id,
                tier = "A1",
                factKey = "preference.ice_cream",
                predicate = "preference.ice_cream",
                text = "对方喜欢吃咖啡。",
                now = now,
            )
            val coveredHistory = appendBudgetHistory(
                repository = repository,
                scope = coveredScope,
                now = now,
                oldCount = 20,
                recentCount = 8,
                oldBodyBytes = 1_800,
                recentBodyBytes = 450,
            )
            val coveredMessageIds = coveredHistory.messageIds
                .take(coveredHistory.oldCount)
            val recentMessageIds = coveredHistory.messageIds
                .takeLast(coveredHistory.recentCount)
            val workingSummary = JSONObject()
                .put(
                    "goals",
                    JSONArray().put(
                        JSONObject()
                            .put(
                                "text",
                                "A3_BUDGET_CHECKPOINT：旧对话已经压缩。" +
                                    "最近八条原文仍需保留。",
                            )
                            .put("evidence", JSONArray()),
                    ),
                )
                .put("constraints", JSONArray())
                .put("unresolved", JSONArray())
                .put("references", JSONArray())
            val workingSources = JSONArray()
            coveredMessageIds.forEach(workingSources::put)
            check(
                database.writableDatabase.update(
                    "sessions",
                    ContentValues().apply {
                        put("working_summary", workingSummary.toString())
                        put("working_version", 1)
                        put("working_sources", workingSources.toString())
                        put("updated_at", now + coveredHistory.messageIds.size + 1)
                    },
                    "scope_id=? AND session_id=?",
                    arrayOf(coveredScope.scopeId, coveredHistory.sessionId),
                ) == 1,
            ) { "budget working summary update failed" }

            val config = MemoryV2ConfigLoader.load(context)
            val budget = MemoryV2ReplyContextBudgetPolicy.resolve(
                providerId = "openrouter_free",
                providerWindows = config.replyContextProviderWindowTokens,
                outputReserveTokens = 500,
                safetyMarginTokens = config.tokenPolicy.safetyMargin,
                framingFixedTokens = config.tokenPolicy.framingFixed +
                    MemoryV2ReplyContextBudgetPolicy
                        .NON_CONTEXT_PROMPT_RESERVE_TOKENS,
                targetInputTokens = config.targetInputTokens,
            )
            check(budget.maxPromptUtf8Bytes > 0) {
                "openrouter_free budget must be bounded"
            }
            val builder = MemoryV2ReplyContextSnapshotBuilder(
                context = context,
                database = database,
                now = { now },
            )
            val snapshot = builder.build(
                scopeId = coveredScope.scopeId,
                maxPromptUtf8Bytes = budget.maxPromptUtf8Bytes,
                recentRawMessages = 8,
                providerId = budget.providerId,
                providerWindowTokens = budget.contextWindowTokens,
            )
            val selectedIds = snapshot.layers.sessionMessages
                .mapTo(mutableSetOf()) { it.messageId }
            val omittedIds = coveredMessageIds.filterNot(selectedIds::contains)
            check(omittedIds.isNotEmpty()) {
                "budget scenario must omit covered history"
            }
            check(
                omittedIds.all { it in coveredMessageIds && it !in recentMessageIds },
            ) {
                "only old messages covered by A3 may be omitted"
            }
            check(recentMessageIds.all(selectedIds::contains)) {
                "latest eight raw messages must remain protected"
            }
            check(
                omittedIds.none { messageId ->
                    val marker = coveredHistory.markersByMessageId
                        .getValue(messageId)
                    snapshot.promptText.contains(marker)
                },
            ) { "omitted raw-message markers must not leak into the prompt" }
            check(snapshot.layers.a0.any { it.memoryId == coveredA0Id }) {
                "A0 must be present in the reply snapshot"
            }
            check(snapshot.layers.a1.any { it.memoryId == coveredA1Id }) {
                "A1 must be present in the reply snapshot"
            }
            check(
                snapshot.layers.sessionSummaries.any { summary ->
                    summary.coveredMessageIds.containsAll(coveredMessageIds)
                },
            ) { "A3 checkpoint and covered IDs must be present" }
            check(
                snapshot.estimatedUtf8Bytes <= budget.maxPromptUtf8Bytes,
            ) { "rendered reply context exceeds the provider budget" }
            check(
                snapshot.budgetOmittedMessageCount == omittedIds.size,
            ) { "omitted-message accounting must match the snapshot" }
            check(snapshot.promptText.contains("A3_BUDGET_CHECKPOINT")) {
                "A3 checkpoint must be rendered"
            }

            val uncoveredScope = ensureBudgetScope(repository, "uncovered")
            appendBudgetHistory(
                repository = repository,
                scope = uncoveredScope,
                now = now + 10_000,
                oldCount = 20,
                recentCount = 8,
                oldBodyBytes = 1_600,
                recentBodyBytes = 1_600,
            )
            var uncoveredError = ""
            try {
                builder.build(
                    scopeId = uncoveredScope.scopeId,
                    maxPromptUtf8Bytes = budget.maxPromptUtf8Bytes,
                    recentRawMessages = 8,
                    providerId = budget.providerId,
                    providerWindowTokens = budget.contextWindowTokens,
                )
            } catch (error: MemoryV2Exception) {
                uncoveredError = error.code.name
            }
            check(uncoveredError == MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED.name) {
                "uncovered history must fail closed instead of being dropped"
            }

            val db = database.readableDatabase
            val quickCheck = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0)
            }
            val foreignKeys = db.rawQuery(
                "PRAGMA foreign_key_check",
                null,
            ).use { cursor -> cursor.count }
            check(quickCheck == "ok") { "budget scenario database is not healthy" }
            check(foreignKeys == 0) { "budget scenario has foreign-key violations" }

            return JSONObject()
                .put("status", "passed")
                .put("case", "budget")
                .put("database", databaseName)
                .put("model_calls", 0)
                .put("provider_id", budget.providerId)
                .put("provider_window_tokens", budget.contextWindowTokens)
                .put("max_prompt_utf8_bytes", budget.maxPromptUtf8Bytes)
                .put("estimated_utf8_bytes", snapshot.estimatedUtf8Bytes)
                .put("covered_message_count", coveredMessageIds.size)
                .put("recent_protected_count", recentMessageIds.size)
                .put("selected_message_count", selectedIds.size)
                .put(
                    "budget_omitted_message_count",
                    snapshot.budgetOmittedMessageCount,
                )
                .put("a0_present", true)
                .put("a1_present", true)
                .put("a3_present", true)
                .put("only_covered_old_messages_omitted", true)
                .put("uncovered_error", uncoveredError)
                .put("quick_check", quickCheck)
                .put("foreign_key_violations", foreignKeys)
        } finally {
            database.close()
        }
    }

    private fun ensureBudgetScope(
        repository: MemoryV2Repository,
        suffix: String,
    ): TrustedScopeContext {
        return repository.ensureScope(
            resolved = ResolvedScopeInput(
                key = MemoryScopeKey(
                    ownerId = "owner-budget-$suffix",
                    contactId = "contact-budget-$suffix",
                    agentId = "agent-budget-$suffix",
                    personaId = "persona-budget-$suffix",
                    realmId = "real",
                ),
                personaRevision = 1,
            ),
            contactDisplayName = "Budget Scenario Contact",
        )
    }

    private fun insertBudgetMemory(
        db: SQLiteDatabase,
        scope: TrustedScopeContext,
        memoryId: String,
        tier: String,
        factKey: String,
        predicate: String,
        text: String,
        now: Long,
    ) {
        val entityId = "user-${scope.key.contactId}"
        db.insertOrThrow(
            "memories",
            null,
            ContentValues().apply {
                put("scope_id", scope.scopeId)
                put("memory_id", memoryId)
                put("version", 1)
                put("tier", tier)
                put("fact_key", factKey)
                put("subject_id", entityId)
                put("viewpoint_id", entityId)
                put("predicate", predicate)
                put("text", text)
                put("value_json", JSONObject().put("value", text).toString())
                put("epistemic", "configured")
                put("lifecycle", "active")
                put("pinned", 1)
                put("temporal_json", "{}")
                put("retention_score", 1.0)
                put("source_anchor", now)
                put("created_at", now)
                put("updated_at", now)
                put("deletion_epoch", scope.deletionEpoch)
            },
        )
    }

    private fun appendBudgetHistory(
        repository: MemoryV2Repository,
        scope: TrustedScopeContext,
        now: Long,
        oldCount: Int,
        recentCount: Int,
        oldBodyBytes: Int,
        recentBodyBytes: Int,
    ): BudgetHistoryFixture {
        require(oldCount > 0 && recentCount > 0)
        var sessionId: String? = null
        val messageIds = mutableListOf<String>()
        val markers = mutableMapOf<String, String>()
        repeat(oldCount + recentCount) { index ->
            val isOld = index < oldCount
            val marker = if (isOld) {
                "BUDGET_OLD_${index.toString().padStart(2, '0')}"
            } else {
                "BUDGET_RECENT_${(index - oldCount).toString().padStart(2, '0')}"
            }
            val bodyBytes = if (isOld) oldBodyBytes else recentBodyBytes
            val text = "$marker " + "x".repeat(bodyBytes)
            val appended = repository.appendMessage(
                scope,
                AppendMessageRequest(
                    clientMessageId = "budget-message-$index",
                    sessionId = sessionId,
                    role = if (index % 2 == 0) "user" else "assistant",
                    text = text,
                    occurredAt = now + index,
                    receivedAt = now + index,
                    timezone = "Asia/Shanghai",
                ),
            )
            sessionId = appended.sessionId
            messageIds += appended.messageId
            markers[appended.messageId] = marker
        }
        return BudgetHistoryFixture(
            sessionId = requireNotNull(sessionId),
            messageIds = messageIds,
            markersByMessageId = markers,
            oldCount = oldCount,
            recentCount = recentCount,
        )
    }

    private data class BudgetHistoryFixture(
        val sessionId: String,
        val messageIds: List<String>,
        val markersByMessageId: Map<String, String>,
        val oldCount: Int,
        val recentCount: Int,
    )

    private fun repairProduction(context: Context): JSONObject {
        val database = MemoryV2Database(context)
        val jobs = MemoryV2JobService(context, database)
        val recovery = jobs.splitFailedOverflowBatch()
        val promptCancelled = database.writableDatabase.update(
            "jobs",
            android.content.ContentValues().apply {
                put("state", "cancelled")
                put("error_code", "PROMPT_VERSION_REPAIR")
                putNull("lease_owner")
                putNull("lease_until")
                put("updated_at", System.currentTimeMillis() / 1_000L)
            },
            "kind='extract' AND state='failed' AND error_code IN " +
                "('PROMPT_VERSION_MISMATCH','SCHEMA_INVALID')",
            null,
        )
        val queued = JSONArray()
        repeat(16) {
            val result = jobs.enqueueMissingExtraction() ?: return@repeat
            queued.put(result.jobId)
        }
        MemoryV2BackgroundWorker.kick(context)
        return productionState(context)
            .put("case", "repair")
            .put("overflow_recovery_changed", recovery.changed)
            .put(
                "overflow_recovery_job_id",
                recovery.splitJobId ?: JSONObject.NULL,
            )
            .put(
                "overflow_recovery_error",
                recovery.errorCode ?: JSONObject.NULL,
            )
            .put("prompt_failed_cancelled", promptCancelled)
            .put("requeued_jobs", queued)
    }

    private fun runProductionSummaryProbe(context: Context): JSONObject {
        val database = MemoryV2Database(context)
        val jobs = MemoryV2JobService(context, database)
        val commits = MemoryV2BatchCommitService(context, database)
        val client = ModelClientFactory.create(context)
            ?: error("model client is not configured")
        val generator = MemoryV2DeepSeekBatchGenerator(context, database, client)
        val budget = MemoryV2CallBudgetStore(context)
        val rounds = JSONArray()
        val current = System.currentTimeMillis() / 1_000L
        database.writableDatabase.update(
            "jobs",
            android.content.ContentValues().apply {
                put("state", "pending")
                put("attempts", 0)
                putNull("error_code")
                putNull("lease_owner")
                putNull("lease_until")
                put("next_attempt_at", current)
                put("updated_at", current)
            },
            "kind='summary' AND state='failed' AND error_code IN " +
                "('SCHEMA_INVALID','SOURCE_SET_INVALID')",
            null,
        )
        repeat(3) {
            jobs.enqueueNextSummary()
            val jobId = nextPendingJob(database, "summary") ?: return@repeat
            val job = jobs.leaseJob("debug-production-summary", jobId)
                ?: return@repeat
            val round = JSONObject()
                .put("job_id", job.jobId)
                .put("session_id", job.sessionId)
                .put("source_count", job.envelope.newSourceIds.size)
            rounds.put(round)
            if (!budget.tryAcquire(job.scopeId, System.currentTimeMillis() / 1_000L)) {
                jobs.pauseUntil(
                    job,
                    (System.currentTimeMillis() / 1_000L) + 3_600L,
                    MemoryV2ErrorCode.CALL_BUDGET_EXHAUSTED.name,
                )
                round.put("status", "budget_paused")
                return JSONObject()
                    .put("status", "passed")
                    .put("rounds", rounds)
            }
            var raw = ""
            try {
                raw = generator.generate(job)
                val batch = jobs.validateSummary(job, raw)
                if (batch.events.isEmpty() && batch.workingItems.isEmpty()) {
                    jobs.completeEmpty(job, batch)
                } else {
                    commits.commitSummary(job, batch)
                }
                round
                    .put("status", "saved")
                    .put("event_count", batch.events.size)
                    .put("working_item_count", batch.workingItems.size)
            } catch (error: MemoryV2Exception) {
                jobs.fail(job, error.code.name)
                round
                    .put("status", "error")
                    .put("error", error.code.name)
                    .put("error_detail", error.message.orEmpty())
                    .put("raw_model_output", raw)
            }
        }
        return productionState(context)
            .put("case", "summary-probe")
            .put("rounds", rounds)
    }

    private fun nextPendingJob(
        database: MemoryV2Database,
        kind: String,
    ): String? {
        return database.readableDatabase.rawQuery(
            "SELECT job_id FROM jobs WHERE kind=? AND state='pending' " +
                "ORDER BY created_at LIMIT 1",
            arrayOf(kind),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun productionState(context: Context): JSONObject {
        val database = MemoryV2Database(context)
        val db = database.readableDatabase
        val schemaVersion = db.rawQuery("PRAGMA user_version", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        val cleanupTable = db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master " +
                "WHERE type='table' AND name='message_cleanup'",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        val quickCheck = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        val foreignKeys = db.rawQuery("PRAGMA foreign_key_check", null).use {
            it.count
        }
        fun grouped(table: String, column: String): JSONObject {
            return db.rawQuery(
                "SELECT $column,COUNT(*) FROM $table GROUP BY $column",
                null,
            ).use { cursor ->
                JSONObject().apply {
                    while (cursor.moveToNext()) {
                        put(
                            if (cursor.isNull(0)) {
                                "null"
                            } else {
                                cursor.getString(0)
                            },
                            cursor.getInt(1),
                        )
                    }
                }
            }
        }
        fun groupedSql(sql: String): JSONObject {
            return db.rawQuery(sql, null).use { cursor ->
                JSONObject().apply {
                    while (cursor.moveToNext()) {
                        put(
                            if (cursor.isNull(0)) {
                                "null"
                            } else {
                                cursor.getString(0)
                            },
                            cursor.getInt(1),
                        )
                    }
                }
            }
        }
        val admin = MemoryV2AdminStore(context, database)
        val overview = admin.overview()
        val failedJobs = JSONArray()
        db.rawQuery(
            "SELECT kind,COALESCE(error_code,''),attempts,created_at," +
                "envelope_json FROM jobs WHERE state='failed' " +
                "ORDER BY created_at",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val envelope = runCatching {
                    JSONObject(cursor.getString(4))
                }.getOrNull()
                failedJobs.put(
                    JSONObject()
                        .put("kind", cursor.getString(0))
                        .put("error", cursor.getString(1))
                        .put("attempts", cursor.getInt(2))
                        .put("created_at", cursor.getLong(3))
                        .put(
                            "source_count",
                            envelope?.optJSONArray("new_source_ids")?.length() ?: 0,
                        ),
                )
            }
        }
        val pendingExtractionWithoutJob = db.rawQuery(
            "SELECT COUNT(*) FROM messages m WHERE m.extraction_state='pending' " +
                "AND m.state='live' AND m.cache_expires_at>? AND NOT EXISTS (" +
                "SELECT 1 FROM dependencies d JOIN jobs j " +
                "ON j.scope_id=d.scope_id AND j.job_id=d.child_id " +
                "WHERE d.scope_id=m.scope_id AND d.parent_kind='message' " +
                "AND d.parent_id=m.message_id AND d.child_kind='job' " +
                "AND j.kind='extract' AND j.state IN ('pending','leased'))",
            arrayOf((System.currentTimeMillis() / 1_000L).toString()),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        val pendingJobs = JSONArray()
        db.rawQuery(
            "SELECT kind,COALESCE(error_code,''),attempts,next_attempt_at," +
                "envelope_json FROM jobs WHERE state='pending' " +
                "ORDER BY next_attempt_at,created_at",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val envelope = runCatching {
                    JSONObject(cursor.getString(4))
                }.getOrNull()
                pendingJobs.put(
                    JSONObject()
                        .put("kind", cursor.getString(0))
                        .put("error", cursor.getString(1))
                        .put("attempts", cursor.getInt(2))
                        .put("next_attempt_at", cursor.getLong(3))
                        .put(
                            "source_count",
                            envelope?.optJSONArray("new_source_ids")?.length() ?: 0,
                        ),
                )
            }
        }
        return JSONObject()
            .put("status", "passed")
            .put("case", "prodstate")
            .put("schema_version", schemaVersion)
            .put("cleanup_table", cleanupTable)
            .put(
                "message_cleanup",
                productionCleanupState(context, db),
            )
            .put("messages", grouped("messages", "state"))
            .put("extraction_states", grouped("messages", "extraction_state"))
            .put("summary_states", grouped("messages", "summary_state"))
            .put(
                "summary_gap_body_presence",
                groupedSql(
                    "SELECT CASE WHEN body IS NULL THEN 'null' ELSE 'present' END," +
                        "COUNT(*) FROM messages WHERE summary_state='gap' " +
                        "GROUP BY body IS NULL",
                ),
            )
            .put("session_states", grouped("sessions", "state"))
            .put("job_kinds", grouped("jobs", "kind"))
            .put("job_states", grouped("jobs", "state"))
            .put("job_errors", grouped("jobs", "error_code"))
            .put("failed_jobs", failedJobs)
            .put(
                "pending_extraction_without_job",
                pendingExtractionWithoutJob,
            )
            .put("pending_job_details", pendingJobs)
            .put("admin", JSONObject()
                .put("extracted", overview.extractedMessageCount)
                .put("pending_extraction", overview.pendingExtractionCount)
                .put("extraction_issues", overview.extractionIssueCount)
                .put("summary_done", overview.summaryDoneCount)
                .put("summary_gap", overview.summaryGapCount)
                .put("pending_summary", overview.pendingSummaryCount))
            .put("pump_running", MemoryV2BackgroundWorker.isPumping())
            .put(
                "pump_state",
                MemoryV2BackgroundWorker.pumpState().name,
            )
            .put(
                "summary_eligible_sessions",
                db.rawQuery(
                    "SELECT COUNT(*) FROM sessions s WHERE s.state='closed' " +
                        "AND EXISTS (SELECT 1 FROM messages m " +
                        "WHERE m.scope_id=s.scope_id " +
                        "AND m.session_id=s.session_id " +
                        "AND m.summary_state='pending' AND m.state='live' " +
                        "AND m.cache_expires_at>?)",
                    arrayOf((System.currentTimeMillis() / 1_000L).toString()),
                ).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                },
            )
            .put("memories", db.rawQuery(
                "SELECT COUNT(*) FROM memories WHERE lifecycle='active'",
                null,
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            })
            .put("quick_check", quickCheck)
            .put("foreign_key_violations", foreignKeys)
    }

    private fun productionCleanupState(
        context: Context,
        db: SQLiteDatabase,
    ): JSONObject {
        val current = System.currentTimeMillis() / 1_000L
        fun grouped(sql: String): JSONObject {
            return db.rawQuery(sql, null).use { cursor ->
                JSONObject().apply {
                    while (cursor.moveToNext()) {
                        put(
                            if (cursor.isNull(0)) {
                                "null"
                            } else {
                                cursor.getString(0)
                            },
                            cursor.getInt(1),
                        )
                    }
                }
            }
        }
        fun count(sql: String, vararg args: String): Int {
            return db.rawQuery(sql, args).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
        fun nextDue(sql: String): Long? {
            return db.rawQuery(sql, null).use { cursor ->
                if (
                    cursor.moveToFirst() &&
                    !cursor.isNull(0)
                ) {
                    cursor.getLong(0)
                } else {
                    null
                }
            }
        }
        val nextBodyDue = nextDue(
            "SELECT MIN(c.body_due_at) FROM message_cleanup c " +
                "JOIN messages m ON m.scope_id=c.scope_id " +
                "AND m.message_id=c.message_id " +
                "WHERE c.state='pending' AND m.body IS NOT NULL",
        )
        val nextMetadataDue = nextDue(
            "SELECT MIN(c.metadata_due_at) FROM message_cleanup c " +
                "JOIN messages m ON m.scope_id=c.scope_id " +
                "AND m.message_id=c.message_id " +
                "WHERE c.state='redacted'",
        )
        val config = MemoryV2ConfigLoader.load(context)
        return JSONObject()
            .put("now", current)
            .put(
                "body_retention",
                MemoryCleanupSettings.bodyRetention(context).name.lowercase(),
            )
            .put("metadata_history_seconds", config.historySeconds)
            .put(
                "cleanup_states",
                grouped(
                    "SELECT state,COUNT(*) FROM message_cleanup " +
                        "GROUP BY state ORDER BY state",
                ),
            )
            .put(
                "cleanup_errors",
                grouped(
                    "SELECT last_error,COUNT(*) FROM message_cleanup " +
                        "GROUP BY last_error ORDER BY last_error",
                ),
            )
            .put(
                "message_body_states",
                grouped(
                    "SELECT m.state || ':' || " +
                        "CASE WHEN m.body IS NULL THEN 'null' ELSE 'present' END," +
                        "COUNT(*) FROM messages m GROUP BY m.state,m.body IS NULL " +
                        "ORDER BY m.state",
                ),
            )
            .put(
                "eligible_without_cleanup",
                count(
                    "SELECT COUNT(*) FROM messages m " +
                        "LEFT JOIN message_cleanup c " +
                        "ON c.scope_id=m.scope_id AND c.message_id=m.message_id " +
                        "WHERE m.state='live' AND m.body IS NOT NULL " +
                        "AND c.message_id IS NULL " +
                        "AND m.extraction_state IN ('done','skipped','gap') " +
                        "AND m.summary_state IN ('done','gap') " +
                        "AND NOT EXISTS (" +
                        "SELECT 1 FROM dependencies d JOIN jobs j " +
                        "ON j.scope_id=d.scope_id AND j.job_id=d.child_id " +
                        "WHERE d.scope_id=m.scope_id " +
                        "AND d.parent_kind='message' AND d.parent_id=m.message_id " +
                        "AND d.child_kind='job' AND j.state IN ('pending','leased'))",
                ),
            )
            .put(
                "pending_due_with_body",
                count(
                    "SELECT COUNT(*) FROM message_cleanup c " +
                        "JOIN messages m ON m.scope_id=c.scope_id " +
                        "AND m.message_id=c.message_id " +
                        "WHERE c.state='pending' AND c.body_due_at<=? " +
                        "AND m.body IS NOT NULL",
                    current.toString(),
                ),
            )
            .put(
                "redacted_with_body",
                count(
                    "SELECT COUNT(*) FROM message_cleanup c " +
                        "JOIN messages m ON m.scope_id=c.scope_id " +
                        "AND m.message_id=c.message_id " +
                        "WHERE c.state='redacted' AND m.body IS NOT NULL",
                ),
            )
            .put(
                "purged_with_live_message",
                count(
                    "SELECT COUNT(*) FROM message_cleanup c " +
                        "JOIN messages m ON m.scope_id=c.scope_id " +
                        "AND m.message_id=c.message_id " +
                        "WHERE c.state='purged'",
                ),
            )
            .put(
                "redacted_without_message",
                count(
                    "SELECT COUNT(*) FROM message_cleanup c " +
                        "LEFT JOIN messages m ON m.scope_id=c.scope_id " +
                        "AND m.message_id=c.message_id " +
                        "WHERE c.state='redacted' AND m.message_id IS NULL",
                ),
            )
            .put(
                "deferred_tasks_not_terminal",
                count(
                    "SELECT COUNT(*) FROM message_cleanup " +
                        "WHERE state='pending' " +
                        "AND last_error='TASKS_NOT_TERMINAL'",
                ),
            )
            .put(
                "deferred_references_present",
                count(
                    "SELECT COUNT(*) FROM message_cleanup " +
                        "WHERE state='redacted' " +
                        "AND last_error='REFERENCES_PRESENT'",
                ),
            )
            .put(
                "due_metadata_redacted",
                count(
                    "SELECT COUNT(*) FROM message_cleanup " +
                        "WHERE state='redacted' AND metadata_due_at<=?",
                    current.toString(),
                ),
            )
            .put(
                "cleanup_audit_events",
                grouped(
                    "SELECT action,COUNT(*) FROM audit_events " +
                        "WHERE action IN ('message_body_redacted'," +
                        "'message_metadata_purged') GROUP BY action " +
                        "ORDER BY action",
                ),
            )
            .put("next_body_due_at", nextBodyDue ?: JSONObject.NULL)
            .put(
                "next_body_due_in_seconds",
                nextBodyDue?.minus(current) ?: JSONObject.NULL,
            )
            .put("next_metadata_due_at", nextMetadataDue ?: JSONObject.NULL)
            .put(
                "next_metadata_due_in_seconds",
                nextMetadataDue?.minus(current) ?: JSONObject.NULL,
            )
    }

    private fun runCleanupScenario(
        context: Context,
        database: MemoryV2Database,
        repository: MemoryV2Repository,
        scope: com.local.wxbotprobe.memory.v2.TrustedScopeContext,
        now: Long,
    ): JSONObject {
        val first = repository.appendMessage(
            scope,
            AppendMessageRequest(
                clientMessageId = "cleanup-processed",
                sessionId = null,
                role = "user",
                text = "这条消息已经完成记忆处理和摘要。",
                occurredAt = now,
                receivedAt = now,
                timezone = "Asia/Shanghai",
            ),
        )
        val second = repository.appendMessage(
            scope,
            AppendMessageRequest(
                clientMessageId = "cleanup-pending",
                sessionId = first.sessionId,
                role = "user",
                text = "这条消息还没有完成摘要。",
                occurredAt = now + 1,
                receivedAt = now + 1,
                timezone = "Asia/Shanghai",
            ),
        )
        val db = database.writableDatabase
        db.execSQL(
            "UPDATE messages SET extraction_state='done',summary_state='done' " +
                "WHERE scope_id=? AND message_id=?",
            arrayOf(scope.scopeId, first.messageId),
        )
        db.execSQL(
            "UPDATE messages SET extraction_state='done',summary_state='pending' " +
                "WHERE scope_id=? AND message_id=?",
            arrayOf(scope.scopeId, second.messageId),
        )
        var clock = now + 10
        val cleanup = MemoryV2MessageCleanupService(
            context = context,
            database = database,
            now = { clock },
            bodyRetention = MemoryMessageBodyRetention.ONE_HOUR,
        )
        val scheduled = cleanup.runOnce()
        db.execSQL(
            "UPDATE message_cleanup SET body_due_at=?,metadata_due_at=? " +
                "WHERE scope_id=? AND message_id=?",
            arrayOf(
                clock - 1L,
                clock - 1L,
                scope.scopeId,
                first.messageId,
            ),
        )
        clock += 10
        val purged = cleanup.runOnce()
        val firstCount = messageCount(db, scope.scopeId, first.messageId)
        val secondBody = messageBody(db, scope.scopeId, second.messageId)
        val cleanupState = cleanupState(db, scope.scopeId, first.messageId)
        val duplicate = repository.appendMessage(
            scope,
            AppendMessageRequest(
                clientMessageId = "cleanup-processed",
                sessionId = first.sessionId,
                role = "user",
                text = "这条消息已经完成记忆处理和摘要。",
                occurredAt = now,
                receivedAt = now,
                timezone = "Asia/Shanghai",
            ),
        )
        val auditCount = db.rawQuery(
            "SELECT COUNT(*) FROM audit_events WHERE scope_id=? " +
                "AND target_id=? AND action IN " +
                "('message_body_redacted','message_metadata_purged')",
            arrayOf(scope.scopeId, first.messageId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        return JSONObject()
            .put("status", "passed")
            .put("case", "cleanup")
            .put("scheduled", scheduled.scheduled)
            .put("redacted", purged.redacted)
            .put("purged", purged.purged)
            .put("processed_message_remaining", firstCount)
            .put("cleanup_state", cleanupState)
            .put("pending_message_body", secondBody)
            .put("duplicate_status", duplicate.status)
            .put("duplicate_message_id_matches", duplicate.messageId == first.messageId)
            .put("duplicate_seq_matches", duplicate.seq == first.seq)
            .put("audit_events", auditCount)
    }

    private fun messageCount(
        db: android.database.sqlite.SQLiteDatabase,
        scopeId: String,
        messageId: String,
    ): Int {
        return db.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE scope_id=? AND message_id=?",
            arrayOf(scopeId, messageId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun messageBody(
        db: android.database.sqlite.SQLiteDatabase,
        scopeId: String,
        messageId: String,
    ): String {
        return db.rawQuery(
            "SELECT COALESCE(body,'') FROM messages WHERE scope_id=? AND message_id=?",
            arrayOf(scopeId, messageId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else ""
        }
    }

    private fun cleanupState(
        db: android.database.sqlite.SQLiteDatabase,
        scopeId: String,
        messageId: String,
    ): String {
        return db.rawQuery(
            "SELECT state FROM message_cleanup WHERE scope_id=? AND message_id=?",
            arrayOf(scopeId, messageId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "missing"
        }
    }

    private fun nextExtractionJob(
        database: MemoryV2Database,
        sessionId: String,
    ): String? {
        return database.readableDatabase.rawQuery(
            "SELECT j.job_id FROM jobs j WHERE j.kind='extract' " +
                "AND j.session_id=? AND j.state='pending' ORDER BY (" +
                "SELECT COUNT(*) FROM dependencies d WHERE d.scope_id=j.scope_id " +
                "AND d.child_kind='job' AND d.child_id=j.job_id " +
                "AND d.parent_kind='message') ASC,j.created_at ASC LIMIT 1",
            arrayOf(sessionId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    companion object {
        private const val EXTRA_EXECUTE = "execute"
        private const val EXTRA_CASE = "case"
        private const val MAX_ROUNDS = 16
        private val FULL_SCENARIO = listOf(
            "晚上要不要一起去吃饭？",
            "我最喜欢吃的是草莓冰淇淋。",
            "明天我可能会去杭州出差。",
            "我不喜欢香菜。",
            "我们周六晚上七点去吃火锅吧。",
            "我昨天已经吃过晚饭了。",
            "他说明天可能下雨。",
            "记得提醒我周六带伞。",
            "明天要早起。",
        )
        private val TEMPORAL_SCENARIO = listOf(
            "明天要早起。",
        )
    }
}
