package com.local.wxbotprobe.memory.v2

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class MemoryV2BatchCommitResult(
    val status: String,
    val jobId: String,
    val memoryIds: List<String>,
    val workingVersion: Int? = null,
)

class MemoryV2BatchCommitService(
    context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val config = MemoryV2ConfigLoader.load(context)
    private val predicates = MemoryV2PredicateRegistry.load(context)
    private val crypto = MemoryV2Crypto()

    fun commitExtraction(
        job: LeasedMemoryJob,
        batch: ValidatedExtractionBatch,
    ): MemoryV2BatchCommitResult {
        require(job.kind == "extract") { "not an extraction job" }
        return transaction(job) { db ->
            val state = currentJobState(db, job)
            if (state == "saved") {
                return@transaction MemoryV2BatchCommitResult(
                    status = "unchanged",
                    jobId = job.jobId,
                    memoryIds = emptyList(),
                )
            }
            if (state != "leased") {
                throw MemoryV2Exception(MemoryV2ErrorCode.JOB_TERMINAL)
            }
            revalidate(job)
            val ids = batch.candidates.filterNot {
                isProtectedPersonaCandidate(db, job.scopeId, it)
            }.map {
                writeCandidate(db, job, it, background = true)
            }
            markJobDone(db, job, "extract")
            MemoryV2BatchCommitResult(
                status = "saved",
                jobId = job.jobId,
                memoryIds = ids,
            )
        }
    }

    fun commitSummary(
        job: LeasedMemoryJob,
        batch: ValidatedSummaryBatch,
    ): MemoryV2BatchCommitResult {
        require(job.kind == "summary") { "not a summary job" }
        return transaction(job) { db ->
            val state = currentJobState(db, job)
            if (state == "saved") {
                return@transaction MemoryV2BatchCommitResult(
                    status = "unchanged",
                    jobId = job.jobId,
                    memoryIds = emptyList(),
                )
            }
            if (state != "leased") {
                throw MemoryV2Exception(MemoryV2ErrorCode.JOB_TERMINAL)
            }
            revalidate(job)
            val ids = batch.events.filterNot {
                isProtectedPersonaCandidate(db, job.scopeId, it)
            }.map {
                writeCandidate(db, job, it, background = true)
            }
            val workingVersion = currentWorkingVersion(db, job)
            val previousSources = currentWorkingSources(db, job)
            val working = renderWorkingSummary(batch.workingItems)
            val workingLimit = config.resources["sessions"]?.maxBytes
                ?: Long.MAX_VALUE
            val workingBytes = working.toString()
                .toByteArray(Charsets.UTF_8)
                .size
            if (
                workingBytes > workingLimit ||
                !MemoryV2WorkingSummaryPolicy.withinBound(
                    working.toString(),
                    config.workingSummaryMaxUtf8Bytes,
                )
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.RESOURCE_CAPACITY_EXHAUSTED)
            }
            val coveredSources = MemoryV2WorkingSummaryPolicy
                .mergeCoveredSources(
                    previousSources,
                    job.envelope.newSourceIds,
                )
            db.update(
                "sessions",
                ContentValues().apply {
                    put("working_summary", working.toString())
                    put(
                        "working_sources",
                        JSONArray(coveredSources).toString(),
                    )
                    put("working_version", workingVersion + 1)
                    put("updated_at", now())
                },
                "scope_id=? AND session_id=?",
                arrayOf(job.scopeId, job.sessionId),
            )
            job.envelope.sources.forEach { source ->
                db.insertWithOnConflict(
                    "dependencies",
                    null,
                    ContentValues().apply {
                        put("scope_id", job.scopeId)
                        put(
                            "dependency_id",
                            MemoryV2Crypto.sha256(
                                "${job.scopeId}|message|${source.messageId}|" +
                                    "working_summary|${job.sessionId}",
                            ),
                        )
                        put("parent_kind", "message")
                        put("parent_id", source.messageId)
                        put("child_kind", "working_summary")
                        put("child_id", job.sessionId)
                        put("source_revision", source.revision)
                        put("created_at", now())
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            markJobDone(db, job, "summary")
            MemoryV2BatchCommitResult(
                status = "saved",
                jobId = job.jobId,
                memoryIds = ids,
                workingVersion = workingVersion + 1,
            )
        }
    }

    private fun transaction(
        job: LeasedMemoryJob,
        block: (SQLiteDatabase) -> MemoryV2BatchCommitResult,
    ): MemoryV2BatchCommitResult {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            refreshLedgers(
                db,
                job.scopeId,
                "memories",
                "evidence",
                "dependencies",
                "memory_conflicts",
                "memory_relations",
                "sessions",
                "outbox",
                "jobs",
                "messages",
            )
            db.setTransactionSuccessful()
            return result
        } catch (error: MemoryV2Exception) {
            throw error
        } catch (error: Throwable) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE,
                error.message.orEmpty(),
            )
        } finally {
            db.endTransaction()
        }
    }

    private fun writeCandidate(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
        candidate: ValidatedMemoryCandidate,
        background: Boolean,
    ): String {
        val predicate = predicates.rule(candidate.predicate)
        if (background && candidate.epistemic == "inferred" &&
            candidate.predicate.startsWith("profile.")
        ) {
            throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_EVIDENCE)
        }
        if (predicate.requiresViewpoint && candidate.viewpointId.isNullOrBlank()) {
            throw MemoryV2Exception(MemoryV2ErrorCode.VIEWPOINT_REQUIRED)
        }
        val sources = candidate.evidence.map {
            revalidateEvidence(db, job, it)
        }
        ensureEntity(db, job.scopeId, candidate.subjectId)
        candidate.viewpointId?.let { ensureEntity(db, job.scopeId, it) }
        val tier = if (candidate.reason == "recent_event") "A2" else "A1"
        val eventId = if (
            candidate.operation == MemoryV2ReconciliationPolicy.RELATE
        ) {
            UUID.randomUUID().toString()
        } else {
            candidate.targetMemoryId ?: UUID.randomUUID().toString()
        }
        val factKey = factKey(
            job.scopeId,
            candidate.subjectId,
            candidate.predicate,
            candidate.viewpointId,
            candidate.valueJson,
            predicate.cardinality,
            eventId,
        )
        val existing = if (candidate.targetMemoryId != null) {
            findMemory(db, job.scopeId, candidate.targetMemoryId)
        } else {
            findByFact(db, job.scopeId, factKey)
        }
        if (candidate.operation != MemoryV2ReconciliationPolicy.ADD &&
            existing == null
        ) {
            throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
        }
        if (existing != null) {
            if (
                candidate.targetMemoryId != null &&
                candidate.expectedVersion != existing.version
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
            }
            if (existing.lifecycle != "active" || existing.retractedAt != null) {
                throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
            }
            if (
                MemoryV2ConflictConfirmationPolicy.isProtectedPersona(
                    entityKind(db, job.scopeId, candidate.subjectId),
                    candidate.predicate,
                )
            ) {
                return existing.memoryId
            }
            if (existing.tier == "A0") {
                recordConflict(db, job, candidate, existing, sources)
                return existing.memoryId
            }
            if (candidate.operation == MemoryV2ReconciliationPolicy.ADD) {
                return if (existing.valueJson == candidate.valueJson) {
                    corroborate(
                        db,
                        job,
                        candidate,
                        existing,
                        sources,
                    )
                } else {
                    recordConflict(db, job, candidate, existing, sources)
                    existing.memoryId
                }
            }
            if (candidate.operation != MemoryV2ReconciliationPolicy.RELATE) {
                ensureSameFactIdentity(existing, candidate)
            }
            val userSourceTexts = sources
                .filter { it.role == "user" }
                .map { it.body }
            return when (candidate.operation) {
                MemoryV2ReconciliationPolicy.CORROBORATE -> {
                    if (existing.valueJson != candidate.valueJson) {
                        recordConflict(db, job, candidate, existing, sources)
                        existing.memoryId
                    } else {
                        corroborate(db, job, candidate, existing, sources)
                    }
                }
                MemoryV2ReconciliationPolicy.SUPERSEDE -> {
                    when (
                        MemoryV2ConflictConfirmationPolicy.decide(
                            existingTier = existing.tier,
                            candidateOperation = candidate.operation,
                            subjectKind = entityKind(
                                db,
                                job.scopeId,
                                candidate.subjectId,
                            ),
                            predicate = candidate.predicate,
                            userSourceTexts = userSourceTexts,
                        )
                    ) {
                        MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE -> {
                            supersede(db, job, candidate, existing, sources)
                            if (
                                findMemory(
                                    db,
                                    job.scopeId,
                                    existing.memoryId,
                                )?.version != existing.version
                            ) {
                                resolvePendingClarifications(
                                    db,
                                    job.scopeId,
                                    existing.memoryId,
                                existing.version,
                                    "accepted_candidate",
                                )
                            }
                            existing.memoryId
                        }
                        MemoryV2ConflictConfirmationDecision.KEEP_EXISTING -> {
                            resolvePendingClarifications(
                                db,
                                job.scopeId,
                                existing.memoryId,
                                existing.version,
                                "keep_existing",
                            )
                            existing.memoryId
                        }
                        MemoryV2ConflictConfirmationDecision.PROTECTED ->
                            existing.memoryId
                        else -> {
                            recordConflict(
                                db,
                                job,
                                candidate,
                                existing,
                                sources,
                            )
                            existing.memoryId
                        }
                    }
                }
                MemoryV2ReconciliationPolicy.CONTRADICT -> {
                    if (
                        MemoryV2ConflictConfirmationPolicy
                            .isAmbiguousAcknowledgement(userSourceTexts)
                    ) {
                        resolvePendingClarifications(
                            db,
                            job.scopeId,
                            existing.memoryId,
                            existing.version,
                            "keep_existing",
                        )
                    } else {
                        recordConflict(db, job, candidate, existing, sources)
                    }
                    existing.memoryId
                }
                MemoryV2ReconciliationPolicy.RELATE -> {
                    if (
                        existing.tier != "A1" ||
                        existing.subjectId != candidate.subjectId
                    ) {
                        throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
                    }
                    val sourceMemoryId = addMemory(
                        db,
                        job,
                        candidate,
                        sources,
                        tier,
                        factKey,
                    )
                    insertRelation(
                        db = db,
                        scopeId = job.scopeId,
                        sourceMemoryId = sourceMemoryId,
                        target = existing,
                        relationType = candidate.relationType
                            ?: throw MemoryV2Exception(
                                MemoryV2ErrorCode.SCHEMA_INVALID,
                            ),
                    )
                    sourceMemoryId
                }
                MemoryV2ReconciliationPolicy.RETRACT -> {
                    if (existing.valueJson != candidate.valueJson) {
                        throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
                    }
                    retract(db, job, candidate, existing, sources)
                }
                MemoryV2ReconciliationPolicy.NOOP -> {
                    if (
                        MemoryV2ConflictConfirmationPolicy
                            .isAmbiguousAcknowledgement(userSourceTexts)
                    ) {
                        resolvePendingClarifications(
                            db,
                            job.scopeId,
                            existing.memoryId,
                            existing.version,
                            "keep_existing",
                        )
                    }
                    existing.memoryId
                }
                else -> throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
            }
        }
        return addMemory(
            db,
            job,
            candidate,
            sources,
            tier,
            factKey,
        )
    }

    private fun addMemory(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
        candidate: ValidatedMemoryCandidate,
        sources: List<SourceEvidence>,
        tier: String,
        factKey: String,
    ): String {
        val anchor = sources.maxOf { it.receivedAt }
        val expiresAt = if (tier == "A2") {
            MemoryV2TemporalPolicy.expiryFor(
                reason = candidate.reason,
                temporalJson = candidate.temporalJson,
                commitmentState = candidate.commitmentState,
                sourceAnchorEpochSeconds = anchor,
                ttlSeconds = config.tiers[tier]?.ttlSeconds
                    ?: throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT),
                fallbackTimezone = config.timezone,
            )
        } else {
            null
        }
        if (expiresAt != null && expiresAt <= now()) {
            throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_EXPIRED)
        }
        val memoryId = UUID.randomUUID().toString()
        val memoryText = MemoryV2MemoryWording.forCurrentPersona(candidate.text)
        val byteSize = (
            memoryText + candidate.valueJson + candidate.temporalJson
            ).toByteArray(Charsets.UTF_8).size
        assertTierCapacity(
            db,
            job.scopeId,
            tier,
            byteSize + config.rowOverheadBytes,
        )
        db.insertOrThrow(
            "memories",
            null,
            ContentValues().apply {
                put("scope_id", job.scopeId)
                put("memory_id", memoryId)
                put("version", 1)
                put("tier", tier)
                put("fact_key", factKey)
                put("subject_id", candidate.subjectId)
                if (candidate.viewpointId == null) {
                    putNull("viewpoint_id")
                } else {
                    put("viewpoint_id", candidate.viewpointId)
                }
                put("predicate", candidate.predicate)
                put("text", memoryText)
                put("value_json", candidate.valueJson)
                put("epistemic", candidate.epistemic)
                put(
                    "lifecycle",
                    if (candidate.commitmentState == "proposed") {
                        "needs_review"
                    } else {
                        "active"
                    },
                )
                putNull("retracted_at")
                put("pinned", 0)
                if (candidate.commitmentState == null) {
                    putNull("commitment_state")
                } else {
                    put(
                        "commitment_state",
                        if (candidate.commitmentState == "active") {
                            "proposed"
                        } else {
                            candidate.commitmentState
                        },
                    )
                }
                put("temporal_json", candidate.temporalJson)
                put("retention_score", candidate.importanceHint)
                put("source_anchor", anchor)
                if (expiresAt == null) putNull("expires_at") else put(
                    "expires_at",
                    expiresAt,
                )
                put("created_at", now())
                put("updated_at", now())
                put("deletion_epoch", queryLong(
                    db,
                    "SELECT deletion_epoch FROM scopes WHERE scope_id=?",
                    arrayOf(job.scopeId),
                ))
            },
        )
        insertEvidence(db, job.scopeId, memoryId, sources)
        emitOutbox(db, job.scopeId, memoryId, 1)
        bumpScopeRevision(db, job.scopeId)
        return memoryId
    }

    private fun insertRelation(
        db: SQLiteDatabase,
        scopeId: String,
        sourceMemoryId: String,
        target: ExistingMemory,
        relationType: String,
    ) {
        db.insertWithOnConflict(
            "memory_relations",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put(
                    "relation_id",
                    MemoryV2Crypto.sha256(
                        "$scopeId|$sourceMemoryId|${target.memoryId}|$relationType",
                    ),
                )
                put("source_memory_id", sourceMemoryId)
                put("target_memory_id", target.memoryId)
                put("relation_type", relationType)
                put("target_version", target.version)
                put("created_at", now())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun ensureSameFactIdentity(
        existing: ExistingMemory,
        candidate: ValidatedMemoryCandidate,
    ) {
        if (
            existing.subjectId != candidate.subjectId ||
            existing.predicate != candidate.predicate ||
            existing.viewpointId != candidate.viewpointId
        ) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
    }

    private fun corroborate(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
        candidate: ValidatedMemoryCandidate,
        existing: ExistingMemory,
        sources: List<SourceEvidence>,
    ): String {
        val newEvidence = sources.filterNot {
            evidenceExists(db, job.scopeId, existing.memoryId, it)
        }
        if (newEvidence.isEmpty()) {
            return existing.memoryId
        }
        writeHistory(db, job.scopeId, existing)
        makeEvidenceRoom(db, job.scopeId, existing.memoryId, newEvidence.size)
        insertEvidence(db, job.scopeId, existing.memoryId, newEvidence)
        val version = existing.version + 1
        val updated = db.update(
            "memories",
            ContentValues().apply {
                put("version", version)
                put(
                    "source_anchor",
                    maxOf(existing.sourceAnchor, newEvidence.maxOf { it.receivedAt }),
                )
                put(
                    "retention_score",
                    maxOf(existing.retentionScore, candidate.importanceHint),
                )
                put("updated_at", now())
            },
            "scope_id=? AND memory_id=? AND version=? AND deletion_epoch=?",
            arrayOf(
                job.scopeId,
                existing.memoryId,
                existing.version.toString(),
                existing.deletionEpoch.toString(),
            ),
        )
        if (updated != 1) {
            throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
        }
        emitOutbox(db, job.scopeId, existing.memoryId, version)
        bumpScopeRevision(db, job.scopeId)
        return existing.memoryId
    }

    private fun supersede(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
        candidate: ValidatedMemoryCandidate,
        existing: ExistingMemory,
        sources: List<SourceEvidence>,
    ): String {
        if (existing.valueJson == candidate.valueJson) {
            throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
        }
        val rule = predicates.rule(candidate.predicate)
        val nextFactKey = factKey(
            job.scopeId,
            candidate.subjectId,
            candidate.predicate,
            candidate.viewpointId,
            candidate.valueJson,
            rule.cardinality,
            existing.memoryId,
        )
        val duplicate = findByFact(db, job.scopeId, nextFactKey)
        if (duplicate != null && duplicate.memoryId != existing.memoryId) {
            recordConflict(db, job, candidate, existing, sources)
            return existing.memoryId
        }
        val anchor = sources.maxOf { it.receivedAt }
        val expiresAt = if (existing.tier == "A2") {
            val calculated = MemoryV2TemporalPolicy.expiryFor(
                reason = candidate.reason,
                temporalJson = candidate.temporalJson,
                commitmentState = candidate.commitmentState,
                sourceAnchorEpochSeconds = anchor,
                ttlSeconds = config.tiers[existing.tier]?.ttlSeconds
                    ?: throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT),
                fallbackTimezone = config.timezone,
            )
            minOf(calculated, existing.expiresAt ?: Long.MAX_VALUE)
        } else {
            null
        }
        if (expiresAt != null && expiresAt <= now()) {
            throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_EXPIRED)
        }
        writeHistory(db, job.scopeId, existing)
        db.delete(
            "evidence",
            "scope_id=? AND memory_id=?",
            arrayOf(job.scopeId, existing.memoryId),
        )
        val version = existing.version + 1
        val updated = db.update(
            "memories",
            ContentValues().apply {
                put("version", version)
                put("fact_key", nextFactKey)
                put("subject_id", candidate.subjectId)
                if (candidate.viewpointId == null) {
                    putNull("viewpoint_id")
                } else {
                    put("viewpoint_id", candidate.viewpointId)
                }
                put("predicate", candidate.predicate)
                put(
                    "text",
                    MemoryV2MemoryWording.forCurrentPersona(candidate.text),
                )
                put("value_json", candidate.valueJson)
                put("epistemic", candidate.epistemic)
                put(
                    "lifecycle",
                    if (candidate.commitmentState == "proposed") {
                        "needs_review"
                    } else {
                        "active"
                    },
                )
                putNull("retracted_at")
                if (candidate.commitmentState == null) {
                    putNull("commitment_state")
                } else {
                    put(
                        "commitment_state",
                        if (candidate.commitmentState == "active") {
                            "proposed"
                        } else {
                            candidate.commitmentState
                        },
                    )
                }
                put("temporal_json", candidate.temporalJson)
                put(
                    "retention_score",
                    maxOf(existing.retentionScore, candidate.importanceHint),
                )
                put("source_anchor", anchor)
                if (expiresAt == null) putNull("expires_at") else put(
                    "expires_at",
                    expiresAt,
                )
                put("updated_at", now())
            },
            "scope_id=? AND memory_id=? AND version=? AND deletion_epoch=?",
            arrayOf(
                job.scopeId,
                existing.memoryId,
                existing.version.toString(),
                existing.deletionEpoch.toString(),
            ),
        )
        if (updated != 1) {
            throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
        }
        insertEvidence(db, job.scopeId, existing.memoryId, sources)
        emitOutbox(db, job.scopeId, existing.memoryId, version)
        bumpScopeRevision(db, job.scopeId)
        return existing.memoryId
    }

    private fun retract(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
        candidate: ValidatedMemoryCandidate,
        existing: ExistingMemory,
        sources: List<SourceEvidence>,
    ): String {
        writeHistory(db, job.scopeId, existing)
        val newEvidence = sources.filterNot {
            evidenceExists(db, job.scopeId, existing.memoryId, it)
        }
        makeEvidenceRoom(db, job.scopeId, existing.memoryId, newEvidence.size)
        insertEvidence(db, job.scopeId, existing.memoryId, newEvidence)
        val version = existing.version + 1
        val updated = db.update(
            "memories",
            ContentValues().apply {
                put("version", version)
                put("retracted_at", now())
                put("updated_at", now())
                put(
                    "retention_score",
                    maxOf(existing.retentionScore, candidate.importanceHint),
                )
            },
            "scope_id=? AND memory_id=? AND version=? AND deletion_epoch=?",
            arrayOf(
                job.scopeId,
                existing.memoryId,
                existing.version.toString(),
                existing.deletionEpoch.toString(),
            ),
        )
        if (updated != 1) {
            throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
        }
        emitOutbox(db, job.scopeId, existing.memoryId, version)
        bumpScopeRevision(db, job.scopeId)
        return existing.memoryId
    }

    private fun recordConflict(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
        candidate: ValidatedMemoryCandidate,
        existing: ExistingMemory,
        sources: List<SourceEvidence>,
    ) {
        val payload = JSONObject()
            .put("candidate_id", candidate.candidateId)
            .put("operation", candidate.operation)
            .put("relation_type", candidate.relationType ?: JSONObject.NULL)
            .put("subject_id", candidate.subjectId)
            .put("viewpoint_id", candidate.viewpointId ?: JSONObject.NULL)
            .put("predicate", candidate.predicate)
            .put("tier", existing.tier)
            .put("text", candidate.text)
            .put("value_json", candidate.valueJson)
            .put("epistemic", candidate.epistemic)
            .put("reason", candidate.reason)
            .put("importance_hint", candidate.importanceHint)
            .put("temporal", JSONObject(candidate.temporalJson))
            .put(
                "evidence",
                JSONArray().apply {
                    sources.forEach { source ->
                        put(
                            JSONObject()
                                .put("message_id", source.messageId)
                                .put("source_revision", source.revision)
                                .put("quote", source.quote)
                                .put("start_codepoint", source.start)
                                .put("end_codepoint", source.end)
                                .put("source_sha256", source.sha256),
                        )
                    }
                },
            )
        val conflictId = MemoryV2Crypto.sha256(
            "${job.scopeId}|${existing.memoryId}|${existing.version}|" +
                payload.toString(),
        )
        db.insertWithOnConflict(
            "memory_conflicts",
            null,
            ContentValues().apply {
                put("scope_id", job.scopeId)
                put("conflict_id", conflictId)
                put("memory_id", existing.memoryId)
                put("target_version", existing.version)
                put("operation", candidate.operation)
                put("candidate_json", payload.toString())
                put("state", "pending")
                put("created_at", now())
                put("updated_at", now())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun resolvePendingClarifications(
        db: SQLiteDatabase,
        scopeId: String,
        memoryId: String,
        targetVersion: Int,
        resolution: String,
    ) {
        db.update(
            "memory_conflicts",
            ContentValues().apply {
                put("state", "resolved")
                put("resolution", resolution)
                put("resolved_at", now())
                put("updated_at", now())
            },
            "scope_id=? AND memory_id=? AND target_version=? AND state='pending'",
            arrayOf(scopeId, memoryId, targetVersion.toString()),
        )
    }

    private fun evidenceExists(
        db: SQLiteDatabase,
        scopeId: String,
        memoryId: String,
        source: SourceEvidence,
    ): Boolean {
        return db.query(
            "evidence",
            arrayOf("evidence_id"),
            "scope_id=? AND memory_id=? AND source_message_id=? " +
                "AND source_revision=? AND start_codepoint=? AND end_codepoint=?",
            arrayOf(
                scopeId,
                memoryId,
                source.messageId,
                source.revision.toString(),
                source.start.toString(),
                source.end.toString(),
            ),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }
    }

    private fun makeEvidenceRoom(
        db: SQLiteDatabase,
        scopeId: String,
        memoryId: String,
        incoming: Int,
    ) {
        val existing = db.query(
            "evidence",
            arrayOf("evidence_id"),
            "scope_id=? AND memory_id=?",
            arrayOf(scopeId, memoryId),
            null,
            null,
            "captured_at,evidence_id",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val remove = (existing.size + incoming - config.maxEvidencePerMemory)
            .coerceAtLeast(0)
        existing.take(remove).forEach { evidenceId ->
            db.delete(
                "evidence",
                "scope_id=? AND memory_id=? AND evidence_id=?",
                arrayOf(scopeId, memoryId, evidenceId),
            )
        }
    }

    private fun insertEvidence(
        db: SQLiteDatabase,
        scopeId: String,
        memoryId: String,
        sources: List<SourceEvidence>,
    ) {
        sources.forEach { source ->
            db.insertWithOnConflict(
                "evidence",
                null,
                ContentValues().apply {
                    put("scope_id", scopeId)
                    put(
                        "evidence_id",
                        MemoryV2Crypto.sha256(
                            "$scopeId|$memoryId|${source.messageId}|" +
                                "${source.revision}|${source.start}|${source.end}",
                        ),
                    )
                    put("memory_id", memoryId)
                    put("source_message_id", source.messageId)
                    put("source_revision", source.revision)
                    put("source_kind", source.role)
                    put("quote", source.quote)
                    put("start_codepoint", source.start)
                    put("end_codepoint", source.end)
                    put("source_sha256", source.sha256)
                    put("captured_at", now())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            insertDependency(
                db,
                scopeId,
                "message",
                source.messageId,
                "memory",
                memoryId,
                source.revision,
            )
        }
    }

    private fun writeHistory(
        db: SQLiteDatabase,
        scopeId: String,
        memory: ExistingMemory,
    ) {
        val snapshot = JSONObject()
            .put("memory_id", memory.memoryId)
            .put("version", memory.version)
            .put("fact_key", memory.factKey)
            .put("tier", memory.tier)
            .put("subject_id", memory.subjectId)
            .put("viewpoint_id", memory.viewpointId ?: JSONObject.NULL)
            .put("predicate", memory.predicate)
            .put("text", memory.text)
            .put("value_json", memory.valueJson)
            .put("epistemic", memory.epistemic)
            .put("lifecycle", memory.lifecycle)
            .put("commitment_state", memory.commitmentState ?: JSONObject.NULL)
            .put("temporal_json", memory.temporalJson)
            .put("retention_score", memory.retentionScore)
            .put("source_anchor", memory.sourceAnchor)
            .put("expires_at", memory.expiresAt ?: JSONObject.NULL)
            .put("retracted_at", memory.retractedAt ?: JSONObject.NULL)
            .put("updated_at", memory.updatedAt)
            .put(
                "evidence",
                JSONArray().apply {
                    db.query(
                        "evidence",
                        arrayOf(
                            "evidence_id",
                            "source_message_id",
                            "source_revision",
                            "source_kind",
                            "quote",
                            "start_codepoint",
                            "end_codepoint",
                            "source_sha256",
                            "captured_at",
                        ),
                        "scope_id=? AND memory_id=?",
                        arrayOf(scopeId, memory.memoryId),
                        null,
                        null,
                        "captured_at,evidence_id",
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            put(
                                JSONObject()
                                    .put("evidence_id", cursor.getString(0))
                                    .put("message_id", cursor.getString(1))
                                    .put("source_revision", cursor.getInt(2))
                                    .put("source_kind", cursor.getString(3))
                                    .put("quote", cursor.getString(4))
                                    .put("start_codepoint", cursor.getInt(5))
                                    .put("end_codepoint", cursor.getInt(6))
                                    .put("source_sha256", cursor.getString(7))
                                    .put("captured_at", cursor.getLong(8)),
                            )
                        }
                    }
                },
            )
        db.insertWithOnConflict(
            "memory_history",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put("memory_id", memory.memoryId)
                put("version", memory.version)
                put("snapshot_json", snapshot.toString())
                put("expires_at", now() + config.historySeconds)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        db.delete(
            "memory_history",
            "scope_id=? AND memory_id=? AND version<=?",
            arrayOf(
                scopeId,
                memory.memoryId,
                (memory.version - config.historyVersions).toString(),
            ),
        )
    }

    private fun bumpScopeRevision(db: SQLiteDatabase, scopeId: String) {
        db.execSQL(
            "UPDATE scopes SET revision=revision+1 WHERE scope_id=?",
            arrayOf(scopeId),
        )
    }

    private fun revalidate(job: LeasedMemoryJob) {
        val db = database.readableDatabase
        val scope = db.query(
            "scopes",
            arrayOf("revision", "deletion_epoch", "persona_revision", "import_generation"),
            "scope_id=?",
            arrayOf(job.scopeId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            ScopeVersions(
                revision = cursor.getInt(0),
                deletionEpoch = cursor.getInt(1),
                personaRevision = cursor.getInt(2),
                importGeneration = cursor.getInt(3),
            )
        }
        if (
            scope.revision != job.envelope.baseScopeRevision ||
            scope.deletionEpoch != job.envelope.baseDeletionEpoch ||
            scope.personaRevision != job.envelope.personaRevision ||
            scope.importGeneration != job.envelope.importGeneration
        ) {
            throw MemoryV2Exception(
                if (scope.deletionEpoch != job.envelope.baseDeletionEpoch) {
                    MemoryV2ErrorCode.STALE_EPOCH
                } else {
                    MemoryV2ErrorCode.STALE_VERSION
                },
            )
        }
        if (job.kind == "summary") {
            val working = currentWorkingVersion(db, job)
            if (working != job.envelope.baseWorkingSummaryVersion) {
                throw MemoryV2Exception(MemoryV2ErrorCode.STALE_SUMMARY)
            }
        }
    }

    private fun revalidateEvidence(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
        evidence: ValidatedQuote,
    ): SourceEvidence {
        db.query(
            "messages",
            arrayOf(
                "revision",
                "role",
                "body",
                "content_sha256",
                "received_at",
                "state",
                "cache_expires_at",
            ),
            "scope_id=? AND message_id=?",
            arrayOf(job.scopeId, evidence.messageId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_UNAVAILABLE)
            }
            if (
                cursor.getString(5) != "live" ||
                cursor.isNull(2) ||
                cursor.getLong(6) <= now()
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_UNAVAILABLE)
            }
            if (
                cursor.getInt(0) != evidence.sourceRevision ||
                cursor.getString(3) != evidence.location.sourceSha256
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_CHANGED)
            }
            val located = MemoryV2QuoteLocator.locate(
                cursor.getString(2),
                evidence.quote,
                config.maxQuoteUtf8Bytes,
            )
            if (
                located.startCodepoint != evidence.location.startCodepoint ||
                located.endCodepoint != evidence.location.endCodepoint
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_CHANGED)
            }
            return SourceEvidence(
                messageId = evidence.messageId,
                revision = evidence.sourceRevision,
                role = cursor.getString(1),
                body = cursor.getString(2).orEmpty(),
                quote = evidence.quote,
                start = located.startCodepoint,
                end = located.endCodepoint,
                sha256 = located.sourceSha256,
                receivedAt = cursor.getLong(4),
            )
        }
    }

    private fun markJobDone(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
        kind: String,
    ) {
        job.envelope.newSourceIds.forEach { messageId ->
            val source = job.envelope.sources.first {
                it.messageId == messageId
            }
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
            val column = if (kind == "summary") "summary_state" else "extraction_state"
            db.execSQL(
                "UPDATE messages SET $column='done' WHERE scope_id=? AND message_id=?",
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
    }

    private fun renderWorkingSummary(
        items: List<ValidatedWorkingItem>,
    ): JSONObject {
        val root = JSONObject()
        listOf("goals", "constraints", "unresolved", "references").forEach {
            section ->
            root.put(
                section,
                JSONArray().apply {
                    items.filter { it.section == section }.forEach { item ->
                        put(
                            JSONObject()
                                .put(
                                    "text",
                                    MemoryV2MemoryWording.forCurrentPersona(item.text),
                                )
                                .put(
                                    "evidence",
                                    JSONArray().apply {
                                        item.evidence.forEach { evidence ->
                                            put(
                                                JSONObject()
                                                    .put("message_id", evidence.messageId)
                                                    .put(
                                                        "source_revision",
                                                        evidence.sourceRevision,
                                                    )
                                                    .put("quote", evidence.quote)
                                                    .put(
                                                        "start_codepoint",
                                                        evidence.location.startCodepoint,
                                                    )
                                                    .put(
                                                        "end_codepoint",
                                                        evidence.location.endCodepoint,
                                                    )
                                                    .put(
                                                        "source_sha256",
                                                        evidence.location.sourceSha256,
                                                    ),
                                            )
                                        }
                                    },
                                ),
                        )
                    }
                },
            )
        }
        return root
    }

    private fun currentWorkingVersion(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
    ): Int {
        return db.query(
            "sessions",
            arrayOf("working_version"),
            "scope_id=? AND session_id=?",
            arrayOf(job.scopeId, job.sessionId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            cursor.getInt(0)
        }
    }

    private fun currentWorkingSources(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
    ): List<String> {
        return db.query(
            "sessions",
            arrayOf("working_sources"),
            "scope_id=? AND session_id=?",
            arrayOf(job.scopeId, job.sessionId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            val values = runCatching {
                JSONArray(cursor.getString(0).orEmpty())
            }.getOrElse { JSONArray() }
            buildList {
                for (index in 0 until values.length()) {
                    values.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
        }
    }

    private fun currentJobState(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
    ): String {
        return db.query(
            "jobs",
            arrayOf("state"),
            "scope_id=? AND job_id=?",
            arrayOf(job.scopeId, job.jobId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.JOB_NOT_FOUND)
            }
            cursor.getString(0)
        }
    }

    private fun factKey(
        scopeId: String,
        subjectId: String,
        predicate: String,
        viewpointId: String?,
        valueJson: String,
        cardinality: String,
        eventId: String,
    ): String {
        val fields = buildList {
            add(subjectId)
            add(predicate)
            add(viewpointId.orEmpty())
            if (cardinality == "multi") add(valueJson)
            if (cardinality == "event") add(eventId)
        }
        return crypto.hmac("fact:$scopeId", fields.joinToString("\u0000"))
    }

    private fun ensureEntity(db: SQLiteDatabase, scopeId: String, entityId: String) {
        db.query(
            "entities",
            arrayOf("resolution"),
            "scope_id=? AND entity_id=?",
            arrayOf(scopeId, entityId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != "resolved") {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
        }
    }

    private fun entityKind(
        db: SQLiteDatabase,
        scopeId: String,
        entityId: String,
    ): String {
        return db.query(
            "entities",
            arrayOf("kind"),
            "scope_id=? AND entity_id=?",
            arrayOf(scopeId, entityId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0).orEmpty()
            } else {
                ""
            }
        }
    }

    private fun isProtectedPersonaCandidate(
        db: SQLiteDatabase,
        scopeId: String,
        candidate: ValidatedMemoryCandidate,
    ): Boolean {
        return MemoryV2ConflictConfirmationPolicy.isProtectedPersona(
            subjectKind = entityKind(db, scopeId, candidate.subjectId),
            predicate = candidate.predicate,
        )
    }

    private fun findMemory(
        db: SQLiteDatabase,
        scopeId: String,
        memoryId: String,
    ): ExistingMemory? = queryMemory(
        db,
        "scope_id=? AND memory_id=?",
        arrayOf(scopeId, memoryId),
    )

    private fun findByFact(
        db: SQLiteDatabase,
        scopeId: String,
        factKey: String,
    ): ExistingMemory? = queryMemory(
        db,
        "scope_id=? AND fact_key=?",
        arrayOf(scopeId, factKey),
    )

    private fun queryMemory(
        db: SQLiteDatabase,
        where: String,
        args: Array<String>,
    ): ExistingMemory? {
        db.query(
            "memories",
            arrayOf(
                "memory_id",
                "version",
                "fact_key",
                "tier",
                "subject_id",
                "viewpoint_id",
                "predicate",
                "text",
                "value_json",
                "epistemic",
                "lifecycle",
                "retracted_at",
                "commitment_state",
                "temporal_json",
                "retention_score",
                "source_anchor",
                "expires_at",
                "updated_at",
                "deletion_epoch",
            ),
            where,
            args,
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return ExistingMemory(
                memoryId = cursor.getString(0),
                version = cursor.getInt(1),
                factKey = cursor.getString(2),
                tier = cursor.getString(3),
                subjectId = cursor.getString(4),
                viewpointId = if (cursor.isNull(5)) null else cursor.getString(5),
                predicate = cursor.getString(6),
                text = cursor.getString(7),
                valueJson = cursor.getString(8),
                epistemic = cursor.getString(9),
                lifecycle = cursor.getString(10),
                retractedAt = if (cursor.isNull(11)) null else cursor.getLong(11),
                commitmentState = if (cursor.isNull(12)) null else cursor.getString(12),
                temporalJson = cursor.getString(13),
                retentionScore = cursor.getDouble(14),
                sourceAnchor = cursor.getLong(15),
                expiresAt = if (cursor.isNull(16)) null else cursor.getLong(16),
                updatedAt = cursor.getLong(17),
                deletionEpoch = cursor.getInt(18),
            )
        }
    }

    private fun assertTierCapacity(
        db: SQLiteDatabase,
        scopeId: String,
        tier: String,
        addBytes: Long,
    ) {
        val limit = config.tiers[tier] ?: return
        val usage = db.rawQuery(
            "SELECT COUNT(*),COALESCE(SUM(" +
                "length(CAST(text AS BLOB))+length(CAST(value_json AS BLOB))+" +
                "${config.rowOverheadBytes}),0) FROM memories " +
                "WHERE scope_id=? AND tier=?",
            arrayOf(scopeId, tier),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0) to cursor.getLong(1)
        }
        if (
            (limit.maxRecords != null && usage.first + 1 > limit.maxRecords) ||
            (limit.maxBytes != null && usage.second + addBytes > limit.maxBytes)
        ) {
            throw MemoryV2Exception(MemoryV2ErrorCode.RESOURCE_CAPACITY_EXHAUSTED)
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

    private fun emitOutbox(
        db: SQLiteDatabase,
        scopeId: String,
        memoryId: String,
        version: Int,
    ) {
        val epoch = queryLong(
            db,
            "SELECT deletion_epoch FROM scopes WHERE scope_id=?",
            arrayOf(scopeId),
        )
        db.insertWithOnConflict(
            "outbox",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put(
                    "event_id",
                    "index-${MemoryV2Crypto.sha256("$scopeId|$memoryId")}",
                )
                put("kind", "index_sync")
                put("target_id", memoryId)
                put("target_version", version)
                put("deletion_epoch", epoch)
                put("state", "pending")
                put("attempts", 0)
                putNull("lease_owner")
                putNull("lease_until")
                put("next_attempt_at", now())
                putNull("error_code")
                put("created_at", now())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun refreshLedgers(
        db: SQLiteDatabase,
        scopeId: String,
        vararg resources: String,
    ) {
        resources.forEach { resource ->
            val (expression, table) = when (resource) {
                "memories" ->
                    "length(CAST(text AS BLOB))+length(CAST(value_json AS BLOB))+" +
                        "length(CAST(temporal_json AS BLOB))" to "memories"
                "evidence" -> "length(CAST(quote AS BLOB))" to "evidence"
                "memory_conflicts" ->
                    "length(CAST(candidate_json AS BLOB))" to "memory_conflicts"
                "memory_relations" -> "0" to "memory_relations"
                "jobs" -> "length(CAST(envelope_json AS BLOB))" to "jobs"
                "messages" ->
                    "COALESCE(length(CAST(body AS BLOB)),0)+" +
                        "length(CAST(origin_json AS BLOB))" to "messages"
                "sessions" ->
                    "length(CAST(working_summary AS BLOB))+" +
                        "length(CAST(working_sources AS BLOB))" to "sessions"
                "dependencies", "coverage", "outbox" ->
                    "0" to resource
                else -> return@forEach
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

    private fun queryLong(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): Long {
        db.rawQuery(sql, args).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private data class ScopeVersions(
        val revision: Int,
        val deletionEpoch: Int,
        val personaRevision: Int,
        val importGeneration: Int,
    )

    private data class SourceEvidence(
        val messageId: String,
        val revision: Int,
        val role: String,
        val body: String,
        val quote: String,
        val start: Int,
        val end: Int,
        val sha256: String,
        val receivedAt: Long,
    )

    private data class ExistingMemory(
        val memoryId: String,
        val version: Int,
        val factKey: String,
        val tier: String,
        val subjectId: String,
        val viewpointId: String?,
        val predicate: String,
        val text: String,
        val valueJson: String,
        val epistemic: String,
        val lifecycle: String,
        val retractedAt: Long?,
        val commitmentState: String?,
        val temporalJson: String,
        val retentionScore: Double,
        val sourceAnchor: Long,
        val expiresAt: Long?,
        val updatedAt: Long,
        val deletionEpoch: Int,
    )
}
