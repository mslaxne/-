package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.memory.v2.AppendMessageRequest
import com.local.wxbotprobe.memory.v2.LeasedMemoryJob
import com.local.wxbotprobe.memory.v2.MemoryCommandEvidence
import com.local.wxbotprobe.memory.v2.MemoryScopeKey
import com.local.wxbotprobe.memory.v2.MemoryV2BatchCommitService
import com.local.wxbotprobe.memory.v2.MemoryV2CallBudgetStore
import com.local.wxbotprobe.memory.v2.MemoryV2CommandService
import com.local.wxbotprobe.memory.v2.MemoryV2ConflictService
import com.local.wxbotprobe.memory.v2.MemoryV2Crypto
import com.local.wxbotprobe.memory.v2.MemoryV2Database
import com.local.wxbotprobe.memory.v2.MemoryV2DeepSeekBatchGenerator
import com.local.wxbotprobe.memory.v2.MemoryV2ErrorCode
import com.local.wxbotprobe.memory.v2.MemoryV2Exception
import com.local.wxbotprobe.memory.v2.MemoryV2JobService
import com.local.wxbotprobe.memory.v2.MemoryV2Repository
import com.local.wxbotprobe.memory.v2.MemoryV2ReconciliationPolicy
import com.local.wxbotprobe.memory.v2.PinMemoryCommand
import com.local.wxbotprobe.memory.v2.ResolvedScopeInput
import com.local.wxbotprobe.memory.v2.SaveMemoryCommand
import com.local.wxbotprobe.memory.v2.toJsonObject
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class DebugMemoryV2ReconciliationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val mode = intent?.getStringExtra(EXTRA_MODE)?.ifBlank { MODE_RECONCILE }
            ?: MODE_RECONCILE
        val requestedCase = intent?.getStringExtra(EXTRA_CASE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val pending = goAsync()
        Thread(
            {
                val result = runCatching {
                    when (mode) {
                        MODE_MIGRATE -> migrateIsolatedCopy(context.applicationContext)
                        MODE_MODEL -> runModelReconciliation(context.applicationContext)
                        MODE_OPERATION_SWEEP ->
                            runModelOperationSweep(
                                context.applicationContext,
                                requestedCase,
                            )
                        MODE_CONFLICT_FOLLOWUP ->
                            runConflictFollowupSweep(
                                context.applicationContext,
                                requestedCase,
                            )
                        MODE_SEED_CONFLICT -> seedUiConflict(context.applicationContext)
                        MODE_SEED_A0_CONFLICT ->
                            seedA0UiConflict(context.applicationContext)
                        MODE_CLEANUP_CONFLICT -> cleanupUiConflict(context.applicationContext)
                        MODE_PROBE_A0_PROPOSAL ->
                            probeA0Proposal(context.applicationContext)
                        MODE_CONTACT_A0 -> migrateContactToA0(context.applicationContext)
                        else -> runReconciliation(context.applicationContext)
                    }
                }
                val output = File(
                    context.filesDir,
                    "debug/memory-v2-reconciliation-$mode.json",
                )
                output.parentFile?.mkdirs()
                output.writeText(
                    result.fold(
                        onSuccess = { it.toString(2) },
                        onFailure = {
                            JSONObject()
                                .put("status", "failed")
                                .put("mode", mode)
                                .put("error", it.message.orEmpty())
                                .put("stack", it.stackTraceToString())
                                .toString(2)
                        },
                    ),
                    Charsets.UTF_8,
                )
                android.util.Log.i("MemoryV2Reconciliation", output.readText())
                pending.finish()
            },
            "memory-v2-reconciliation-$mode",
        ).start()
    }

    private fun migrateIsolatedCopy(context: Context): JSONObject {
        val source = context.getDatabasePath(MemoryV2Database.DATABASE_NAME)
        if (!source.isFile) {
            error("production database is missing: ${source.path}")
        }
        val backupDirectory = File(context.filesDir, "debug/migration-backup")
        backupDirectory.mkdirs()
        copyDatabaseFiles(source, backupDirectory, source.name)

        val testName = "memory-v2-migration-copy.db"
        context.deleteDatabase(testName)
        val test = context.getDatabasePath(testName)
        test.parentFile?.mkdirs()
        copyDatabaseFiles(source, test.parentFile!!, test.name)

        val before = readDatabaseSummary(test)
        val database = MemoryV2Database(context, testName)
        val db = database.writableDatabase
        val after = readDatabaseSummary(test)
        val quickCheck = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else ""
        }
        val foreignKeyViolations = db.rawQuery(
            "PRAGMA foreign_key_check",
            null,
        ).use { cursor -> cursor.count }
        val tables = db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' " +
                "AND name IN ('memory_conflicts','memory_relations')",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        val memoryColumns = db.rawQuery(
            "PRAGMA table_info(memories)",
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        db.close()
        val passed = before.schemaVersion == 2 &&
            after.schemaVersion == MemoryV2Database.DATABASE_VERSION &&
            before.messageCount == after.messageCount &&
            before.memoryCount == after.memoryCount &&
            quickCheck == "ok" &&
            foreignKeyViolations == 0 &&
            tables == 2 &&
            "retracted_at" in memoryColumns
        return JSONObject()
            .put("status", if (passed) "passed" else "failed")
            .put("source", source.path)
            .put("backup_directory", backupDirectory.path)
            .put("before", before.toJson())
            .put("after", after.toJson())
            .put("quick_check", quickCheck)
            .put("foreign_key_violations", foreignKeyViolations)
            .put("new_tables", tables)
            .put("has_retracted_at", "retracted_at" in memoryColumns)
    }

    private fun runReconciliation(context: Context): JSONObject {
        val databaseName = "memory-v2-reconciliation-self.db"
        context.deleteDatabase(databaseName)
        val database = MemoryV2Database(context, databaseName)
        val now = System.currentTimeMillis() / 1_000L
        val repository = MemoryV2Repository(context, database) { now }
        val scope = repository.ensureScope(
            resolved = ResolvedScopeInput(
                key = MemoryScopeKey(
                    ownerId = "owner-reconcile",
                    contactId = "contact-reconcile",
                    agentId = "agent-reconcile",
                    personaId = "persona-reconcile",
                    realmId = "real",
                ),
                personaRevision = 1,
            ),
            contactDisplayName = "Reconcile Contact",
        )
        val subjectId = "user-contact-reconcile"
        val first = append(context, repository, scope, "我已加入项目组", now + 1)
        val commands = MemoryV2CommandService(context, database) { now }
        val saved = commands.save(
            scope,
            SaveMemoryCommand(
                operationId = "reconcile-seed-a1",
                commandSeq = 1,
                tier = "A1",
                predicate = "relationship.status",
                subjectId = subjectId,
                viewpointId = null,
                text = "对方已加入项目组。",
                value = "已加入项目组",
                epistemic = "declared",
                evidence = listOf(
                    MemoryCommandEvidence(first.messageId, 1, "我已加入项目组"),
                ),
            ),
        )
        val a1 = saved.memoryId ?: error("seed A1 has no id")

        val relatedMessage = append(
            context,
            repository,
            scope,
            "同事你好～",
            now + 10,
        )
        val relationJob = enqueueAndLease(
            context,
            database,
            scope,
            relatedMessage.sessionId,
            relatedMessage.messageId,
            "relation",
        )
        val relationCandidate = candidate(
            id = "candidate-elaborate",
            operation = "RELATE",
            relationType = "ELABORATES",
            targetId = a1,
            expectedVersion = 1,
            subjectId = subjectId,
            predicate = "event.summary",
            text = "对方重复确认了你的称呼。",
            value = "对方亲昵地叫你同事同事",
            reason = "recent_event",
            quote = "同事你好～",
            messageId = relatedMessage.messageId,
            occurrence = now + 10,
        )
        val relationBatch = jobs(context, database).validateExtraction(
            relationJob,
            extractionOutput(relationJob, relationCandidate),
        )
        val relationResult = MemoryV2BatchCommitService(
            context,
            database,
        ) { now }.commitExtraction(relationJob, relationBatch)
        val relationCount = count(
            database,
            "SELECT COUNT(*) FROM memory_relations WHERE scope_id=? " +
                "AND target_memory_id=? AND relation_type='ELABORATES'",
            scope.scopeId,
            a1,
        )
        if (relationCount != 1 || relationResult.memoryIds.size != 1) {
            error("RELATE did not persist exactly one edge")
        }

        val divorceMessage = append(
            context,
            repository,
            scope,
            "我已经离婚了",
            now + 20,
        )
        val divorceJob = enqueueAndLease(
            context,
            database,
            scope,
            divorceMessage.sessionId,
            divorceMessage.messageId,
            "divorce",
        )
        val divorceCandidate = candidate(
            id = "candidate-divorce",
            operation = "SUPERSEDE",
            targetId = a1,
            expectedVersion = 1,
            subjectId = subjectId,
            predicate = "relationship.status",
            text = "对方已经离婚。",
            value = "离婚",
            reason = "durable_preference",
            quote = "我已经离婚了",
            messageId = divorceMessage.messageId,
            occurrence = now + 20,
        )
        val divorceBatch = jobs(context, database).validateExtraction(
            divorceJob,
            extractionOutput(divorceJob, divorceCandidate),
        )
        MemoryV2BatchCommitService(context, database) { now }
            .commitExtraction(divorceJob, divorceBatch)
        val superseded = memory(database, scope.scopeId, a1)
        if (superseded.version != 2 || superseded.value != "离婚") {
            error("SUPERSEDE failed: $superseded")
        }
        if (
            superseded.relationTargetVersion != 1 ||
            superseded.historyVersions != listOf(1)
        ) {
            error("history or relation version freeze failed: $superseded")
        }

        val conflictMessage = append(
            context,
            repository,
            scope,
            "我没有离婚，我们还好好的",
            now + 30,
        )
        val conflictJob = enqueueAndLease(
            context,
            database,
            scope,
            conflictMessage.sessionId,
            conflictMessage.messageId,
            "conflict",
        )
        val conflictCandidate = candidate(
            id = "candidate-conflict",
            operation = "CONTRADICT",
            targetId = a1,
            expectedVersion = 2,
            subjectId = subjectId,
            predicate = "relationship.status",
            text = "对方表示没有离婚。",
            value = "没有离婚",
            reason = "durable_preference",
            quote = "我没有离婚",
            messageId = conflictMessage.messageId,
            occurrence = now + 30,
        )
        val conflictBatch = jobs(context, database).validateExtraction(
            conflictJob,
            extractionOutput(conflictJob, conflictCandidate),
        )
        MemoryV2BatchCommitService(context, database) { now }
            .commitExtraction(conflictJob, conflictBatch)
        val conflicts = MemoryV2ConflictService(context, database) { now }
            .list(scope.scopeId)
        if (conflicts.size != 1) {
            error("CONTRADICT did not create one pending conflict")
        }
        val resolution = MemoryV2ConflictService(context, database) { now }
            .resolve(
                scope.scopeId,
                conflicts.single().conflictId,
                MemoryV2ConflictService.ACCEPT_CANDIDATE,
            )
        val resolved = memory(database, scope.scopeId, a1)
        if (
            resolution.status != "saved" ||
            resolved.version != 3 ||
            resolved.value != "没有离婚"
        ) {
            error("conflict resolution failed: $resolution $resolved")
        }

        val staleMessage = append(
            context,
            repository,
            scope,
            "我确实已经离婚了",
            now + 40,
        )
        val staleJob = enqueueAndLease(
            context,
            database,
            scope,
            staleMessage.sessionId,
            staleMessage.messageId,
            "stale",
        )
        val commandSeq = queryLong(
            database,
            "SELECT op_watermark FROM scopes WHERE scope_id=?",
            scope.scopeId,
        ).toInt() + 1
        val pinned = commands.pin(
            scope,
            PinMemoryCommand(
                operationId = "reconcile-pin-stale",
                commandSeq = commandSeq,
                memoryId = a1,
                expectedVersion = 3,
                pinned = true,
            ),
        )
        if (pinned.version != 4) {
            error("stale-test pin did not advance version")
        }
        val staleRejected = runCatching {
            jobs(context, database).validateExtraction(
                staleJob,
                JSONObject()
                    .put("contract", "ExtractionBatch")
                    .put("envelope", staleJob.envelope.toJsonObject())
                    .put(
                        "processed_message_ids",
                        JSONArray(listOf(staleMessage.messageId)),
                    )
                    .put("candidates", JSONArray())
                    .toString(),
            )
        }.exceptionOrNull()
        val staleError = staleRejected as? MemoryV2Exception
        if (staleError?.code != MemoryV2ErrorCode.STALE_EPOCH) {
            error("stale job was not rejected: $staleRejected")
        }
        val finalMemory = memory(database, scope.scopeId, a1)

        val quickCheck = database.readableDatabase.rawQuery(
            "PRAGMA quick_check",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else ""
        }
        val foreignKeyViolations = database.readableDatabase.rawQuery(
            "PRAGMA foreign_key_check",
            null,
        ).use { cursor -> cursor.count }
        if (quickCheck != "ok" || foreignKeyViolations != 0) {
            error("database integrity failed")
        }
        return JSONObject()
            .put("status", "passed")
            .put("database", databaseName)
            .put("schema_version", queryLong(
                database,
                "PRAGMA user_version",
            ))
            .put("a1_memory_id", a1)
            .put("a1_version", finalMemory.version)
            .put("a1_value", finalMemory.value)
            .put("relation_count", relationCount)
            .put("relation_target_version", superseded.relationTargetVersion)
            .put("history_versions", JSONArray(superseded.historyVersions))
            .put("pending_conflicts_after_resolution", count(
                database,
                "SELECT COUNT(*) FROM memory_conflicts WHERE scope_id=? " +
                    "AND state='pending'",
                scope.scopeId,
            ))
            .put("stale_error", staleError.code.name)
            .put("quick_check", quickCheck)
            .put("foreign_key_violations", foreignKeyViolations)
    }

    private fun runModelReconciliation(context: Context): JSONObject {
        val databaseName = "memory-v2-reconciliation-model.db"
        context.deleteDatabase(databaseName)
        val database = MemoryV2Database(context, databaseName)
        val now = System.currentTimeMillis() / 1_000L
        val repository = MemoryV2Repository(context, database) { now }
        val scope = repository.ensureScope(
            resolved = ResolvedScopeInput(
                key = MemoryScopeKey(
                    ownerId = "owner-model-reconcile",
                    contactId = "contact-model-reconcile",
                    agentId = "agent-model-reconcile",
                    personaId = "persona-model-reconcile",
                    realmId = "real",
                ),
                personaRevision = 1,
            ),
            contactDisplayName = "Model Reconcile",
        )
        val subjectId = "user-contact-model-reconcile"
        val seedMessage = append(
            context,
            repository,
            scope,
            "我已加入项目组",
            now + 1,
        )
        val saved = MemoryV2CommandService(context, database) { now }.save(
            scope,
            SaveMemoryCommand(
                operationId = "model-probe-seed-a1",
                commandSeq = 1,
                tier = "A1",
                predicate = "relationship.status",
                subjectId = subjectId,
                viewpointId = null,
                text = "对方已加入项目组。",
                value = "已加入项目组",
                epistemic = "declared",
                evidence = listOf(
                    MemoryCommandEvidence(seedMessage.messageId, 1, "我已加入项目组"),
                ),
            ),
        )
        val a1 = saved.memoryId ?: error("model probe A1 has no id")
        val updateMessage = append(
            context,
            repository,
            scope,
            "我已经离婚了，现在是单身",
            now + 10,
        )
        val job = enqueueAndLease(
            context,
            database,
            scope,
            updateMessage.sessionId,
            updateMessage.messageId,
            "model",
        )
        val budget = MemoryV2CallBudgetStore(context)
        val realNow = System.currentTimeMillis() / 1_000L
        if (!budget.tryAcquire(job.scopeId, realNow)) {
            jobs(context, database).cancelForReschedule(
                job,
                MemoryV2ErrorCode.CALL_BUDGET_EXHAUSTED,
            )
            return JSONObject()
                .put("status", "budget_exhausted")
                .put("database", databaseName)
        }
        val client = ModelClientFactory.create(context)
            ?: error("model client is not configured")
        val raw = try {
            MemoryV2DeepSeekBatchGenerator(context, database, client, { now })
                .generate(job)
        } catch (error: Throwable) {
            budget.release(job.scopeId, realNow)
            jobs(context, database).fail(
                job,
                if (error is MemoryV2Exception) {
                    error.code.name
                } else {
                    MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name
                },
            )
            throw error
        }
        val batch = jobs(context, database).validateExtraction(job, raw)
        val operations = JSONArray().apply {
            batch.candidates.forEach { candidate ->
                put(
                    JSONObject()
                        .put("candidate_id", candidate.candidateId)
                        .put("operation", candidate.operation)
                        .put("relation_type", candidate.relationType ?: JSONObject.NULL)
                        .put("target_memory_id", candidate.targetMemoryId ?: JSONObject.NULL)
                        .put("expected_version", candidate.expectedVersion ?: JSONObject.NULL)
                        .put("predicate", candidate.predicate)
                        .put("value_json", candidate.valueJson)
                        .put("reason", candidate.reason),
                )
            }
        }
        if (batch.candidates.isNotEmpty()) {
            MemoryV2BatchCommitService(context, database) { now }
                .commitExtraction(job, batch)
        } else {
            jobs(context, database).completeEmpty(job, batch)
        }
        val current = memory(database, scope.scopeId, a1)
        val pendingConflicts = MemoryV2ConflictService(context, database) { now }
            .list(scope.scopeId)
        return JSONObject()
            .put("status", "passed")
            .put("database", databaseName)
            .put("a1_memory_id", a1)
            .put("a1_version", current.version)
            .put("a1_value", current.value)
            .put("candidate_count", batch.candidates.size)
            .put("operations", operations)
            .put("pending_conflicts", pendingConflicts.size)
            .put("raw_response", raw)
    }

    private fun runModelOperationSweep(
        context: Context,
        requestedCase: String?,
    ): JSONObject {
        val startedAt = System.currentTimeMillis() / 1_000L
        val runId = "operation-sweep-$startedAt"
        val databaseName = "memory-v2-$runId.db"
        val database = MemoryV2Database(context, databaseName)
        val client = ModelClientFactory.create(context)
            ?: error("model client is not configured")
        val cases = requestedCase?.let { id ->
            MODEL_OPERATION_CASES.filter { it.id == id }.ifEmpty {
                error("unknown operation case: $id")
            }
        } ?: MODEL_OPERATION_CASES
        val results = JSONArray()
        cases.forEachIndexed { index, case ->
            val result = runCatching {
                runModelOperationCase(
                    context = context,
                    database = database,
                    client = client,
                    case = case,
                    index = index,
                    startedAt = startedAt,
                )
            }.getOrElse { error ->
                JSONObject()
                    .put("case", case.id)
                    .put("expected_operations", JSONArray(case.expected))
                    .put("message", case.message)
                    .put("status", "failed")
                    .put("error", error.message.orEmpty())
                    .put("stack", error.stackTraceToString())
            }
            results.put(result)
        }
        val rows = (0 until results.length())
            .map { results.getJSONObject(it) }
        val passed = rows.count { it.optString("status") == "passed" }
        val quickCheck = database.readableDatabase.rawQuery(
            "PRAGMA quick_check",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else ""
        }
        val foreignKeyViolations = database.readableDatabase.rawQuery(
            "PRAGMA foreign_key_check",
            null,
        ).use { cursor -> cursor.count }
        return JSONObject()
            .put("status", "completed")
            .put("run_id", runId)
            .put("requested_case", requestedCase ?: JSONObject.NULL)
            .put("database", databaseName)
            .put("case_count", rows.size)
            .put("passed_cases", passed)
            .put("failed_cases", rows.size - passed)
            .put("all_passed", passed == rows.size)
            .put("quick_check", quickCheck)
            .put("foreign_key_violations", foreignKeyViolations)
            .put("results", results)
    }

    private fun runConflictFollowupSweep(
        context: Context,
        requestedCase: String?,
    ): JSONObject {
        val startedAt = System.currentTimeMillis() / 1_000L
        val runId = "conflict-followup-$startedAt"
        val databaseName = "memory-v2-$runId.db"
        val database = MemoryV2Database(context, databaseName)
        val client = ModelClientFactory.create(context)
            ?: error("model client is not configured")
        val cases = requestedCase?.let { id ->
            MODEL_CONFLICT_FOLLOWUP_CASES.filter { it.id == id }.ifEmpty {
                error("unknown conflict follow-up case: $id")
            }
        } ?: MODEL_CONFLICT_FOLLOWUP_CASES
        val results = JSONArray()
        cases.forEachIndexed { index, case ->
            val result = runCatching {
                val now = startedAt - 500L + index * 100L
                val repository = MemoryV2Repository(context, database) { now }
                val scope = repository.ensureScope(
                    resolved = ResolvedScopeInput(
                        key = MemoryScopeKey(
                            ownerId = "owner-conflict-${case.id}",
                            contactId = "contact-conflict-${case.id}",
                            agentId = "agent-conflict",
                            personaId = "persona-conflict",
                            realmId = "real",
                        ),
                        personaRevision = 1,
                    ),
                    contactDisplayName = "Conflict ${case.id}",
                )
                val db = database.readableDatabase
                val subjectId = db.rawQuery(
                    "SELECT entity_id FROM entities WHERE scope_id=? " +
                        "AND kind='user' ORDER BY entity_id LIMIT 1",
                    arrayOf(scope.scopeId),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        error("${case.id} isolated contact entity is missing")
                    }
                    cursor.getString(0)
                }
                val seedMessage = append(
                    context,
                    repository,
                    scope,
                    case.seedText,
                    now,
                )
                val memoryId = seedFixtureMemory(
                    database = database,
                    scope = scope,
                    subjectId = subjectId,
                    predicate = "profile.city",
                    text = case.seedText,
                    value = "杭州",
                    messageId = seedMessage.messageId,
                    now = now,
                    caseId = case.id,
                )
                val conflictMessage = append(
                    context,
                    repository,
                    scope,
                    case.conflictText,
                    now + 10,
                )
                val conflictJob = enqueueAndLease(
                    context = context,
                    database = database,
                    scope = scope,
                    sessionId = conflictMessage.sessionId,
                    messageId = conflictMessage.messageId,
                    suffix = "conflict-seed-${case.id}",
                    targetVersions = mapOf(memoryId to 1),
                )
                val conflictCandidate = candidate(
                    id = "conflict-${case.id}",
                    operation = "CONTRADICT",
                    targetId = memoryId,
                    expectedVersion = 1,
                    subjectId = subjectId,
                    predicate = "profile.city",
                    text = "对方说可能搬到上海，但尚未确认。",
                    value = "上海（待确认）",
                    reason = "durable_preference",
                    quote = case.conflictText,
                    messageId = conflictMessage.messageId,
                    occurrence = now + 10,
                )
                val conflictBatch = jobs(context, database).validateExtraction(
                    conflictJob,
                    extractionOutput(conflictJob, conflictCandidate),
                )
                MemoryV2BatchCommitService(context, database) { now }
                    .commitExtraction(conflictJob, conflictBatch)
                val pendingBefore = MemoryV2ConflictService(
                    context,
                    database,
                ) { now }.list(scope.scopeId)
                if (pendingBefore.size != 1) {
                    error("${case.id} did not create exactly one pending conflict")
                }

                val followupMessage = append(
                    context,
                    repository,
                    scope,
                    case.followupText,
                    now + 20,
                )
                val followupJob = enqueueAndLease(
                    context = context,
                    database = database,
                    scope = scope,
                    sessionId = followupMessage.sessionId,
                    messageId = followupMessage.messageId,
                    suffix = "followup-${case.id}",
                    targetVersions = mapOf(memoryId to 1),
                )
                val budget = MemoryV2CallBudgetStore(context)
                val realNow = System.currentTimeMillis() / 1_000L
                if (!budget.tryAcquire(followupJob.scopeId, realNow)) {
                    error("${case.id} hourly model budget exhausted")
                }
                val raw = MemoryV2DeepSeekBatchGenerator(
                    context,
                    database,
                    client,
                    { now },
                ).generate(followupJob)
                val followupBatch = jobs(context, database).validateExtraction(
                    followupJob,
                    raw,
                )
                val commitStatus = if (followupBatch.candidates.isEmpty()) {
                    jobs(context, database).completeEmpty(
                        followupJob,
                        followupBatch,
                    )
                    "empty"
                } else {
                    MemoryV2BatchCommitService(context, database) { now }
                        .commitExtraction(followupJob, followupBatch)
                        .status
                }
                val after = memory(database, scope.scopeId, memoryId)
                val conflictOutcome = db.rawQuery(
                    "SELECT state,resolution FROM memory_conflicts " +
                        "WHERE scope_id=? AND memory_id=? " +
                        "ORDER BY created_at DESC LIMIT 1",
                    arrayOf(scope.scopeId, memoryId),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        JSONObject()
                    } else {
                        JSONObject()
                            .put("state", cursor.getString(0).orEmpty())
                            .put("resolution", cursor.getString(1).orEmpty())
                    }
                }
                val passed = after.version == case.expectedVersion &&
                    after.value == case.expectedValue &&
                    conflictOutcome.optString("state") == "resolved" &&
                    conflictOutcome.optString("resolution") == case.expectedResolution
                JSONObject()
                    .put("case", case.id)
                    .put("status", if (passed) "passed" else "failed")
                    .put("followup_message", case.followupText)
                    .put("candidate_operations", JSONArray(
                        followupBatch.candidates.map { it.operation },
                    ))
                    .put("commit_status", commitStatus)
                    .put("memory_version", after.version)
                    .put("memory_value", after.value)
                    .put("expected_version", case.expectedVersion)
                    .put("expected_value", case.expectedValue)
                    .put("conflict_outcome", conflictOutcome)
                    .put("raw_response", raw.take(12_000))
            }.getOrElse { error ->
                JSONObject()
                    .put("case", case.id)
                    .put("status", "failed")
                    .put("error", error.message.orEmpty())
                    .put("stack", error.stackTraceToString())
            }
            results.put(result)
        }
        val rows = (0 until results.length()).map { results.getJSONObject(it) }
        val passed = rows.count { it.optString("status") == "passed" }
        return JSONObject()
            .put("status", "completed")
            .put("run_id", runId)
            .put("requested_case", requestedCase ?: JSONObject.NULL)
            .put("database", databaseName)
            .put("case_count", rows.size)
            .put("passed_cases", passed)
            .put("failed_cases", rows.size - passed)
            .put("all_passed", passed == rows.size)
            .put("results", results)
    }

    private fun runModelOperationCase(
        context: Context,
        database: MemoryV2Database,
        client: com.local.wxbotprobe.bot.ChatClient,
        case: ModelOperationCase,
        index: Int,
        startedAt: Long,
    ): JSONObject {
        val now = startedAt - 1_000L + index * 100L
        val repository = MemoryV2Repository(context, database) { now }
        val scope = repository.ensureScope(
            resolved = ResolvedScopeInput(
                key = MemoryScopeKey(
                    ownerId = "owner-sweep-${case.id}",
                    contactId = "contact-sweep-${case.id}",
                    agentId = "agent-sweep",
                    personaId = "persona-sweep",
                    realmId = "real",
                ),
                personaRevision = 1,
            ),
            contactDisplayName = "Sweep ${case.id}",
        )
        val subjectId = database.readableDatabase.rawQuery(
            "SELECT entity_id FROM entities WHERE scope_id=? AND kind='user' " +
                "ORDER BY entity_id LIMIT 1",
            arrayOf(scope.scopeId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                error("${case.id} isolated contact entity is missing")
            }
            cursor.getString(0)
        }
        var targetId: String? = null
        var targetVersion: Int? = null
        if (case.seedPredicate != null) {
            val seedMessage = append(
                context,
                repository,
                scope,
                case.seedText,
                now,
            )
            targetId = seedFixtureMemory(
                database = database,
                scope = scope,
                subjectId = subjectId,
                predicate = case.seedPredicate,
                text = case.seedText,
                value = case.seedValue,
                messageId = seedMessage.messageId,
                now = now,
                caseId = case.id,
            )
            targetVersion = 1
        }
        val newMessage = append(
            context,
            repository,
            scope,
            case.message,
            now + 10,
        )
        val targetVersions = if (targetId != null && targetVersion != null) {
            mapOf(targetId to targetVersion)
        } else {
            emptyMap()
        }
        val job = enqueueAndLease(
            context = context,
            database = database,
            scope = scope,
            sessionId = newMessage.sessionId,
            messageId = newMessage.messageId,
            suffix = "operation-${case.id}",
            targetVersions = targetVersions,
        )
        val budget = MemoryV2CallBudgetStore(context)
        val realNow = System.currentTimeMillis() / 1_000L
        if (!budget.tryAcquire(job.scopeId, realNow)) {
            jobs(context, database).cancelForReschedule(
                job,
                MemoryV2ErrorCode.CALL_BUDGET_EXHAUSTED,
            )
            return JSONObject()
                .put("case", case.id)
                .put("expected_operations", JSONArray(case.expected))
                .put("message", case.message)
                .put("status", "budget_exhausted")
        }
        val modelStarted = System.currentTimeMillis()
        val raw = try {
            MemoryV2DeepSeekBatchGenerator(
                context,
                database,
                client,
                { now },
            ).generate(job)
        } catch (error: Throwable) {
            budget.release(job.scopeId, realNow)
            jobs(context, database).fail(
                job,
                if (error is MemoryV2Exception) {
                    error.code.name
                } else {
                    MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name
                },
            )
            return JSONObject()
                .put("case", case.id)
                .put("expected_operations", JSONArray(case.expected))
                .put("message", case.message)
                .put("status", "failed")
                .put("error", error.message.orEmpty())
        }
        val batch = runCatching {
            jobs(context, database).validateExtraction(job, raw)
        }.getOrElse { error ->
            jobs(context, database).fail(
                job,
                if (error is MemoryV2Exception) {
                    error.code.name
                } else {
                    MemoryV2ErrorCode.SCHEMA_INVALID.name
                },
            )
            return JSONObject()
                .put("case", case.id)
                .put("expected_operations", JSONArray(case.expected))
                .put("message", case.message)
                .put("status", "failed")
                .put("error", error.message.orEmpty())
                .put("validation_error", (error as? MemoryV2Exception)?.code?.name)
                .put("raw_response", raw.take(8_000))
        }
        val candidates = JSONArray().apply {
            batch.candidates.forEach { candidate ->
                put(
                    JSONObject()
                        .put("candidate_id", candidate.candidateId)
                        .put("operation", candidate.operation)
                        .put("relation_type", candidate.relationType ?: JSONObject.NULL)
                        .put("target_memory_id", candidate.targetMemoryId ?: JSONObject.NULL)
                        .put("expected_version", candidate.expectedVersion ?: JSONObject.NULL)
                        .put("predicate", candidate.predicate)
                        .put("text", candidate.text)
                        .put("value_json", candidate.valueJson)
                )
            }
        }
        val expectedCandidate = if (case.expected.isEmpty()) {
            batch.candidates.isEmpty()
        } else {
            batch.candidates.any { candidate ->
                candidate.operation in case.expected &&
                    if (case.requiresTarget) {
                        candidate.targetMemoryId == targetId
                    } else {
                        candidate.targetMemoryId == null
                    } &&
                    if (case.requiresRelation) {
                        candidate.relationType != null
                    } else {
                        true
                    }
            }
        }
        val noopAllowsEmpty =
            MemoryV2ReconciliationPolicy.NOOP in case.expected &&
                batch.candidates.isEmpty()
        val passed = expectedCandidate || noopAllowsEmpty
        val commitStatus = runCatching {
            if (batch.candidates.isEmpty()) {
                jobs(context, database).completeEmpty(job, batch)
                "empty"
            } else {
                MemoryV2BatchCommitService(context, database) { now }
                    .commitExtraction(job, batch)
                    .status
            }
        }.getOrElse { error ->
            "failed:${error.message.orEmpty()}"
        }
        val targetAfter = targetId?.let { memory(database, scope.scopeId, it) }
        val relationCount = if (targetId == null) {
            0
        } else {
            count(
                database,
                "SELECT COUNT(*) FROM memory_relations WHERE scope_id=? " +
                    "AND target_memory_id=?",
                scope.scopeId,
                targetId,
            )
        }
        val memoryCount = count(
            database,
            "SELECT COUNT(*) FROM memories WHERE scope_id=?",
            scope.scopeId,
        )
        val expectedTargetValueMatches =
            case.expectedTargetValue == null ||
                targetAfter?.value == case.expectedTargetValue
        val passedWithCommit =
            passed && expectedTargetValueMatches &&
                !commitStatus.startsWith("failed:")
        return JSONObject()
            .put("case", case.id)
            .put("expected_operations", JSONArray(case.expected))
            .put("requires_target", case.requiresTarget)
            .put("requires_relation", case.requiresRelation)
            .put("message", case.message)
            .put("target_memory_id", targetId ?: JSONObject.NULL)
            .put("target_version", targetVersion ?: JSONObject.NULL)
            .put("candidates", candidates)
            .put("commit_status", commitStatus)
            .put("target_after", targetAfter?.toJsonObject() ?: JSONObject.NULL)
            .put(
                "expected_target_value",
                case.expectedTargetValue ?: JSONObject.NULL,
            )
            .put("relation_count", relationCount)
            .put("memory_count", memoryCount)
            .put("latency_ms", System.currentTimeMillis() - modelStarted)
            .put("status", if (passedWithCommit) "passed" else "failed")
    }

    private fun seedUiConflict(context: Context): JSONObject {
        return seedUiConflict(context, MemoryV2Database(context), "A1")
    }

    private fun seedA0UiConflict(context: Context): JSONObject {
        return seedUiConflict(context, MemoryV2Database(context), "A0")
    }

    private fun seedUiConflict(
        context: Context,
        database: MemoryV2Database,
        expectedTier: String,
    ): JSONObject {
        val db = database.writableDatabase
        val source = db.rawQuery(
            "SELECT scope_id,memory_id,version,subject_id,viewpoint_id," +
                "predicate,text,value_json,tier FROM memories " +
                "WHERE lifecycle='active' AND retracted_at IS NULL AND tier=? " +
                "ORDER BY updated_at DESC,memory_id LIMIT 1",
            arrayOf(expectedTier),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                error("no active $expectedTier memory available for UI conflict probe")
            }
            UiConflictSource(
                scopeId = cursor.getString(0),
                memoryId = cursor.getString(1),
                version = cursor.getInt(2),
                subjectId = cursor.getString(3),
                viewpointId = if (cursor.isNull(4)) null else cursor.getString(4),
                predicate = cursor.getString(5),
                text = cursor.getString(6),
                valueJson = cursor.getString(7),
                tier = cursor.getString(8),
            )
        }
        val conflictId = "debug-${expectedTier.lowercase()}-conflict-" +
            System.currentTimeMillis()
        val evidence = db.rawQuery(
            "SELECT source_message_id,source_revision,quote FROM evidence " +
                "WHERE scope_id=? AND memory_id=? ORDER BY evidence_id LIMIT 1",
            arrayOf(source.scopeId, source.memoryId),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                JSONArray().put(
                    JSONObject()
                        .put("message_id", cursor.getString(0))
                        .put("source_revision", cursor.getInt(1))
                        .put("quote", cursor.getString(2)),
                )
            } else {
                JSONArray()
            }
        }
        val candidateText = if (source.tier == "A0") {
            "A0 审核提案：隔离验证中的核心关系更新。"
        } else {
            "测试候选：这条内容不应自动写入。"
        }
        val candidateValue = if (source.tier == "A0") {
            "核心关系审核提案验证"
        } else {
            "测试候选"
        }
        val candidate = JSONObject()
            .put("candidate_id", conflictId)
            .put("operation", "CONTRADICT")
            .put("subject_id", source.subjectId)
            .put("viewpoint_id", source.viewpointId ?: JSONObject.NULL)
            .put("predicate", source.predicate)
            .put("tier", source.tier)
            .put("text", candidateText)
            .put("value_json", org.json.JSONObject.quote(candidateValue))
            .put("epistemic", "declared")
            .put("reason", "important_experience")
            .put("importance_hint", 0.5)
            .put(
                "temporal_json",
                SaveMemoryCommand.EMPTY_TEMPORAL,
            )
            .put("commitment_state", JSONObject.NULL)
            .put("evidence", evidence)
        db.insertOrThrow(
            "memory_conflicts",
            null,
            android.content.ContentValues().apply {
                put("scope_id", source.scopeId)
                put("conflict_id", conflictId)
                put("memory_id", source.memoryId)
                put("target_version", source.version)
                put("operation", "CONTRADICT")
                put("candidate_json", candidate.toString())
                put("state", "pending")
                putNull("resolution")
                putNull("resolved_at")
                put("created_at", System.currentTimeMillis() / 1_000L)
                put("updated_at", System.currentTimeMillis() / 1_000L)
            },
        )
        return JSONObject()
            .put("status", "passed")
            .put("conflict_id", conflictId)
            .put("scope_id", source.scopeId)
            .put("memory_id", source.memoryId)
            .put("target_version", source.version)
            .put("existing_text", source.text)
            .put("existing_tier", source.tier)
    }

    private fun probeA0Proposal(context: Context): JSONObject {
        val source = context.getDatabasePath(MemoryV2Database.DATABASE_NAME)
        if (!source.isFile) {
            error("production database is missing: ${source.path}")
        }
        val testName = "memory-v2-a0-proposal-probe.db"
        context.deleteDatabase(testName)
        val test = context.getDatabasePath(testName)
        test.parentFile?.mkdirs()
        copyDatabaseFiles(source, test.parentFile!!, test.name)
        val database = MemoryV2Database(context, testName)
        val db = database.writableDatabase
        try {
            val seeded = seedUiConflict(context, database, "A0")
            val scopeId = seeded.getString("scope_id")
            val conflictId = seeded.getString("conflict_id")
            val memoryId = seeded.getString("memory_id")
            val before = db.rawQuery(
                "SELECT version,text,pinned FROM memories " +
                    "WHERE scope_id=? AND memory_id=?",
                arrayOf(scopeId, memoryId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    error("isolated A0 memory was not found")
                }
                Triple(cursor.getInt(0), cursor.getString(1), cursor.getInt(2))
            }
            val conflicts = MemoryV2ConflictService(context, database)
            val proposal = conflicts.list(scopeId)
                .firstOrNull { it.conflictId == conflictId }
                ?: error("isolated A0 proposal was not listed")
            val unconfirmedRejected = runCatching {
                conflicts.resolve(
                    scopeId = scopeId,
                    conflictId = conflictId,
                    decision = MemoryV2ConflictService.ACCEPT_CANDIDATE,
                    a0Confirmed = false,
                )
            }.exceptionOrNull()?.let { error ->
                error is MemoryV2Exception &&
                    error.code == MemoryV2ErrorCode.FIELD_NOT_EDITABLE
            } == true
            val accepted = conflicts.resolve(
                scopeId = scopeId,
                conflictId = conflictId,
                decision = MemoryV2ConflictService.ACCEPT_CANDIDATE,
                a0Confirmed = true,
            )
            val after = db.rawQuery(
                "SELECT version,text,pinned FROM memories " +
                    "WHERE scope_id=? AND memory_id=?",
                arrayOf(scopeId, memoryId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    error("accepted A0 memory was not found")
                }
                Triple(cursor.getInt(0), cursor.getString(1), cursor.getInt(2))
            }
            val evidenceCount = db.rawQuery(
                "SELECT COUNT(*) FROM evidence WHERE scope_id=? AND memory_id=?",
                arrayOf(scopeId, memoryId),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            val historyCount = db.rawQuery(
                "SELECT COUNT(*) FROM memory_history " +
                    "WHERE scope_id=? AND memory_id=?",
                arrayOf(scopeId, memoryId),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            val conflictState = db.rawQuery(
                "SELECT state FROM memory_conflicts " +
                    "WHERE scope_id=? AND conflict_id=?",
                arrayOf(scopeId, conflictId),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else ""
            }
            val passed = unconfirmedRejected &&
                accepted.status == "saved" &&
                proposal.isA0Proposal &&
                proposal.evidenceCount > 0 &&
                after.first == before.first + 1 &&
                after.second == proposal.candidateText &&
                after.third == 1 &&
                evidenceCount > 0 &&
                historyCount > 0 &&
                conflictState == "resolved"
            return JSONObject()
                .put("status", if (passed) "passed" else "failed")
                .put("isolated_database", test.path)
                .put("conflict_id", conflictId)
                .put("scope_id", scopeId)
                .put("memory_id", memoryId)
                .put("proposal_tier", proposal.existingTier)
                .put("evidence_count", proposal.evidenceCount)
                .put("history_count", historyCount)
                .put("unconfirmed_rejected", unconfirmedRejected)
                .put("accepted_status", accepted.status)
                .put("before_version", before.first)
                .put("after_version", after.first)
                .put("before_text", before.second)
                .put("after_text", after.second)
                .put("pinned", after.third == 1)
                .put("conflict_state", conflictState)
        } finally {
            database.close()
            context.deleteDatabase(testName)
        }
    }

    private fun cleanupUiConflict(context: Context): JSONObject {
        val db = MemoryV2Database(context).writableDatabase
        val deleted = db.delete(
            "memory_conflicts",
            "conflict_id LIKE 'debug-%'",
            null,
        )
        return JSONObject()
            .put("status", "passed")
            .put("deleted", deleted)
    }

    private fun migrateContactToA0(context: Context): JSONObject {
        val database = MemoryV2Database(context)
        val db = database.writableDatabase
        val identity = db.rawQuery(
            "SELECT s.scope_id,s.contact_id,e.entity_id,e.display_name " +
                "FROM scopes s JOIN entities e ON e.scope_id=s.scope_id " +
                "WHERE e.kind='user' AND e.display_name='A' " +
                "ORDER BY s.created_at LIMIT 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                error("contact A was not found")
            }
            ContactRecord(
                scopeId = cursor.getString(0),
                contactId = cursor.getString(1),
                entityId = cursor.getString(2),
                displayName = cursor.getString(3),
            )
        }
        val now = System.currentTimeMillis() / 1_000L
        val repository = MemoryV2Repository(context, database) { now }
        val scope = repository.scopeById(identity.scopeId)
        val confirmation = db.rawQuery(
            "SELECT m.message_id,m.session_id,m.revision FROM messages m " +
                "WHERE m.scope_id=? AND m.client_message_id=? AND m.role='config' " +
                "ORDER BY EXISTS(SELECT 1 FROM evidence e " +
                "WHERE e.scope_id=m.scope_id AND e.source_message_id=m.message_id) DESC, " +
                "m.received_at,m.message_id LIMIT 1",
            arrayOf(identity.scopeId, CONTACT_CONFIRMATION_CLIENT_ID),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                ConfirmationMessage(
                    messageId = cursor.getString(0),
                    sessionId = cursor.getString(1),
                    revision = cursor.getInt(2),
                )
            } else {
                val appended = repository.appendMessage(
                    scope,
                    AppendMessageRequest(
                        clientMessageId = CONTACT_CONFIRMATION_CLIENT_ID,
                        sessionId = null,
                        role = "config",
                        text = CONTACT_CONFIRMATION_TEXT,
                        occurredAt = now,
                        receivedAt = now,
                        timezone = "Asia/Shanghai",
                    ),
                )
                ConfirmationMessage(
                    messageId = appended.messageId,
                    sessionId = appended.sessionId,
                    revision = 1,
                )
            }
        }
        db.update(
            "messages",
            android.content.ContentValues().apply {
                put("summary_state", "done")
            },
            "scope_id=? AND message_id=?",
            arrayOf(identity.scopeId, confirmation.messageId),
        )
        db.insertWithOnConflict(
            "coverage",
            null,
            android.content.ContentValues().apply {
                put("scope_id", identity.scopeId)
                put("session_id", confirmation.sessionId)
                put("message_id", confirmation.messageId)
                put("kind", "summary")
                put("source_revision", confirmation.revision)
                put("state", "done")
                putNull("job_id")
                put("reason", "HOST_CONFIRMATION")
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        val commandSeq = queryLong(
            database,
            "SELECT op_watermark FROM scopes WHERE scope_id=?",
            identity.scopeId,
        ).toInt() + 1
        val existingA0 = db.rawQuery(
            "SELECT memory_id FROM memories WHERE scope_id=? AND tier='A0' " +
                "AND predicate='relationship.role' AND value_json='\"同事\"' " +
                "AND retracted_at IS NULL LIMIT 1",
            arrayOf(identity.scopeId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (existingA0 == null) {
            MemoryV2CommandService(context, database) { now }.save(
                scope,
                SaveMemoryCommand(
                    operationId = "user-confirmed-contact-a0",
                    commandSeq = commandSeq,
                    tier = "A0",
                    predicate = "relationship.role",
                    subjectId = identity.entityId,
                    viewpointId = null,
                    text = "对方是你的同事。",
                    value = "同事",
                    epistemic = "declared",
                    evidence = listOf(
                        MemoryCommandEvidence(
                            messageId = confirmation.messageId,
                            sourceRevision = 1,
                            quote = CONTACT_CONFIRMATION_TEXT,
                        ),
                    ),
                    pinned = true,
                    coreAuthorized = true,
                ),
            )
        }
        val duplicateConfirmations = db.rawQuery(
            "SELECT m.message_id FROM messages m WHERE m.scope_id=? " +
                "AND m.client_message_id=? AND m.message_id<>? " +
                "AND NOT EXISTS(SELECT 1 FROM evidence e " +
                "WHERE e.scope_id=m.scope_id AND e.source_message_id=m.message_id)",
            arrayOf(
                identity.scopeId,
                CONTACT_CONFIRMATION_CLIENT_ID,
                confirmation.messageId,
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        duplicateConfirmations.forEach { messageId ->
            db.delete(
                "dependencies",
                "scope_id=? AND parent_kind='message' AND parent_id=?",
                arrayOf(identity.scopeId, messageId),
            )
            db.delete(
                "messages",
                "scope_id=? AND message_id=?",
                arrayOf(identity.scopeId, messageId),
            )
        }
        val oldFact = db.rawQuery(
            "SELECT memory_id,version,subject_id,predicate,text FROM memories " +
                "WHERE scope_id=? AND tier IN ('A1','A2') " +
                "AND value_json='\"已加入项目组\"' " +
                "AND retracted_at IS NULL ORDER BY updated_at DESC LIMIT 1",
            arrayOf(identity.scopeId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                OldMembershipFact(
                    memoryId = cursor.getString(0),
                    version = cursor.getInt(1),
                    subjectId = cursor.getString(2),
                    predicate = cursor.getString(3),
                    text = cursor.getString(4),
                )
            }
        }
        var retiredId: String? = null
        if (oldFact != null) {
            val jobs = MemoryV2JobService(
                context = context,
                database = database,
                now = { now },
                summaryMigrationEnabled = false,
                extractionMigrationEnabled = false,
            )
            val queued = jobs.enqueue(
                scope = scope,
                sessionId = confirmation.sessionId,
                kind = "extract",
                sourceIds = listOf(confirmation.messageId),
                targetVersions = mapOf(oldFact.memoryId to oldFact.version),
            )
            val job = jobs.leaseJob("debug-contact-a0", queued.jobId)
                ?: error("contact migration job was not leased")
            val candidate = candidate(
                id = "retract-old-membership",
                operation = "RETRACT",
                targetId = oldFact.memoryId,
                expectedVersion = oldFact.version,
                subjectId = oldFact.subjectId,
                predicate = oldFact.predicate,
                text = oldFact.text,
                value = "已加入项目组",
                reason = "durable_preference",
                quote = "用户确认：同事 A 和我属于同一项目组。",
                messageId = confirmation.messageId,
                occurrence = now,
            )
            val batch = jobs.validateExtraction(
                job,
                extractionOutput(job, candidate),
            )
            MemoryV2BatchCommitService(context, database) { now }
                .commitExtraction(job, batch)
            retiredId = oldFact.memoryId
        }
        val a0 = db.rawQuery(
            "SELECT memory_id,version,text,value_json,pinned FROM memories " +
                "WHERE scope_id=? AND tier='A0' AND predicate='relationship.role' " +
                "AND retracted_at IS NULL ORDER BY updated_at DESC LIMIT 1",
            arrayOf(identity.scopeId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                error("A0 contact memory was not created")
            }
            JSONObject()
                .put("memory_id", cursor.getString(0))
                .put("version", cursor.getInt(1))
                .put("text", cursor.getString(2))
                .put("value", decodeValue(cursor.getString(3)))
                .put("pinned", cursor.getInt(4) == 1)
        }
        val retired = retiredId?.let { memoryId ->
            db.rawQuery(
                "SELECT version,retracted_at FROM memories " +
                    "WHERE scope_id=? AND memory_id=?",
                arrayOf(identity.scopeId, memoryId),
            ).use { cursor ->
                cursor.moveToFirst()
                JSONObject()
                    .put("memory_id", memoryId)
                    .put("version", cursor.getInt(0))
                    .put("retracted", !cursor.isNull(1))
            }
        } ?: JSONObject().put("status", "already_retracted")
        return JSONObject()
            .put("status", "passed")
            .put("scope_id", identity.scopeId)
            .put("contact_id", identity.contactId)
            .put("entity_id", identity.entityId)
            .put("display_name", identity.displayName)
            .put("confirmation_message_id", confirmation.messageId)
            .put("a0", a0)
            .put("retired_fact", retired)
    }

    private fun enqueueAndLease(
        context: Context,
        database: MemoryV2Database,
        scope: com.local.wxbotprobe.memory.v2.TrustedScopeContext,
        sessionId: String,
        messageId: String,
        suffix: String,
        targetVersions: Map<String, Int> = emptyMap(),
    ): LeasedMemoryJob {
        val service = jobs(context, database)
        val queued = service.enqueue(
            scope = scope,
            sessionId = sessionId,
            kind = "extract",
            sourceIds = listOf(messageId),
            targetVersions = targetVersions,
        )
        return service.leaseJob("debug-reconcile-$suffix", queued.jobId)
            ?: error("could not lease $suffix job")
    }

    private fun jobs(context: Context, database: MemoryV2Database): MemoryV2JobService {
        return MemoryV2JobService(
            context = context,
            database = database,
            now = { System.currentTimeMillis() / 1_000L },
            summaryMigrationEnabled = false,
            extractionMigrationEnabled = false,
        )
    }

    private fun append(
        context: Context,
        repository: MemoryV2Repository,
        scope: com.local.wxbotprobe.memory.v2.TrustedScopeContext,
        text: String,
        occurredAt: Long,
    ): com.local.wxbotprobe.memory.v2.AppendMessageResult {
        val appended = repository.appendMessage(
            scope,
            AppendMessageRequest(
                clientMessageId = "reconcile-${occurredAt}",
                sessionId = null,
                role = "user",
                text = text,
                occurredAt = occurredAt,
                receivedAt = occurredAt,
                timezone = "Asia/Shanghai",
            ),
        )
        repository.closeSession(scope, appended.sessionId, occurredAt + 1)
        return appended
    }

    private fun seedFixtureMemory(
        database: MemoryV2Database,
        scope: com.local.wxbotprobe.memory.v2.TrustedScopeContext,
        subjectId: String,
        predicate: String,
        text: String,
        value: String,
        messageId: String,
        now: Long,
        caseId: String,
    ): String {
        val db = database.writableDatabase
        val memoryId = UUID.randomUUID().toString()
        val deletionEpoch = queryLong(
            db,
            "SELECT deletion_epoch FROM scopes WHERE scope_id=?",
            scope.scopeId,
        )
        db.insertOrThrow(
            "memories",
            null,
            android.content.ContentValues().apply {
                put("scope_id", scope.scopeId)
                put("memory_id", memoryId)
                put("version", 1)
                put("tier", "A1")
                put("fact_key", "fixture|$predicate|$caseId")
                put("subject_id", subjectId)
                putNull("viewpoint_id")
                put("predicate", predicate)
                put("text", text)
                put("value_json", JSONObject.quote(value))
                put("epistemic", "declared")
                put("lifecycle", "active")
                putNull("retracted_at")
                put("pinned", 0)
                putNull("commitment_state")
                put("temporal_json", "{}")
                put("retention_score", 0.8)
                put("source_anchor", now)
                putNull("expires_at")
                put("created_at", now)
                put("updated_at", now)
                put("deletion_epoch", deletionEpoch)
            },
        )
        db.insertOrThrow(
            "evidence",
            null,
            android.content.ContentValues().apply {
                put("scope_id", scope.scopeId)
                put("evidence_id", UUID.randomUUID().toString())
                put("memory_id", memoryId)
                put("source_message_id", messageId)
                put("source_revision", 1)
                put("source_kind", "user")
                put("quote", text)
                put("start_codepoint", 0)
                put("end_codepoint", text.codePointCount(0, text.length))
                put("source_sha256", MemoryV2Crypto.sha256(text))
                put("captured_at", now)
            },
        )
        return memoryId
    }

    private fun candidate(
        id: String,
        operation: String,
        targetId: String?,
        expectedVersion: Int?,
        subjectId: String,
        predicate: String,
        text: String,
        value: String,
        reason: String,
        quote: String,
        messageId: String,
        occurrence: Long,
        relationType: String? = null,
    ): JSONObject {
        return JSONObject()
            .put("candidate_id", id)
            .put("operation", operation)
            .put("relation_type", relationType ?: JSONObject.NULL)
            .put("subject_id", subjectId)
            .put("viewpoint_id", JSONObject.NULL)
            .put("predicate", predicate)
            .put("text", text)
            .put("value", value)
            .put("epistemic", "declared")
            .put("reason", reason)
            .put("target_memory_id", targetId ?: JSONObject.NULL)
            .put("expected_version", expectedVersion ?: JSONObject.NULL)
            .put(
                "temporal",
                JSONObject()
                    .put("occurred_at", occurrence)
                    .put("local_date", JSONObject.NULL)
                    .put("timezone", "Asia/Shanghai")
                    .put("precision", "unknown")
                    .put("original", ""),
            )
            .put("commitment_state", JSONObject.NULL)
            .put("importance_hint", 0.8)
            .put(
                "evidence",
                JSONArray().put(
                    JSONObject()
                        .put("message_id", messageId)
                        .put("source_revision", 1)
                        .put("quote", quote),
                ),
            )
    }

    private fun extractionOutput(
        job: LeasedMemoryJob,
        candidate: JSONObject,
    ): String {
        return JSONObject()
            .put("contract", "ExtractionBatch")
            .put("envelope", job.envelope.toJsonObject())
            .put(
                "processed_message_ids",
                JSONArray(job.envelope.newSourceIds),
            )
            .put("candidates", JSONArray().put(candidate))
            .toString()
    }

    private fun memory(
        database: MemoryV2Database,
        scopeId: String,
        memoryId: String,
    ): MemorySnapshot {
        return database.readableDatabase.rawQuery(
            "SELECT version,value_json FROM memories " +
                "WHERE scope_id=? AND memory_id=?",
            arrayOf(scopeId, memoryId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) error("memory not found")
            val relationVersion = database.readableDatabase.rawQuery(
                "SELECT target_version FROM memory_relations " +
                    "WHERE scope_id=? AND target_memory_id=? LIMIT 1",
                arrayOf(scopeId, memoryId),
            ).use { relation ->
                if (relation.moveToFirst()) relation.getInt(0) else -1
            }
            val history = database.readableDatabase.rawQuery(
                "SELECT version FROM memory_history WHERE scope_id=? " +
                    "AND memory_id=? ORDER BY version",
                arrayOf(scopeId, memoryId),
            ).use { historyCursor ->
                buildList {
                    while (historyCursor.moveToNext()) {
                        add(historyCursor.getInt(0))
                    }
                }
            }
            MemorySnapshot(
                version = cursor.getInt(0),
                value = decodeValue(cursor.getString(1)),
                relationTargetVersion = relationVersion,
                historyVersions = history,
            )
        }
    }

    private fun readDatabaseSummary(path: File): DatabaseSummary {
        val db = SQLiteDatabase.openDatabase(
            path.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return try {
            DatabaseSummary(
                schemaVersion = queryLong(db, "PRAGMA user_version").toInt(),
                messageCount = queryLong(db, "SELECT COUNT(*) FROM messages").toInt(),
                memoryCount = queryLong(db, "SELECT COUNT(*) FROM memories").toInt(),
            )
        } finally {
            db.close()
        }
    }

    private fun copyDatabaseFiles(
        source: File,
        targetDirectory: File,
        targetName: String,
    ) {
        targetDirectory.mkdirs()
        listOf("", "-wal", "-shm").forEach { suffix ->
            val from = File(source.path + suffix)
            if (from.isFile) {
                from.copyTo(File(targetDirectory, targetName + suffix), true)
            }
        }
    }

    private fun count(
        database: MemoryV2Database,
        sql: String,
        vararg args: String,
    ): Int {
        return queryLong(database, sql, *args).toInt()
    }

    private fun queryLong(
        database: MemoryV2Database,
        sql: String,
        vararg args: String,
    ): Long {
        return database.readableDatabase.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun queryLong(
        db: SQLiteDatabase,
        sql: String,
        vararg args: String,
    ): Long {
        return db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun decodeValue(raw: String): String {
        return runCatching {
            JSONArray("[$raw]").getString(0)
        }.getOrDefault(raw)
    }

    private data class DatabaseSummary(
        val schemaVersion: Int,
        val messageCount: Int,
        val memoryCount: Int,
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("schema_version", schemaVersion)
                .put("message_count", messageCount)
                .put("memory_count", memoryCount)
        }
    }

    private data class MemorySnapshot(
        val version: Int,
        val value: String,
        val relationTargetVersion: Int,
        val historyVersions: List<Int>,
    ) {
        fun toJsonObject(): JSONObject {
            return JSONObject()
                .put("version", version)
                .put("value", value)
                .put("relation_target_version", relationTargetVersion)
                .put("history_versions", JSONArray(historyVersions))
        }
    }

    private data class ModelOperationCase(
        val id: String,
        val message: String,
        val seedPredicate: String?,
        val seedText: String,
        val seedValue: String,
        val expected: Set<String>,
        val requiresTarget: Boolean,
        val requiresRelation: Boolean = false,
        val expectedTargetValue: String? = null,
    )

    private data class ConflictFollowupCase(
        val id: String,
        val seedText: String,
        val conflictText: String,
        val followupText: String,
        val expectedVersion: Int,
        val expectedValue: String,
        val expectedResolution: String,
    )

    private data class UiConflictSource(
        val scopeId: String,
        val memoryId: String,
        val version: Int,
        val subjectId: String,
        val viewpointId: String?,
        val predicate: String,
        val text: String,
        val valueJson: String,
        val tier: String,
    )

    private data class ContactRecord(
        val scopeId: String,
        val contactId: String,
        val entityId: String,
        val displayName: String,
    )

    private data class OldMembershipFact(
        val memoryId: String,
        val version: Int,
        val subjectId: String,
        val predicate: String,
        val text: String,
    )

    private data class ConfirmationMessage(
        val messageId: String,
        val sessionId: String,
        val revision: Int,
    )

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_CASE = "case"
        const val MODE_MIGRATE = "migrate"
        const val MODE_RECONCILE = "reconcile"
        const val MODE_MODEL = "model"
        const val MODE_OPERATION_SWEEP = "operation-sweep"
        const val MODE_CONFLICT_FOLLOWUP = "conflict-followup"
        const val MODE_SEED_CONFLICT = "seed-conflict"
        const val MODE_SEED_A0_CONFLICT = "seed-a0-conflict"
        const val MODE_CLEANUP_CONFLICT = "cleanup-conflict"
        const val MODE_PROBE_A0_PROPOSAL = "probe-a0-proposal"
        const val MODE_CONTACT_A0 = "contact-a0"
        private val MODEL_OPERATION_CASES = listOf(
            ModelOperationCase(
                id = "add",
                message = "我今天第一次学会了做番茄炒蛋，味道还不错。",
                seedPredicate = null,
                seedText = "",
                seedValue = "",
                expected = setOf(MemoryV2ReconciliationPolicy.ADD),
                requiresTarget = false,
            ),
            ModelOperationCase(
                id = "corroborate",
                message = "我每次去甜品店都只点草莓冰淇淋。",
                seedPredicate = "preference.food",
                seedText = "对方喜欢草莓冰淇淋。",
                seedValue = "草莓冰淇淋",
                expected = setOf(MemoryV2ReconciliationPolicy.CORROBORATE),
                requiresTarget = true,
            ),
            ModelOperationCase(
                id = "supersede",
                message = "我上个月搬到上海了，现在一直住在上海。",
                seedPredicate = "profile.city",
                seedText = "对方住在杭州。",
                seedValue = "杭州",
                expected = setOf(MemoryV2ReconciliationPolicy.SUPERSEDE),
                requiresTarget = true,
            ),
            ModelOperationCase(
                id = "relate",
                message = "今天合作方完成了交付，我很开心。",
                seedPredicate = "relationship.role",
                seedText = "对方是你的同事。",
                seedValue = "同事",
                expected = setOf(MemoryV2ReconciliationPolicy.RELATE),
                requiresTarget = true,
                requiresRelation = true,
            ),
            ModelOperationCase(
                id = "retract",
                message = "我之前说喜欢吃香菜那条是错的，我其实一直不喜欢香菜，别记着那个了。",
                seedPredicate = "preference.food",
                seedText = "对方喜欢吃香菜。",
                seedValue = "香菜",
                expected = setOf(MemoryV2ReconciliationPolicy.RETRACT),
                requiresTarget = true,
            ),
            ModelOperationCase(
                id = "noop",
                message = "你知道我喜欢喝咖啡就好，这次没有什么新信息。",
                seedPredicate = "preference.drink",
                seedText = "对方喜欢喝咖啡。",
                seedValue = "咖啡",
                expected = setOf(MemoryV2ReconciliationPolicy.NOOP),
                requiresTarget = true,
            ),
            ModelOperationCase(
                id = "explicit-correction",
                message = "你记错了，我现在住在上海，不是在杭州。",
                seedPredicate = "profile.city",
                seedText = "对方住在杭州。",
                seedValue = "杭州",
                expected = setOf(MemoryV2ReconciliationPolicy.SUPERSEDE),
                requiresTarget = true,
                expectedTargetValue = "上海",
            ),
            ModelOperationCase(
                id = "ambiguous-acknowledgement",
                message = "好像是哦，我记错了。",
                seedPredicate = "profile.city",
                seedText = "对方住在杭州。",
                seedValue = "杭州",
                expected = setOf(MemoryV2ReconciliationPolicy.NOOP),
                requiresTarget = true,
                expectedTargetValue = "杭州",
            ),
            ModelOperationCase(
                id = "explicit-correction-natural",
                message = "你确实记错了，我根本没在杭州住过，现在人在上海。",
                seedPredicate = "profile.city",
                seedText = "对方住在杭州。",
                seedValue = "杭州",
                expected = setOf(MemoryV2ReconciliationPolicy.SUPERSEDE),
                requiresTarget = true,
                expectedTargetValue = "上海",
            ),
            ModelOperationCase(
                id = "explicit-change-natural",
                message = "草莓我早就吃腻了，现在更偏爱咖啡。",
                seedPredicate = "preference.food",
                seedText = "对方喜欢吃草莓冰淇淋。",
                seedValue = "草莓冰淇淋",
                expected = setOf(MemoryV2ReconciliationPolicy.SUPERSEDE),
                requiresTarget = true,
                expectedTargetValue = "咖啡",
            ),
            ModelOperationCase(
                id = "explicit-change-future-preference",
                message = "我改主意了，以后更喜欢咖啡。",
                seedPredicate = "preference.food",
                seedText = "对方喜欢吃草莓冰淇淋。",
                seedValue = "草莓冰淇淋",
                expected = setOf(MemoryV2ReconciliationPolicy.SUPERSEDE),
                requiresTarget = true,
                expectedTargetValue = "咖啡",
            ),
            ModelOperationCase(
                id = "explicit-change-future-location",
                message = "以后不在杭州了，我住苏州。",
                seedPredicate = "profile.city",
                seedText = "对方住在杭州。",
                seedValue = "杭州",
                expected = setOf(MemoryV2ReconciliationPolicy.SUPERSEDE),
                requiresTarget = true,
                expectedTargetValue = "苏州",
            ),
            ModelOperationCase(
                id = "mixed-signal-explicit-correction",
                message = "可能是吧，但你确实记错了，我现在只喝燕麦奶。",
                seedPredicate = "preference.drink",
                seedText = "对方喜欢喝咖啡。",
                seedValue = "咖啡",
                expected = setOf(MemoryV2ReconciliationPolicy.SUPERSEDE),
                requiresTarget = true,
                expectedTargetValue = "燕麦奶",
            ),
            ModelOperationCase(
                id = "ambiguous-uncertain",
                message = "可能是吧，我记不清了。",
                seedPredicate = "profile.city",
                seedText = "对方住在杭州。",
                seedValue = "杭州",
                expected = setOf(MemoryV2ReconciliationPolicy.NOOP),
                requiresTarget = true,
                expectedTargetValue = "杭州",
            ),
        )
        private val MODEL_CONFLICT_FOLLOWUP_CASES = listOf(
            ConflictFollowupCase(
                id = "explicit-correction-after-conflict",
                seedText = "对方住在杭州。",
                conflictText = "我可能搬到上海了，但还没确定。",
                followupText = "你记错了，我现在住在上海。",
                expectedVersion = 2,
                expectedValue = "上海",
                expectedResolution = "accepted_candidate",
            ),
            ConflictFollowupCase(
                id = "ambiguous-after-conflict",
                seedText = "对方住在杭州。",
                conflictText = "我可能搬到上海了，但还没确定。",
                followupText = "好像是哦，我记错了。",
                expectedVersion = 1,
                expectedValue = "杭州",
                expectedResolution = "keep_existing",
            ),
            ConflictFollowupCase(
                id = "explicit-natural-after-conflict",
                seedText = "对方住在杭州。",
                conflictText = "我可能搬到上海了，但还没确定。",
                followupText = "你确实记错了，我根本没在杭州住过，现在人在上海。",
                expectedVersion = 2,
                expectedValue = "上海",
                expectedResolution = "accepted_candidate",
            ),
            ConflictFollowupCase(
                id = "ambiguous-uncertain-after-conflict",
                seedText = "对方住在杭州。",
                conflictText = "我可能搬到上海了，但还没确定。",
                followupText = "可能是吧，我记不清了。",
                expectedVersion = 1,
                expectedValue = "杭州",
                expectedResolution = "keep_existing",
            ),
        )
        private const val CONTACT_CONFIRMATION_CLIENT_ID =
            "user-confirmed-contact-a0-20261025"
        private const val CONTACT_CONFIRMATION_TEXT =
            "用户确认：同事 A 和我是同事。"
    }
}
