package com.local.wxbotprobe.memory.v2

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class MemoryV2JobService(
    context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val summaryMigrationEnabled: Boolean = true,
    private val extractionMigrationEnabled: Boolean = true,
) {
    private val appContext = context.applicationContext
    private val config = MemoryV2ConfigLoader.load(context)
    private val crypto = MemoryV2Crypto()
    private val reconciliationContext = MemoryV2ReconciliationContextReader(
        appContext,
        database,
        now,
    )

    fun enqueue(
        scope: TrustedScopeContext,
        sessionId: String,
        kind: String,
        sourceIds: List<String>,
        targetVersions: Map<String, Int> = emptyMap(),
        jobId: String = UUID.randomUUID().toString(),
    ): MemoryJobEnqueueResult {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val result = enqueueLocked(
                db = db,
                scope = scope,
                sessionId = sessionId,
                kind = kind,
                sourceIds = sourceIds,
                targetVersions = targetVersions,
                jobId = jobId,
            )
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun splitContextOverflow(job: LeasedMemoryJob): List<String> {
        if (job.kind != "extract") {
            return emptyList()
        }
        val partitions = MemoryV2BatchSplitPolicy.splitExtraction(job.envelope.newSourceIds)
        if (partitions.isEmpty()) {
            return emptyList()
        }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val state = readJob(db, job.scopeId, job.jobId)
            if (state !in setOf("leased", "failed")) {
                throw MemoryV2Exception(MemoryV2ErrorCode.JOB_TERMINAL)
            }
            val scope = requireScope(db, job.scopeId)
            val childIds = partitions.map { sourceIds ->
                enqueueLocked(
                    db = db,
                    scope = scope,
                    sessionId = job.sessionId,
                    kind = job.kind,
                    sourceIds = sourceIds,
                    targetVersions = job.envelope.targetVersions,
                    jobId = UUID.randomUUID().toString(),
                ).jobId
            }
            val cancelled = db.update(
                "jobs",
                ContentValues().apply {
                    put("state", "cancelled")
                    put("error_code", MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED.name)
                    putNull("lease_owner")
                    putNull("lease_until")
                    put("updated_at", now())
                },
                "scope_id=? AND job_id=? AND state IN ('leased','failed')",
                arrayOf(job.scopeId, job.jobId),
            )
            if (cancelled != 1) {
                throw MemoryV2Exception(MemoryV2ErrorCode.JOB_TERMINAL)
            }
            refreshLedgers(db, job.scopeId, "jobs", "dependencies")
            db.setTransactionSuccessful()
            return childIds
        } finally {
            db.endTransaction()
        }
    }

    fun splitFailedOverflowBatch(): MemoryOverflowRepairResult {
        var repairError: String? = null
        val failedJobs = database.readableDatabase.query(
            "jobs",
            arrayOf(
                "scope_id",
                "job_id",
                "session_id",
                "kind",
                "attempts",
                "max_attempts",
                "envelope_json",
            ),
            "kind='extract' AND state='failed' AND error_code=?",
            arrayOf(MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED.name),
            null,
            null,
            "updated_at,created_at",
            "32",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LeasedMemoryJob(
                            jobId = cursor.string("job_id"),
                            scopeId = cursor.string("scope_id"),
                            sessionId = cursor.string("session_id"),
                            kind = cursor.string("kind"),
                            attempts = cursor.int("attempts"),
                            maxAttempts = cursor.int("max_attempts"),
                            envelope = parseEnvelope(
                                JSONObject(cursor.string("envelope_json")),
                            ),
                        ),
                    )
                }
            }
        }
        failedJobs
            .filter { it.envelope.newSourceIds.size > 1 }
            .sortedWith(
                compareByDescending<LeasedMemoryJob> {
                    it.envelope.newSourceIds.size
                }.thenBy { it.envelope.createdAt },
            )
            .forEach { job ->
                try {
                    val childIds = splitContextOverflow(job)
                    if (childIds.isNotEmpty()) {
                        return MemoryOverflowRepairResult(
                            changed = true,
                            splitJobId = job.jobId,
                        )
                    }
                } catch (error: Throwable) {
                    if (repairError == null) {
                        repairError = (error as? MemoryV2Exception)
                            ?.code
                            ?.name
                            ?: error.message.orEmpty()
                    }
                }
            }
        return MemoryOverflowRepairResult(
            changed = false,
            errorCode = repairError,
        )
    }

    fun cancelForSummaryResize(
        job: LeasedMemoryJob,
        errorCode: MemoryV2ErrorCode,
    ) {
        require(job.kind == "summary") { "not a summary job" }
        if (job.envelope.newSourceIds.size <= 1) {
            markSummaryGap(job, errorCode)
            return
        }
        cancelForReschedule(job, errorCode)
    }

    private fun markSummaryGap(
        job: LeasedMemoryJob,
        errorCode: MemoryV2ErrorCode,
    ) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            job.envelope.sources.forEach { source ->
                db.update(
                    "messages",
                    ContentValues().apply {
                        put("summary_state", "gap")
                    },
                    "scope_id=? AND message_id=? AND state='live'",
                    arrayOf(job.scopeId, source.messageId),
                )
                db.insertWithOnConflict(
                    "coverage",
                    null,
                    ContentValues().apply {
                        put("scope_id", job.scopeId)
                        put("session_id", job.sessionId)
                        put("message_id", source.messageId)
                        put("kind", "summary")
                        put("source_revision", source.revision)
                        put("state", "gap")
                        put("job_id", job.jobId)
                        put("reason", errorCode.name)
                        put("updated_at", now())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            val updated = db.update(
                "jobs",
                ContentValues().apply {
                    put("state", "cancelled")
                    put("error_code", errorCode.name)
                    putNull("lease_owner")
                    putNull("lease_until")
                    put("updated_at", now())
                },
                "scope_id=? AND job_id=? AND state IN ('pending','leased')",
                arrayOf(job.scopeId, job.jobId),
            )
            if (updated != 1) {
                throw MemoryV2Exception(MemoryV2ErrorCode.JOB_TERMINAL)
            }
            refreshLedgers(
                db,
                job.scopeId,
                "jobs",
                "messages",
                "coverage",
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun cancelForReschedule(
        job: LeasedMemoryJob,
        errorCode: MemoryV2ErrorCode,
    ) {
        val updated = database.writableDatabase.update(
            "jobs",
            ContentValues().apply {
                put("state", "cancelled")
                put("error_code", errorCode.name)
                putNull("lease_owner")
                putNull("lease_until")
                put("updated_at", now())
            },
            "scope_id=? AND job_id=? AND state IN ('pending','leased')",
            arrayOf(job.scopeId, job.jobId),
        )
        if (updated != 1) {
            throw MemoryV2Exception(MemoryV2ErrorCode.JOB_TERMINAL)
        }
    }

    fun pauseUntil(
        job: LeasedMemoryJob,
        nextAttemptAt: Long,
        errorCode: String,
    ) {
        val updated = database.writableDatabase.update(
            "jobs",
            ContentValues().apply {
                put("state", "pending")
                put("error_code", errorCode)
                putNull("lease_owner")
                putNull("lease_until")
                put("next_attempt_at", nextAttemptAt)
                put("updated_at", now())
            },
            "scope_id=? AND job_id=? AND state='leased'",
            arrayOf(job.scopeId, job.jobId),
        )
        if (updated != 1) {
            throw MemoryV2Exception(MemoryV2ErrorCode.JOB_TERMINAL)
        }
    }

    fun nextPendingDelayMillis(): Long? {
        val currentMillis = System.currentTimeMillis()
        return database.readableDatabase.rawQuery(
            "SELECT MIN(next_attempt_at) FROM jobs WHERE state='pending' " +
                "AND next_attempt_at>?",
            arrayOf((currentMillis / 1_000L).toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                null
            } else {
                val dueMillis = cursor.getLong(0) * 1_000L
                (dueMillis - currentMillis + 1_000L).coerceAtLeast(1_000L)
            }
        }
    }

    fun enqueueNextSummary(
        sessionId: String? = null,
        forceOpen: Boolean = false,
    ): MemoryJobEnqueueResult? {
        synchronized(SUMMARY_SCHEDULER_LOCK) {
            cancelStaleJobs()
            if (summaryMigrationEnabled) {
                migrateLegacySummaryJobs()
            }
            val db = database.readableDatabase
            val minimumNewMessages =
                config.backgroundMinBatchMessages.coerceAtLeast(1)
            val filter = if (sessionId == null) {
                ""
            } else {
                " AND s.session_id=?"
            }
            val openCondition = if (forceOpen) {
                "s.state IN ('open','closed')"
            } else {
                "(s.state='closed' OR (s.state='open' AND (" +
                    "SELECT COUNT(*) FROM messages threshold_m " +
                    "WHERE threshold_m.scope_id=s.scope_id " +
                    "AND threshold_m.session_id=s.session_id " +
                    "AND threshold_m.summary_state='pending' " +
                    "AND threshold_m.state='live' " +
                    "AND threshold_m.cache_expires_at>?" +
                    ")>=$minimumNewMessages))"
            }
            val args = buildList {
                if (!forceOpen) {
                    add(now().toString())
                }
                add(now().toString())
                add(now().toString())
                if (sessionId != null) {
                    add(sessionId)
                }
            }.toTypedArray()
            val sessions = db.rawQuery(
                "SELECT s.scope_id,s.session_id FROM sessions s " +
                    "WHERE $openCondition AND EXISTS (" +
                    "SELECT 1 FROM messages m WHERE m.scope_id=s.scope_id " +
                    "AND m.session_id=s.session_id " +
                    "AND m.summary_state='pending' AND m.state='live' " +
                    "AND m.cache_expires_at>?" +
                    ") AND NOT EXISTS (" +
                    "SELECT 1 FROM messages m WHERE m.scope_id=s.scope_id " +
                    "AND m.session_id=s.session_id " +
                    "AND m.extraction_state='pending' AND m.state='live' " +
                    "AND m.cache_expires_at>?" +
                    ") AND NOT EXISTS (" +
                    "SELECT 1 FROM jobs j WHERE j.scope_id=s.scope_id " +
                    "AND j.session_id=s.session_id AND j.kind='extract' " +
                    "AND j.state IN ('pending','leased')" +
                    ") AND NOT EXISTS (" +
                    "SELECT 1 FROM jobs j WHERE j.scope_id=s.scope_id " +
                    "AND j.session_id=s.session_id AND j.kind='summary' " +
                    "AND j.state IN ('pending','leased')" +
                    ")$filter ORDER BY s.updated_at DESC LIMIT 16",
                args,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0) to cursor.getString(1))
                    }
                }
            }
            for ((scopeId, candidateSessionId) in sessions) {
                val latest = latestSummaryJob(db, scopeId, candidateSessionId)
                val batchLimit = MemoryV2SummaryBatchPolicy.nextBatchLimit(
                    latest,
                    config.backgroundMaxSources,
                ) ?: continue
                val sqlLimit = MemoryV2LimitPolicy.sqliteLimit(batchLimit)
                val sourceIds = db.rawQuery(
                    "SELECT message_id FROM messages WHERE scope_id=? " +
                        "AND session_id=? AND summary_state='pending' " +
                        "AND state='live' AND cache_expires_at>? " +
                        "ORDER BY seq LIMIT ?",
                    arrayOf(
                        scopeId,
                        candidateSessionId,
                        now().toString(),
                        sqlLimit.toString(),
                    ),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursor.getString(0))
                        }
                    }
                }
                if (sourceIds.isEmpty()) {
                    continue
                }
                val scope = requireScope(db, scopeId)
                return enqueue(
                    scope = scope,
                    sessionId = candidateSessionId,
                    kind = "summary",
                    sourceIds = sourceIds,
                )
            }
            return null
        }
    }

    fun enqueueMissingExtraction(sessionId: String? = null): MemoryJobEnqueueResult? {
        synchronized(EXTRACTION_SCHEDULER_LOCK) {
            cancelStaleJobs()
            if (extractionMigrationEnabled) {
                migrateLegacyExtractionFailures()
            }
            val db = database.readableDatabase
            val minimumNewMessages =
                config.backgroundMinBatchMessages.coerceAtLeast(1)
            val filter = if (sessionId == null) "" else " AND s.session_id=?"
            val openCondition = "(s.state='closed' OR (s.state='open' AND (" +
                "SELECT COUNT(*) FROM messages threshold_m " +
                "WHERE threshold_m.scope_id=s.scope_id " +
                "AND threshold_m.session_id=s.session_id " +
                "AND threshold_m.extraction_state='pending' " +
                "AND threshold_m.state='live' " +
                "AND threshold_m.cache_expires_at>?" +
                ")>=$minimumNewMessages))"
            val args = buildList {
                add(now().toString())
                add(now().toString())
                if (sessionId != null) {
                    add(sessionId)
                }
            }.toTypedArray()
            val sessions = db.rawQuery(
                "SELECT s.scope_id,s.session_id FROM sessions s " +
                    "WHERE $openCondition AND EXISTS (" +
                    "SELECT 1 FROM messages m WHERE m.scope_id=s.scope_id " +
                    "AND m.session_id=s.session_id " +
                    "AND m.extraction_state='pending' AND m.state='live' " +
                    "AND m.cache_expires_at>? AND NOT EXISTS (" +
                    "SELECT 1 FROM dependencies d JOIN jobs j " +
                    "ON j.scope_id=d.scope_id AND j.job_id=d.child_id " +
                    "WHERE d.scope_id=m.scope_id AND d.parent_kind='message' " +
                    "AND d.parent_id=m.message_id AND d.child_kind='job' " +
                    "AND j.kind='extract' AND j.state IN ('pending','leased')" +
                    ")) AND NOT EXISTS (" +
                    "SELECT 1 FROM jobs j WHERE j.scope_id=s.scope_id " +
                    "AND j.session_id=s.session_id AND j.kind='summary' " +
                    "AND j.state IN ('pending','leased')" +
                    ")$filter ORDER BY s.updated_at DESC LIMIT 16",
                args,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0) to cursor.getString(1))
                    }
                }
            }
            for ((scopeId, candidateSessionId) in sessions) {
                val sourceIds = db.rawQuery(
                    "SELECT m.message_id FROM messages m " +
                        "WHERE m.scope_id=? AND m.session_id=? " +
                        "AND m.extraction_state='pending' AND m.state='live' " +
                        "AND m.cache_expires_at>? AND NOT EXISTS (" +
                        "SELECT 1 FROM dependencies d JOIN jobs j " +
                        "ON j.scope_id=d.scope_id AND j.job_id=d.child_id " +
                        "WHERE d.scope_id=m.scope_id " +
                        "AND d.parent_kind='message' AND d.parent_id=m.message_id " +
                        "AND d.child_kind='job' AND j.kind='extract' " +
                        "AND j.state IN ('pending','leased')" +
                        ") ORDER BY m.seq LIMIT ?",
                    arrayOf(
                        scopeId,
                        candidateSessionId,
                        now().toString(),
                        MemoryV2LimitPolicy.sqliteLimit(
                            config.backgroundMaxSources,
                        ).toString(),
                    ),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursor.getString(0))
                        }
                    }
                }
                if (sourceIds.isEmpty()) {
                    continue
                }
                val latest = latestExtractionJob(db, scopeId, candidateSessionId)
                if (
                    latest?.state == "failed" &&
                    latest.errorCode != MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED.name
                ) {
                    continue
                }
                val sessionOpen = db.query(
                    "sessions",
                    arrayOf("state"),
                    "scope_id=? AND session_id=?",
                    arrayOf(scopeId, candidateSessionId),
                    null,
                    null,
                    null,
                    "1",
                ).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == "open"
                }
                if (
                    latest?.state == "saved" &&
                    MemoryV2ExtractionDeferralPolicy.shouldWaitForMoreSources(
                        previousSourceIds = latest.sourceIds,
                        pendingSourceIds = sourceIds,
                        minimumNewSources = minimumNewMessages,
                        sessionOpen = sessionOpen,
                    )
                ) {
                    continue
                }
                val scope = requireScope(db, scopeId)
                return enqueue(
                    scope = scope,
                    sessionId = candidateSessionId,
                    kind = "extract",
                    sourceIds = sourceIds,
                )
            }
            return null
        }
    }

    private fun latestExtractionJob(
        db: SQLiteDatabase,
        scopeId: String,
        sessionId: String,
    ): ExtractionJobSnapshot? {
        return db.query(
            "jobs",
            arrayOf("state", "error_code", "envelope_json"),
            "scope_id=? AND session_id=? AND kind='extract'",
            arrayOf(scopeId, sessionId),
            null,
            null,
            "created_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val envelope = parseEnvelope(JSONObject(cursor.string("envelope_json")))
            ExtractionJobSnapshot(
                state = cursor.string("state"),
                errorCode = if (cursor.isNull(1)) null else cursor.getString(1),
                sourceIds = envelope.newSourceIds,
            )
        }
    }

    private data class ExtractionJobSnapshot(
        val state: String,
        val errorCode: String?,
        val sourceIds: List<String>,
    )

    private fun migrateLegacyExtractionFailures() {
        val preferences = appContext.getSharedPreferences(
            EXTRACTION_SCHEDULER_PREFS,
            Context.MODE_PRIVATE,
        )
        val promptVersion = config.versions["extraction_prompt"] ?: "extract-2.5.1"
        if (preferences.getString(EXTRACTION_PROMPT_REVISION_KEY, null) == promptVersion) {
            return
        }
        val recoverableCodes =
            MemoryV2ExtractionFailureRecoveryPolicy.recoverableErrorCodes()
        val placeholders = recoverableCodes.joinToString(",") { "?" }
        database.writableDatabase.update(
            "jobs",
            ContentValues().apply {
                put("state", "cancelled")
                put("error_code", "EXTRACTION_SCHEDULER_MIGRATION")
                putNull("lease_owner")
                putNull("lease_until")
                put("updated_at", now())
            },
            "kind='extract' AND state='failed' AND error_code IN ($placeholders)",
            recoverableCodes.toTypedArray(),
        )
        preferences.edit()
            .putString(EXTRACTION_PROMPT_REVISION_KEY, promptVersion)
            .apply()
    }

    fun cancelStaleJobs(): Int {
        val db = database.writableDatabase
        val candidates = db.query(
            "jobs",
            arrayOf(
                "scope_id",
                "job_id",
                "session_id",
                "kind",
                "envelope_json",
                "state",
                "lease_until",
            ),
            "state='pending' OR (state='leased' AND " +
                "(lease_until IS NULL OR lease_until<=?))",
            arrayOf(now().toString()),
            null,
            null,
            "created_at",
            "64",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StaleJobCandidate(
                            scopeId = cursor.string("scope_id"),
                            jobId = cursor.string("job_id"),
                            sessionId = cursor.string("session_id"),
                            kind = cursor.string("kind"),
                            envelope = parseEnvelope(
                                JSONObject(cursor.string("envelope_json")),
                            ),
                        ),
                    )
                }
            }
        }
        var cancelled = 0
        candidates.forEach { candidate ->
            val scope = readScope(db, candidate.scopeId) ?: return@forEach
            val session = readSession(db, candidate.scopeId, candidate.sessionId)
                ?: return@forEach
            val envelope = candidate.envelope
            val stale = scope.revision != envelope.baseScopeRevision ||
                scope.deletionEpoch != envelope.baseDeletionEpoch ||
                scope.personaRevision != envelope.personaRevision ||
                scope.importGeneration != envelope.importGeneration ||
                (
                    candidate.kind == "summary" &&
                        session.workingVersion != envelope.baseWorkingSummaryVersion
                    )
            if (!stale) {
                return@forEach
            }
            cancelled += db.update(
                "jobs",
                ContentValues().apply {
                    put("state", "cancelled")
                    put(
                        "error_code",
                        if (candidate.kind == "summary") {
                            MemoryV2ErrorCode.STALE_SUMMARY.name
                        } else {
                            MemoryV2ErrorCode.STALE_EPOCH.name
                        },
                    )
                    putNull("lease_owner")
                    putNull("lease_until")
                    put("updated_at", now())
                },
                "scope_id=? AND job_id=? AND state IN ('pending','leased')",
                arrayOf(candidate.scopeId, candidate.jobId),
            )
        }
        return cancelled
    }

    private fun latestSummaryJob(
        db: SQLiteDatabase,
        scopeId: String,
        sessionId: String,
    ): MemorySummaryJobSnapshot? {
        return db.query(
            "jobs",
            arrayOf("state", "error_code", "envelope_json"),
            "scope_id=? AND session_id=? AND kind='summary'",
            arrayOf(scopeId, sessionId),
            null,
            null,
            "created_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val envelope = parseEnvelope(JSONObject(cursor.string("envelope_json")))
            MemorySummaryJobSnapshot(
                state = cursor.string("state"),
                errorCode = if (cursor.isNull(1)) null else cursor.getString(1),
                sourceCount = envelope.newSourceIds.size,
            )
        }
    }

    private fun migrateLegacySummaryJobs() {
        val preferences = appContext.getSharedPreferences(
            SUMMARY_SCHEDULER_PREFS,
            Context.MODE_PRIVATE,
        )
        if (
            preferences.getInt(SUMMARY_CONTRACT_REVISION_KEY, 0) >=
            SUMMARY_CONTRACT_REVISION
        ) {
            return
        }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "jobs",
                ContentValues().apply {
                    put("state", "cancelled")
                    put("error_code", MemoryV2SummaryBatchPolicy.MIGRATION_ERROR)
                    putNull("lease_owner")
                    putNull("lease_until")
                    put("updated_at", now())
                },
                "kind='summary' AND state IN ('pending','leased','failed')",
                null,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        preferences.edit()
            .putInt(SUMMARY_CONTRACT_REVISION_KEY, SUMMARY_CONTRACT_REVISION)
            .apply()
    }

    private fun enqueueLocked(
        db: SQLiteDatabase,
        scope: TrustedScopeContext,
        sessionId: String,
        kind: String,
        sourceIds: List<String>,
        targetVersions: Map<String, Int>,
        jobId: String,
    ): MemoryJobEnqueueResult {
        require(kind in setOf("extract", "summary")) { "unsupported job kind" }
        require(sourceIds.isNotEmpty()) { "sourceIds are required" }
        if (config.backgroundMaxSources > 0) {
            require(sourceIds.size <= config.backgroundMaxSources) {
                "too many source messages"
            }
        }
        val distinctSources = sourceIds.distinct()
        val currentScope = requireScope(db, scope.scopeId)
        val session = readSession(db, scope.scopeId, sessionId)
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
        val sources = distinctSources.map { sourceId ->
            readSource(db, scope.scopeId, sessionId, sourceId)
        }
        val promptVersion = if (kind == "summary") {
            config.versions["summary_prompt"] ?: "summary-2.2.0"
        } else {
            config.versions["extraction_prompt"] ?: "extract-2.5.1"
        }
        val resolvedTargetVersions = if (kind == "extract") {
            if (targetVersions.isEmpty()) {
                reconciliationContext.targetVersions(
                    reconciliationContext.selectForSources(
                        scope.scopeId,
                        distinctSources,
                    ),
                )
            } else {
                reconciliationContext.loadFrozen(scope.scopeId, targetVersions)
                targetVersions
            }
        } else {
            emptyMap()
        }
        val envelope = TrustedMemoryEnvelope(
            schemaVersion = "2.0.0",
            scopeId = scope.scopeId,
            sessionId = sessionId,
            jobId = jobId,
            kind = kind,
            baseScopeRevision = currentScope.revision,
            baseDeletionEpoch = currentScope.deletionEpoch,
            baseWorkingSummaryVersion = session.workingVersion,
            personaRevision = currentScope.personaRevision,
            importGeneration = currentScope.importGeneration,
            policyVersion = config.versions["policy"] ?: "2.0.3",
            configVersion = config.versions["pipeline"] ?: "2.0.3",
            promptVersion = promptVersion,
            sources = sources.map {
                MemorySourceRef(it.messageId, it.revision, it.sha256)
            },
            newSourceIds = distinctSources,
            targetVersions = resolvedTargetVersions,
            createdAt = now(),
        )
        val envelopeJson = envelope.toJsonObject()
        val idempotencyKey = crypto.hmac(
            "job:${scope.scopeId}:$kind",
            canonicalJson(envelopeJson),
        )
        db.query(
            "jobs",
            arrayOf("job_id"),
            "scope_id=? AND idempotency_key=?",
            arrayOf(scope.scopeId, idempotencyKey),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return MemoryJobEnqueueResult(
                    status = "unchanged",
                    jobId = cursor.getString(0),
                )
            }
        }
        pruneCancelledJobs(db, scope.scopeId)
        assertJobCapacity(db, scope.scopeId)
        db.insertOrThrow(
            "jobs",
            null,
            ContentValues().apply {
                put("scope_id", scope.scopeId)
                put("job_id", jobId)
                put("session_id", sessionId)
                put("kind", kind)
                put("idempotency_key", idempotencyKey)
                put("envelope_json", envelopeJson.toString())
                put("state", "pending")
                put("attempts", 0)
                put("max_attempts", config.backgroundMaxAttempts)
                putNull("lease_owner")
                putNull("lease_until")
                put("next_attempt_at", now())
                putNull("error_code")
                put("created_at", now())
                put("updated_at", now())
            },
        )
        sources.forEach { source ->
            insertDependency(
                db,
                scope.scopeId,
                "message",
                source.messageId,
                "job",
                jobId,
                source.revision,
            )
        }
        refreshLedgers(db, scope.scopeId, "jobs", "dependencies")
        return MemoryJobEnqueueResult(
            status = "pending",
            jobId = jobId,
            envelope = envelope,
        )
    }

    fun lease(
        owner: String,
        limit: Int = 1,
    ): List<LeasedMemoryJob> {
        require(owner.isNotBlank()) { "lease owner is required" }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val candidates = db.query(
                "jobs",
                arrayOf(
                    "scope_id",
                    "job_id",
                    "session_id",
                    "kind",
                    "attempts",
                    "max_attempts",
                    "envelope_json",
                    "state",
                    "lease_until",
                ),
                "state IN ('pending','leased') AND next_attempt_at<=?",
                arrayOf(now().toString()),
                null,
                null,
                "CASE kind WHEN 'summary' THEN 0 ELSE 1 END," +
                    "next_attempt_at,created_at",
                limit.coerceAtLeast(1).toString(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val state = cursor.string("state")
                        val leaseUntil = cursor.getLongOrNull("lease_until")
                        if (state == "leased" && leaseUntil != null && leaseUntil > now()) {
                            continue
                        }
                        add(
                            LeasedMemoryJob(
                                jobId = cursor.string("job_id"),
                                scopeId = cursor.string("scope_id"),
                                sessionId = cursor.string("session_id"),
                                kind = cursor.string("kind"),
                                attempts = cursor.int("attempts") + 1,
                                maxAttempts = cursor.int("max_attempts"),
                                envelope = parseEnvelope(
                                    JSONObject(cursor.string("envelope_json")),
                                ),
                            ),
                        )
                    }
                }
            }
            candidates.forEach { job ->
                db.update(
                    "jobs",
                    ContentValues().apply {
                        put("state", "leased")
                        put("attempts", job.attempts)
                        put("lease_owner", owner)
                        put("lease_until", now() + config.backgroundLeaseSeconds)
                        put("updated_at", now())
                    },
                    "scope_id=? AND job_id=?",
                    arrayOf(job.scopeId, job.jobId),
                )
            }
            db.setTransactionSuccessful()
            return candidates
        } finally {
            db.endTransaction()
        }
    }

    private fun pruneCancelledJobs(
        db: SQLiteDatabase,
        scopeId: String,
        keepNewest: Int = 32,
    ): Int {
        val staleIds = db.rawQuery(
            "SELECT job_id FROM jobs WHERE scope_id=? AND state='cancelled' " +
                "ORDER BY updated_at DESC,job_id DESC LIMIT -1 OFFSET ?",
            arrayOf(scopeId, keepNewest.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
        if (staleIds.isEmpty()) {
            return 0
        }
        val placeholders = staleIds.joinToString(",") { "?" }
        db.delete(
            "dependencies",
            "scope_id=? AND child_kind='job' AND child_id IN ($placeholders)",
            arrayOf(scopeId, *staleIds.toTypedArray()),
        )
        return db.delete(
            "jobs",
            "scope_id=? AND state='cancelled' AND job_id IN ($placeholders)",
            arrayOf(scopeId, *staleIds.toTypedArray()),
        )
    }

    fun leaseJob(
        owner: String,
        jobId: String,
    ): LeasedMemoryJob? {
        require(owner.isNotBlank()) { "lease owner is required" }
        require(jobId.isNotBlank()) { "jobId is required" }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val job = db.query(
                "jobs",
                arrayOf(
                    "scope_id",
                    "job_id",
                    "session_id",
                    "kind",
                    "attempts",
                    "max_attempts",
                    "envelope_json",
                    "state",
                    "lease_until",
                ),
                "job_id=?",
                arrayOf(jobId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return null
                }
                val state = cursor.string("state")
                val leaseUntil = cursor.getLongOrNull("lease_until")
                if (state == "leased" && leaseUntil != null && leaseUntil > now()) {
                    return null
                }
                if (state !in setOf("pending", "leased")) {
                    return null
                }
                LeasedMemoryJob(
                    jobId = cursor.string("job_id"),
                    scopeId = cursor.string("scope_id"),
                    sessionId = cursor.string("session_id"),
                    kind = cursor.string("kind"),
                    attempts = cursor.int("attempts") + 1,
                    maxAttempts = cursor.int("max_attempts"),
                    envelope = parseEnvelope(
                        JSONObject(cursor.string("envelope_json")),
                    ),
                )
            }
            db.update(
                "jobs",
                ContentValues().apply {
                    put("state", "leased")
                    put("attempts", job.attempts)
                    put("lease_owner", owner)
                    put("lease_until", now() + config.backgroundLeaseSeconds)
                    put("updated_at", now())
                },
                "scope_id=? AND job_id=?",
                arrayOf(job.scopeId, job.jobId),
            )
            db.setTransactionSuccessful()
            return job
        } finally {
            db.endTransaction()
        }
    }

    fun validateExtraction(
        job: LeasedMemoryJob,
        rawBatch: String,
    ): ValidatedExtractionBatch {
        require(job.kind == "extract") { "not an extraction job" }
        val root = parseObject(rawBatch)
        validateEnvelope(root, job)
        if (root.optString("contract") != "ExtractionBatch") {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        val processed = stringArray(root.optJSONArray("processed_message_ids"))
        val phaseAware = job.envelope.promptVersion.startsWith("extract-2.5.") ||
            job.envelope.promptVersion == config.versions["extraction_prompt"]
        val checkpoint = MemoryV2PhaseCheckpointPolicy.validate(
            allSourceIds = job.envelope.newSourceIds,
            processedMessageIds = processed,
            phase = root.optJSONObject("phase"),
            phaseAware = phaseAware,
        )
        val sourceMap = loadSources(job.envelope, processed.toSet())
        val candidatesJson = root.optJSONArray("candidates")
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        if (!checkpoint.closed && candidatesJson.length() > 0) {
            throw MemoryV2Exception(MemoryV2ErrorCode.COVERAGE_MISMATCH)
        }
        val candidates = buildList {
            for (index in 0 until candidatesJson.length()) {
                add(
                    validateCandidate(
                        candidatesJson.optJSONObject(index)
                            ?: throw MemoryV2Exception(
                                MemoryV2ErrorCode.SCHEMA_INVALID,
                            ),
                        job.envelope,
                        sourceMap,
                        requireRecentEvent = false,
                    ),
                )
            }
        }
        val targetedIds = candidates.mapNotNull { it.targetMemoryId }
        if (targetedIds.size != targetedIds.distinct().size) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        return ValidatedExtractionBatch(
            envelope = job.envelope,
            processedMessageIds = processed,
            candidates = candidates,
            phaseClosed = checkpoint.closed,
            phaseReason = checkpoint.reason,
        )
    }

    fun validateSummary(
        job: LeasedMemoryJob,
        rawBatch: String,
    ): ValidatedSummaryBatch {
        require(job.kind == "summary") { "not a summary job" }
        val root = parseObject(rawBatch)
        validateEnvelope(root, job)
        if (root.optString("contract") != "SummaryBatch") {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        val processed = stringArray(root.optJSONArray("processed_message_ids"))
        if (processed != job.envelope.newSourceIds) {
            throw MemoryV2Exception(MemoryV2ErrorCode.COVERAGE_MISMATCH)
        }
        val sourceMap = loadSources(job.envelope)
        val previousEvidence = loadPreviousWorkingEvidence(job)
        val eventsJson = root.optJSONArray("events")
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        val events = buildList {
            for (index in 0 until eventsJson.length()) {
                add(
                    validateCandidate(
                        eventsJson.optJSONObject(index)
                            ?: throw MemoryV2Exception(
                                MemoryV2ErrorCode.SCHEMA_INVALID,
                            ),
                        job.envelope,
                        sourceMap,
                        requireRecentEvent = true,
                    ),
                )
            }
        }
        val working = root.optJSONObject("working_summary")
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        val items = buildList {
            listOf("goals", "constraints", "unresolved", "references").forEach {
                section ->
                val array = working.optJSONArray(section)
                    ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index)
                        ?: throw MemoryV2Exception(
                            MemoryV2ErrorCode.SCHEMA_INVALID,
                        )
                    add(
                        ValidatedWorkingItem(
                            section = section,
                            text = requiredString(item, "text"),
                            evidence = validateWorkingEvidenceArray(
                                item.optJSONArray("evidence"),
                                job.envelope,
                                sourceMap,
                                previousEvidence,
                            ),
                        ),
                    )
                }
            }
        }
        return ValidatedSummaryBatch(
            envelope = job.envelope,
            processedMessageIds = processed,
            events = events,
            workingItems = items,
        )
    }

    fun completeEmpty(
        job: LeasedMemoryJob,
        batch: ValidatedExtractionBatch,
    ) {
        if (batch.candidates.isNotEmpty()) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.INVALID_ARGUMENT,
                "non-empty extraction requires commit",
            )
        }
        complete(job, "extract", batch.processedMessageIds)
    }

    fun completeEmpty(
        job: LeasedMemoryJob,
        batch: ValidatedSummaryBatch,
    ) {
        if (batch.events.isNotEmpty() || batch.workingItems.isNotEmpty()) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.INVALID_ARGUMENT,
                "non-empty summary requires commit",
            )
        }
        complete(job, "summary", batch.processedMessageIds)
    }

    fun fail(
        job: LeasedMemoryJob,
        errorCode: String,
    ) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val terminal = job.attempts >= job.maxAttempts
            val delay = config.backgroundBackoffSeconds.getOrElse(
                (job.attempts - 1).coerceAtLeast(0),
            ) { 30L }
            db.update(
                "jobs",
                ContentValues().apply {
                    put("state", if (terminal) "failed" else "pending")
                    put("error_code", errorCode)
                    putNull("lease_owner")
                    putNull("lease_until")
                    put("next_attempt_at", now() + delay)
                    put("updated_at", now())
                },
                "scope_id=? AND job_id=?",
                arrayOf(job.scopeId, job.jobId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun complete(
        job: LeasedMemoryJob,
        kind: String,
        processedMessageIds: List<String>,
    ) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val current = readJob(db, job.scopeId, job.jobId)
                ?: throw MemoryV2Exception(MemoryV2ErrorCode.JOB_NOT_FOUND)
            if (current != "leased") {
                throw MemoryV2Exception(MemoryV2ErrorCode.JOB_TERMINAL)
            }
            processedMessageIds.forEach { messageId ->
                val source = job.envelope.sources.first { it.messageId == messageId }
                db.insertWithOnConflict(
                    "coverage",
                    null,
                    ContentValues().apply {
                        put("scope_id", job.scopeId)
                        put("session_id", job.sessionId)
                        put("message_id", messageId)
                        put("kind", kind)
                        put("source_revision", source.revision)
                        put("state", "done")
                        put("job_id", job.jobId)
                        putNull("reason")
                        put("updated_at", now())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                val column = if (kind == "summary") {
                    "summary_state"
                } else {
                    "extraction_state"
                }
                db.execSQL(
                    "UPDATE messages SET $column='done' " +
                        "WHERE scope_id=? AND message_id=?",
                    arrayOf(job.scopeId, messageId),
                )
            }
            db.update(
                "jobs",
                ContentValues().apply {
                    put("state", "saved")
                    putNull("lease_owner")
                    putNull("lease_until")
                    putNull("error_code")
                    put("updated_at", now())
                },
                "scope_id=? AND job_id=?",
                arrayOf(job.scopeId, job.jobId),
            )
            refreshLedgers(db, job.scopeId, "jobs", "messages", "coverage")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun validateEnvelope(root: JSONObject, job: LeasedMemoryJob) {
        val actual = root.optJSONObject("envelope")
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.ENVELOPE_MISMATCH)
        val expected = job.envelope.toJsonObject()
        if (
            MemoryV2OutputEnvelopePolicy.matchesCompact(
                actual = actual,
                expectedJobId = job.jobId,
            )
        ) {
            validateCurrentEnvelopeState(job)
            return
        }
        if (canonicalJson(actual) != canonicalJson(expected)) {
            throw MemoryV2Exception(MemoryV2ErrorCode.ENVELOPE_MISMATCH)
        }
        validateCurrentEnvelopeState(job)
    }

    private fun validateCurrentEnvelopeState(job: LeasedMemoryJob) {
        val scope = readScope(job.scopeId)
        if (scope == null ||
            scope.revision != job.envelope.baseScopeRevision ||
            scope.deletionEpoch != job.envelope.baseDeletionEpoch ||
            scope.personaRevision != job.envelope.personaRevision ||
            scope.importGeneration != job.envelope.importGeneration
        ) {
            throw MemoryV2Exception(MemoryV2ErrorCode.STALE_EPOCH)
        }
        val session = readSession(database.readableDatabase, job.scopeId, job.sessionId)
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
        if (session.workingVersion != job.envelope.baseWorkingSummaryVersion) {
            throw MemoryV2Exception(MemoryV2ErrorCode.STALE_SUMMARY)
        }
        val acceptedPromptVersions = if (job.kind == "summary") {
            setOf(
                config.versions["summary_prompt"] ?: "summary-2.2.0",
                "summary-2.1.2",
                "summary-2.1.0",
                "summary-2.0.3",
                "summary-2.0.2",
                "summary-2.0.1",
                "summary-2.0.0",
            )
        } else {
            setOf(
                config.versions["extraction_prompt"] ?: "extract-2.5.1",
                "extract-2.5.0",
                "extract-2.4.0",
                "extract-2.3.0",
                "extract-2.2.0",
                "extract-2.1.2",
                "extract-2.1.1",
                "extract-2.1.0",
                "extract-2.0.6",
                "extract-2.0.5",
                "extract-2.0.4",
                "extract-2.0.3",
                "extract-2.0.2",
                "extract-2.0.1",
                "extract-2.0.0",
            )
        }
        if (job.envelope.promptVersion !in acceptedPromptVersions) {
            throw MemoryV2Exception(MemoryV2ErrorCode.PROMPT_VERSION_MISMATCH)
        }
    }

    private fun validateCandidate(
        item: JSONObject,
        envelope: TrustedMemoryEnvelope,
        sources: Map<String, SourceBody>,
        requireRecentEvent: Boolean,
    ): ValidatedMemoryCandidate {
        if (
            item.has("pinned") ||
            item.has("protection_reasons") ||
            item.has("start_codepoint") ||
            item.has("end_codepoint")
        ) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        val reason = requiredString(item, "reason")
        if (reason !in CANDIDATE_REASONS) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        val strictSemantics = envelope.promptVersion.startsWith("extract-2.3.") ||
            envelope.promptVersion.startsWith("summary-2.3.")
        val tier = MemoryV2CandidateTierPolicy.resolve(
            explicitTier = item.optString("tier").takeIf { item.has("tier") },
            promptVersion = envelope.promptVersion,
            reason = reason,
            requireRecentEvent = requireRecentEvent,
        ) ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        if (requireRecentEvent && reason != "recent_event") {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        val rawOperation = item.optString("operation")
        val operation = if (strictSemantics) {
            MemoryV2ReconciliationPolicy.canonical(rawOperation)
        } else {
            MemoryV2ReconciliationPolicy.normalize(rawOperation)
        } ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        val rawRelationType = item.optString("relation_type")
            .takeIf { it.isNotBlank() && !item.isNull("relation_type") }
        val normalizedRelationType = rawRelationType?.let {
                if (strictSemantics) {
                    MemoryV2ReconciliationPolicy.canonicalRelationType(it)
                } else {
                    MemoryV2ReconciliationPolicy.normalizeRelationType(it)
                }
            }
        if (rawRelationType != null && normalizedRelationType == null) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        val relationType = normalizedRelationType
            ?: if (operation == MemoryV2ReconciliationPolicy.RELATE) {
                throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
            } else {
                null
            }
        if (
            operation != MemoryV2ReconciliationPolicy.RELATE &&
            rawRelationType != null
        ) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        if (
            operation == MemoryV2ReconciliationPolicy.RELATE &&
            reason != "recent_event"
        ) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        if (requireRecentEvent && operation != MemoryV2ReconciliationPolicy.ADD) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        val targetMemoryId = item.optString("target_memory_id")
            .takeIf { it.isNotBlank() && !item.isNull("target_memory_id") }
        val expectedVersion = if (item.isNull("expected_version")) {
            null
        } else {
            item.optInt("expected_version").takeIf { it > 0 }
                ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        MemoryV2ReconciliationPolicy.validateTarget(
            operation,
            targetMemoryId,
            expectedVersion,
        )?.let { throw MemoryV2Exception(it) }
        if (targetMemoryId != null) {
            val version = envelope.targetVersions[targetMemoryId]
            if (version == null || version != expectedVersion) {
                throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
            }
        } else if (expectedVersion != null) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        val value = item.opt("value")
        if (value !is String) {
            throw MemoryV2Exception(MemoryV2ErrorCode.VALUE_TYPE_INVALID)
        }
        val temporal = item.optJSONObject("temporal")
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        val normalizedTemporal = MemoryV2CandidateNormalizer.temporal(
            temporal,
            config.timezone,
        ) ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        val epistemic = MemoryV2CandidateNormalizer.epistemic(
            requiredString(item, "epistemic"),
            allowSynonyms = !strictSemantics,
        ) ?: run {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        val commitmentState = item.optString("commitment_state")
            .takeIf { it.isNotBlank() && !item.isNull("commitment_state") }
            ?.let { value ->
                if (strictSemantics) {
                    value.lowercase().takeIf {
                        it in CANDIDATE_COMMITMENT_STATES
                    } ?: throw MemoryV2Exception(
                        MemoryV2ErrorCode.SCHEMA_INVALID,
                    )
                } else {
                    when (value.lowercase()) {
                        "none", "declared", "accepted" -> null
                        "proposed", "active", "overdue_unknown", "completed" -> value
                        "confirmed" -> "active"
                        "declined", "rejected" -> "cancelled"
                        "cancelled" -> "cancelled"
                        else -> throw MemoryV2Exception(
                            MemoryV2ErrorCode.SCHEMA_INVALID,
                        )
                    }
                }
            }
        val evidence = validateEvidenceArray(
            item.optJSONArray("evidence"),
            envelope,
            sources,
        )
        return ValidatedMemoryCandidate(
            candidateId = requiredString(item, "candidate_id"),
            tier = tier,
            operation = operation,
            relationType = relationType,
            subjectId = requiredString(item, "subject_id"),
            viewpointId = item.optString("viewpoint_id")
                .takeIf { it.isNotBlank() && !item.isNull("viewpoint_id") },
            predicate = requiredString(item, "predicate"),
            text = requiredString(item, "text"),
            valueJson = JSONArray().put(value).toString()
                .removePrefix("[")
                .removeSuffix("]"),
            epistemic = epistemic,
            reason = reason,
            targetMemoryId = targetMemoryId,
            expectedVersion = expectedVersion,
            temporalJson = normalizedTemporal.toString(),
            commitmentState = commitmentState,
            importanceHint = if (strictSemantics) {
                val importance = (item.opt("importance_hint") as? Number)
                    ?.toDouble()
                    ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
                if (!importance.isFinite() || importance !in 0.0..1.0) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
                }
                importance
            } else {
                MemoryV2CandidateNormalizer.importance(
                    item.opt("importance_hint"),
                    allowLabelSynonyms = true,
                )
            },
            evidence = evidence,
        )
    }

    private fun validateEvidenceArray(
        array: JSONArray?,
        envelope: TrustedMemoryEnvelope,
        sources: Map<String, SourceBody>,
    ): List<ValidatedQuote> {
        if (array == null || array.length() == 0) {
            throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_EVIDENCE)
        }
        if (array.length() > config.maxEvidencePerMemory) {
            throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_EVIDENCE)
        }
        return buildList {
            for (index in 0 until array.length()) {
                val evidence = array.optJSONObject(index)
                    ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
                if (
                    evidence.has("start_codepoint") ||
                    evidence.has("end_codepoint") ||
                    evidence.has("source_sha256")
                ) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
                }
                val messageId = requiredString(evidence, "message_id")
                val source = sources[messageId]
                    ?: throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_SET_INVALID)
                val revision = evidence.optInt("source_revision")
                if (revision != source.revision) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_CHANGED)
                }
                val quote = requiredString(evidence, "quote")
                val location = MemoryV2QuoteLocator.locate(
                    source.body,
                    quote,
                    config.maxQuoteUtf8Bytes,
                )
                if (location.sourceSha256 != source.sha256) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_CHANGED)
                }
                add(ValidatedQuote(messageId, revision, quote, location))
            }
        }
    }

    private fun validateWorkingEvidenceArray(
        array: JSONArray?,
        envelope: TrustedMemoryEnvelope,
        sources: Map<String, SourceBody>,
        previousEvidence: Map<String, ValidatedQuote>,
    ): List<ValidatedQuote> {
        if (array == null || array.length() == 0) {
            throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_EVIDENCE)
        }
        if (array.length() > config.maxEvidencePerMemory) {
            throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_EVIDENCE)
        }
        return buildList {
            for (index in 0 until array.length()) {
                val evidence = array.optJSONObject(index)
                    ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
                val messageId = requiredString(evidence, "message_id")
                if (messageId in sources) {
                    add(
                        validateEvidenceArray(
                            JSONArray().put(evidence),
                            envelope,
                            sources,
                        ).single(),
                    )
                } else {
                    val key = workingEvidenceKey(
                        messageId = messageId,
                        revision = evidence.optInt("source_revision"),
                        quote = requiredString(evidence, "quote"),
                    )
                    add(
                        previousEvidence[key]
                            ?: throw MemoryV2Exception(
                                MemoryV2ErrorCode.SOURCE_SET_INVALID,
                            ),
                    )
                }
            }
        }
    }

    private fun loadPreviousWorkingEvidence(
        job: LeasedMemoryJob,
    ): Map<String, ValidatedQuote> {
        val raw = database.readableDatabase.query(
            "sessions",
            arrayOf("working_summary"),
            "scope_id=? AND session_id=?",
            arrayOf(job.scopeId, job.sessionId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            listOf("goals", "constraints", "unresolved", "references").forEach {
                section ->
                val items = root.optJSONArray(section) ?: return@forEach
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val evidence = item.optJSONArray("evidence") ?: continue
                    for (evidenceIndex in 0 until evidence.length()) {
                        val value = evidence.optJSONObject(evidenceIndex) ?: continue
                        val messageId = value.optString("message_id")
                        val quote = value.optString("quote")
                        val revision = value.optInt("source_revision", -1)
                        val sha256 = value.optString("source_sha256")
                        if (
                            messageId.isBlank() ||
                            quote.isBlank() ||
                            revision <= 0 ||
                            sha256.isBlank()
                        ) {
                            continue
                        }
                        put(
                            workingEvidenceKey(messageId, revision, quote),
                            ValidatedQuote(
                                messageId = messageId,
                                sourceRevision = revision,
                                quote = quote,
                                location = LocatedQuote(
                                    startCodepoint = value.optInt(
                                        "start_codepoint",
                                    ),
                                    endCodepoint = value.optInt("end_codepoint"),
                                    sourceSha256 = sha256,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun workingEvidenceKey(
        messageId: String,
        revision: Int,
        quote: String,
    ): String {
        return "$messageId\u0000$revision\u0000$quote"
    }

    private fun loadSources(
        envelope: TrustedMemoryEnvelope,
        allowedMessageIds: Set<String> = envelope.newSourceIds.toSet(),
    ): Map<String, SourceBody> {
        val db = database.readableDatabase
        return envelope.sources.filter { it.messageId in allowedMessageIds }.associate { source ->
            val body = db.query(
                "messages",
                arrayOf("body", "revision", "content_sha256", "state", "cache_expires_at"),
                "scope_id=? AND message_id=?",
                arrayOf(envelope.scopeId, source.messageId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_UNAVAILABLE)
                }
                if (
                    cursor.getString(3) != "live" ||
                    cursor.isNull(0) ||
                    cursor.getLong(4) <= now()
                ) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_UNAVAILABLE)
                }
                if (
                    cursor.getInt(1) != source.revision ||
                    cursor.getString(2) != source.sha256
                ) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_CHANGED)
                }
                SourceBody(
                    messageId = source.messageId,
                    revision = source.revision,
                    sha256 = source.sha256,
                    body = cursor.getString(0),
                )
            }
            source.messageId to body
        }
    }

    private fun readSource(
        db: SQLiteDatabase,
        scopeId: String,
        sessionId: String,
        messageId: String,
    ): SourceBody {
        db.query(
            "messages",
            arrayOf("session_id", "revision", "content_sha256", "body", "state", "cache_expires_at"),
            "scope_id=? AND message_id=?",
            arrayOf(scopeId, messageId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != sessionId) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            if (
                cursor.getString(4) != "live" ||
                cursor.isNull(3) ||
                cursor.getLong(5) <= now()
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_UNAVAILABLE)
            }
            return SourceBody(
                messageId = messageId,
                revision = cursor.getInt(1),
                sha256 = cursor.getString(2),
                body = cursor.getString(3),
            )
        }
    }

    private fun readScope(scopeId: String): TrustedScopeContext? {
        return readScope(database.readableDatabase, scopeId)
    }

    private fun readScope(
        db: SQLiteDatabase,
        scopeId: String,
    ): TrustedScopeContext? {
        db.query(
            "scopes",
            null,
            "scope_id=?",
            arrayOf(scopeId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return requireScope(cursor)
        }
    }

    private fun requireScope(db: SQLiteDatabase, scopeId: String): TrustedScopeContext {
        return readScope(db, scopeId)
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
    }

    private fun requireScope(cursor: Cursor): TrustedScopeContext {
        return TrustedScopeContext(
            scopeId = cursor.string("scope_id"),
            key = MemoryScopeKey(
                ownerId = cursor.string("owner_id"),
                contactId = cursor.string("contact_id"),
                agentId = cursor.string("agent_id"),
                personaId = cursor.string("persona_id"),
                realmId = cursor.string("realm_id"),
            ),
            personaRevision = cursor.int("persona_revision"),
            revision = cursor.int("revision"),
            deletionEpoch = cursor.int("deletion_epoch"),
            importGeneration = cursor.int("import_generation"),
            retentionPolicy = cursor.string("retention_policy"),
            state = cursor.string("state"),
            restoreGuardId = cursor.string("restore_guard_id"),
        )
    }

    private fun readSession(
        db: SQLiteDatabase,
        scopeId: String,
        sessionId: String,
    ): SessionSnapshot? {
        db.query(
            "sessions",
            arrayOf("working_version"),
            "scope_id=? AND session_id=?",
            arrayOf(scopeId, sessionId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return SessionSnapshot(cursor.getInt(0))
        }
    }

    private fun readJob(
        db: SQLiteDatabase,
        scopeId: String,
        jobId: String,
    ): String? {
        db.query(
            "jobs",
            arrayOf("state"),
            "scope_id=? AND job_id=?",
            arrayOf(scopeId, jobId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun insertDependency(
        db: SQLiteDatabase,
        scopeId: String,
        parentKind: String,
        parentId: String,
        childKind: String,
        childId: String,
        revision: Int,
    ) {
        db.insertWithOnConflict(
            "dependencies",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put(
                    "dependency_id",
                    MemoryV2Crypto.sha256(
                        "$scopeId|$parentKind|$parentId|$childKind|$childId",
                    ),
                )
                put("parent_kind", parentKind)
                put("parent_id", parentId)
                put("child_kind", childKind)
                put("child_id", childId)
                put("source_revision", revision)
                put("created_at", now())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun assertJobCapacity(db: SQLiteDatabase, scopeId: String) {
        val limit = config.resources["jobs"] ?: return
        val count = db.rawQuery(
            "SELECT COUNT(*) FROM jobs WHERE scope_id=?",
            arrayOf(scopeId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        if (count + 1 > limit.maxRecords) {
            throw MemoryV2Exception(MemoryV2ErrorCode.RESOURCE_CAPACITY_EXHAUSTED)
        }
    }

    private fun refreshLedgers(
        db: SQLiteDatabase,
        scopeId: String,
        vararg resources: String,
    ) {
        resources.forEach { resource ->
            val table = when (resource) {
                "jobs" -> "jobs"
                "dependencies" -> "dependencies"
                "messages" -> "messages"
                "coverage" -> "coverage"
                else -> return@forEach
            }
            val expression = when (table) {
                "jobs" -> "length(CAST(envelope_json AS BLOB))"
                "messages" -> "COALESCE(length(CAST(body AS BLOB)),0)+length(CAST(origin_json AS BLOB))"
                else -> "0"
            }
            val usage = db.rawQuery(
                "SELECT COALESCE(SUM($expression+${config.rowOverheadBytes}),0)," +
                    "COUNT(*) FROM $table WHERE scope_id=?",
                arrayOf(scopeId),
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0) to cursor.getInt(1)
            }
            db.insertWithOnConflict(
                "capacity_ledger",
                null,
                ContentValues().apply {
                    put("scope_id", scopeId)
                    put("resource", resource)
                    put("used_bytes", usage.first)
                    put("used_records", usage.second)
                    put("reservation_bytes", 0)
                    put("accounting_version", 1)
                    put("last_tx_id", UUID.randomUUID().toString())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    private fun parseObject(raw: String): JSONObject {
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
    }

    private fun requiredString(item: JSONObject, key: String): String {
        return item.optString(key).takeIf { it.isNotBlank() }
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
    }

    private fun stringArray(array: JSONArray?): List<String> {
        if (array == null) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index)
                if (value.isBlank() || value in this) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
                }
                add(value)
            }
        }
    }

    private fun canonicalJson(value: Any?): String {
        return when (value) {
            is JSONObject -> value.keys().asSequence()
                .sorted()
                .joinToString(prefix = "{", postfix = "}") {
                    JSONObject.quote(it) + ":" + canonicalJson(value.opt(it))
                }
            is JSONArray -> (0 until value.length()).joinToString(
                prefix = "[",
                postfix = "]",
            ) {
                canonicalJson(value.opt(it))
            }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            null, JSONObject.NULL -> "null"
            else -> JSONObject.quote(value.toString())
        }
    }

    private fun parseEnvelope(root: JSONObject): TrustedMemoryEnvelope {
        val sourcesJson = root.optJSONArray("sources")
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        return TrustedMemoryEnvelope(
            schemaVersion = requiredString(root, "schema_version"),
            scopeId = requiredString(root, "scope_id"),
            sessionId = requiredString(root, "session_id"),
            jobId = requiredString(root, "job_id"),
            kind = requiredString(root, "kind"),
            baseScopeRevision = root.optInt("base_scope_revision"),
            baseDeletionEpoch = root.optInt("base_deletion_epoch"),
            baseWorkingSummaryVersion = root.optInt(
                "base_working_summary_version",
            ),
            personaRevision = root.optInt("persona_revision"),
            importGeneration = root.optInt("import_generation"),
            policyVersion = requiredString(root, "policy_version"),
            configVersion = requiredString(root, "config_version"),
            promptVersion = requiredString(root, "prompt_version"),
            sources = buildList {
                for (index in 0 until sourcesJson.length()) {
                    val item = sourcesJson.optJSONObject(index)
                        ?: throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
                    add(
                        MemorySourceRef(
                            messageId = requiredString(item, "message_id"),
                            revision = item.optInt("revision"),
                            sha256 = requiredString(item, "sha256"),
                        ),
                    )
                }
            },
            newSourceIds = stringArray(root.optJSONArray("new_source_ids")),
            targetVersions = buildMap {
                val versions = root.optJSONObject("target_versions") ?: JSONObject()
                versions.keys().forEach { key ->
                    put(key, versions.optInt(key))
                }
            },
            createdAt = root.optLong("created_at"),
        )
    }

    private data class SourceBody(
        val messageId: String,
        val revision: Int,
        val sha256: String,
        val body: String,
    )

    private data class SessionSnapshot(
        val workingVersion: Int,
    )

    private data class StaleJobCandidate(
        val scopeId: String,
        val jobId: String,
        val sessionId: String,
        val kind: String,
        val envelope: TrustedMemoryEnvelope,
    )

    private fun Cursor.string(column: String): String {
        return getString(getColumnIndexOrThrow(column)).orEmpty()
    }

    private fun Cursor.int(column: String): Int {
        return getInt(getColumnIndexOrThrow(column))
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    companion object {
        private val CANDIDATE_REASONS = setOf(
            "durable_preference",
            "confirmed_commitment",
            "important_experience",
            "attributed_impression",
            "recent_event",
        )
        private val CANDIDATE_COMMITMENT_STATES = setOf(
            "proposed",
            "active",
            "overdue_unknown",
            "completed",
            "cancelled",
        )
        private const val SUMMARY_SCHEDULER_PREFS = "memory_v2_summary_scheduler"
        private const val SUMMARY_CONTRACT_REVISION_KEY = "contract_revision"
        private const val SUMMARY_CONTRACT_REVISION = 3
        private const val EXTRACTION_SCHEDULER_PREFS = "memory_v2_extraction_scheduler"
        private const val EXTRACTION_PROMPT_REVISION_KEY = "prompt_revision"
        private val SUMMARY_SCHEDULER_LOCK = Any()
        private val EXTRACTION_SCHEDULER_LOCK = Any()
    }
}
