package com.local.wxbotprobe.memory.v2

import android.content.ContentValues
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class MemoryV2ConflictRow(
    val scopeId: String,
    val conflictId: String,
    val memoryId: String,
    val targetVersion: Int,
    val operation: String,
    val existingText: String,
    val existingTier: String,
    val existingPredicate: String,
    val candidateText: String,
    val candidateValue: String,
    val candidateTier: String,
    val candidatePredicate: String,
    val candidateEpistemic: String,
    val candidateReason: String,
    val evidenceCount: Int,
    val evidenceQuotes: List<String>,
    val createdAt: Long,
) {
    val isA0Proposal: Boolean
        get() = MemoryV2ConflictPolicy.isA0Proposal(
            existingTier,
            candidateTier,
        )
}

object MemoryV2ConflictPolicy {
    fun isA0Proposal(
        existingTier: String,
        candidateTier: String,
    ): Boolean {
        return existingTier == "A0" || candidateTier == "A0"
    }

    fun canAcceptCandidate(
        existingTier: String,
        candidateTier: String,
        a0Confirmed: Boolean,
    ): Boolean {
        if (existingTier == "A0") {
            return a0Confirmed && candidateTier == "A0"
        }
        return candidateTier != "A0" || a0Confirmed
    }

    fun requiresManualReview(
        existingTier: String,
        candidateTier: String,
        subjectKind: String,
        predicate: String,
    ): Boolean {
        return !isProtectedPersona(subjectKind, predicate) &&
            (existingTier == "A0" || candidateTier == "A0")
    }

    fun isProtectedPersona(
        subjectKind: String,
        predicate: String,
    ): Boolean {
        return subjectKind == "agent" &&
            (
                predicate.startsWith("profile.") ||
                    predicate.startsWith("persona.") ||
                    predicate.startsWith("config.")
                )
    }
}

data class MemoryV2ConflictResolution(
    val status: String,
    val memoryId: String,
    val version: Int?,
)

data class MemoryV2A0ProposalRow(
    val scopeId: String,
    val memoryId: String,
    val version: Int,
    val text: String,
    val predicate: String,
    val epistemic: String,
    val evidenceCount: Int,
    val evidenceQuotes: List<String>,
    val createdAt: Long,
)

class MemoryV2ConflictService(
    private val context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val repository = MemoryV2Repository(context, database, now)
    private val commands = MemoryV2CommandService(context, database, now)
    private val config = MemoryV2ConfigLoader.load(context)
    private val predicates = MemoryV2PredicateRegistry.load(context)

    fun list(scopeId: String, limit: Int = 20): List<MemoryV2ConflictRow> {
        return database.readableDatabase.rawQuery(
            "SELECT c.conflict_id,c.memory_id,c.target_version,c.operation," +
                "c.candidate_json,c.created_at,m.text,m.tier,m.predicate " +
                "FROM memory_conflicts c JOIN memories m " +
                "ON m.scope_id=c.scope_id AND m.memory_id=c.memory_id " +
                "WHERE c.scope_id=? AND c.state='pending' " +
                "ORDER BY c.created_at,conflict_id LIMIT ?",
            arrayOf(scopeId, limit.coerceIn(1, 100).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val candidate = runCatching {
                        JSONObject(cursor.getString(4))
                    }.getOrElse { JSONObject() }
                    val evidence = candidate.optJSONArray("evidence")
                        ?: JSONArray()
                    val quotes = buildList {
                        for (index in 0 until evidence.length()) {
                            val quote = evidence.optJSONObject(index)
                                ?.optString("quote")
                                .orEmpty()
                                .trim()
                            if (quote.isNotEmpty()) {
                                add(quote)
                            }
                        }
                    }
                    add(
                        MemoryV2ConflictRow(
                            scopeId = scopeId,
                            conflictId = cursor.getString(0),
                            memoryId = cursor.getString(1),
                            targetVersion = cursor.getInt(2),
                            operation = cursor.getString(3),
                            existingText = cursor.getString(6).orEmpty(),
                            existingTier = cursor.getString(7).orEmpty(),
                            existingPredicate = cursor.getString(8).orEmpty(),
                            candidateText = candidate.optString("text"),
                            candidateValue = decodeValue(
                                candidate.optString("value_json"),
                            ),
                            candidateTier = candidate.optString("tier"),
                            candidatePredicate = candidate.optString("predicate"),
                            candidateEpistemic = candidate.optString("epistemic"),
                            candidateReason = candidate.optString("reason"),
                            evidenceCount = evidence.length(),
                            evidenceQuotes = quotes,
                            createdAt = cursor.getLong(5),
                        ),
                    )
                }
            }
        }
    }

    fun resolve(
        scopeId: String,
        conflictId: String,
        decision: String,
        a0Confirmed: Boolean = false,
    ): MemoryV2ConflictResolution {
        val normalized = decision.trim().uppercase()
        val conflict = readConflict(scopeId, conflictId)
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
        if (conflict.state != "pending") {
            return MemoryV2ConflictResolution(
                status = "unchanged",
                memoryId = conflict.memoryId,
                version = null,
            )
        }
        return when (normalized) {
            KEEP_EXISTING -> {
                markResolved(scopeId, conflictId, "resolved", "keep_existing")
                MemoryV2ConflictResolution("saved", conflict.memoryId, null)
            }
            DISMISS -> {
                markResolved(scopeId, conflictId, "dismissed", "dismissed")
                MemoryV2ConflictResolution("saved", conflict.memoryId, null)
            }
            ACCEPT_CANDIDATE -> acceptCandidate(
                scopeId = scopeId,
                conflict = conflict,
                a0Confirmed = a0Confirmed,
            )
            else -> throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT)
        }
    }

    fun listA0Proposals(
        scopeId: String,
        limit: Int = 20,
    ): List<MemoryV2A0ProposalRow> {
        val db = database.readableDatabase
        val proposals = db.rawQuery(
            "SELECT m.memory_id,m.version,m.text,m.predicate,m.epistemic," +
                "m.created_at," +
                "(SELECT COUNT(*) FROM evidence e WHERE e.scope_id=m.scope_id " +
                "AND e.memory_id=m.memory_id) " +
                "FROM memories m WHERE m.scope_id=? AND m.tier='A0' " +
                "AND m.lifecycle='needs_review' AND m.retracted_at IS NULL " +
                "ORDER BY m.created_at,m.memory_id LIMIT ?",
            arrayOf(scopeId, limit.coerceIn(1, 100).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MemoryV2A0ProposalRow(
                            scopeId = scopeId,
                            memoryId = cursor.getString(0).orEmpty(),
                            version = cursor.getInt(1),
                            text = cursor.getString(2).orEmpty(),
                            predicate = cursor.getString(3).orEmpty(),
                            epistemic = cursor.getString(4).orEmpty(),
                            evidenceCount = cursor.getInt(6),
                            evidenceQuotes = emptyList(),
                            createdAt = cursor.getLong(5),
                        ),
                    )
                }
            }
        }
        return proposals.map { proposal ->
            proposal.copy(
                evidenceQuotes = evidenceQuotes(
                    scopeId,
                    proposal.memoryId,
                ),
            )
        }
    }

    fun resolveA0Proposal(
        scopeId: String,
        memoryId: String,
        decision: String,
        a0Confirmed: Boolean = false,
    ): MemoryV2ConflictResolution {
        val normalized = decision.trim().uppercase()
        val proposal = readA0Proposal(scopeId, memoryId)
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
        return when (normalized) {
            APPROVE_A0 -> approveA0Proposal(
                proposal,
                a0Confirmed,
            )
            REJECT_A0 -> rejectA0Proposal(proposal)
            else -> throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT)
        }
    }

    private fun approveA0Proposal(
        proposal: A0Proposal,
        a0Confirmed: Boolean,
    ): MemoryV2ConflictResolution {
        if (!a0Confirmed) {
            throw MemoryV2Exception(MemoryV2ErrorCode.FIELD_NOT_EDITABLE)
        }
        if (!predicates.rule(proposal.predicate).allowA0) {
            throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT)
        }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            requireCurrentScopeEpoch(db, proposal)
            val updated = db.update(
                "memories",
                ContentValues().apply {
                    put("lifecycle", "active")
                    put("updated_at", now())
                },
                "scope_id=? AND memory_id=? AND version=? " +
                    "AND lifecycle='needs_review' AND retracted_at IS NULL",
                arrayOf(
                    proposal.scopeId,
                    proposal.memoryId,
                    proposal.version.toString(),
                ),
            )
            if (updated != 1) {
                throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
            }
            emitOutbox(
                db,
                proposal.scopeId,
                proposal.memoryId,
                proposal.version,
            )
            db.execSQL(
                "UPDATE scopes SET revision=revision+1 WHERE scope_id=?",
                arrayOf(proposal.scopeId),
            )
            appendAudit(
                db,
                proposal.scopeId,
                "approve-a0-${MemoryV2Crypto.sha256(
                    "${proposal.scopeId}|${proposal.memoryId}|${proposal.version}",
                )}",
                "a0_approved",
                proposal.memoryId,
                proposal.version,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return MemoryV2ConflictResolution(
            status = "saved",
            memoryId = proposal.memoryId,
            version = proposal.version,
        )
    }

    private fun rejectA0Proposal(
        proposal: A0Proposal,
    ): MemoryV2ConflictResolution {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            requireCurrentScopeEpoch(db, proposal)
            val updated = db.update(
                "memories",
                ContentValues().apply {
                    put("retracted_at", now())
                    put("fact_key", "retracted-a0-${proposal.memoryId}")
                    put("updated_at", now())
                },
                "scope_id=? AND memory_id=? AND version=? " +
                    "AND lifecycle='needs_review' AND retracted_at IS NULL",
                arrayOf(
                    proposal.scopeId,
                    proposal.memoryId,
                    proposal.version.toString(),
                ),
            )
            if (updated != 1) {
                throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
            }
            appendAudit(
                db,
                proposal.scopeId,
                "reject-a0-${MemoryV2Crypto.sha256(
                    "${proposal.scopeId}|${proposal.memoryId}|${proposal.version}",
                )}",
                "a0_rejected",
                proposal.memoryId,
                null,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return MemoryV2ConflictResolution(
            status = "saved",
            memoryId = proposal.memoryId,
            version = proposal.version,
        )
    }

    private fun readA0Proposal(
        scopeId: String,
        memoryId: String,
    ): A0Proposal? {
        return database.readableDatabase.query(
            "memories",
            arrayOf("version", "predicate", "deletion_epoch"),
            "scope_id=? AND memory_id=? AND tier='A0' " +
                "AND lifecycle='needs_review' AND retracted_at IS NULL",
            arrayOf(scopeId, memoryId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            A0Proposal(
                scopeId = scopeId,
                memoryId = memoryId,
                version = cursor.getInt(0),
                predicate = cursor.getString(1).orEmpty(),
                deletionEpoch = cursor.getInt(2),
            )
        }
    }

    private fun requireCurrentScopeEpoch(
        db: android.database.sqlite.SQLiteDatabase,
        proposal: A0Proposal,
    ) {
        val current = db.query(
            "scopes",
            arrayOf("state", "deletion_epoch"),
            "scope_id=?",
            arrayOf(proposal.scopeId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            cursor.getString(0).orEmpty() to cursor.getInt(1)
        }
        if (
            current.first != "active" ||
            current.second != proposal.deletionEpoch
        ) {
            throw MemoryV2Exception(MemoryV2ErrorCode.STALE_EPOCH)
        }
    }

    private fun evidenceQuotes(
        scopeId: String,
        memoryId: String,
    ): List<String> {
        return database.readableDatabase.query(
            "evidence",
            arrayOf("quote"),
            "scope_id=? AND memory_id=?",
            arrayOf(scopeId, memoryId),
            null,
            null,
            "captured_at,evidence_id",
            "8",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val quote = cursor.getString(0).orEmpty().trim()
                    if (quote.isNotEmpty()) add(quote)
                }
            }
        }
    }

    private fun emitOutbox(
        db: android.database.sqlite.SQLiteDatabase,
        scopeId: String,
        memoryId: String,
        version: Int,
    ) {
        val epoch = queryLong(
            db,
            "SELECT deletion_epoch FROM scopes WHERE scope_id=?",
            scopeId,
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
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun appendAudit(
        db: android.database.sqlite.SQLiteDatabase,
        scopeId: String,
        operationId: String,
        action: String,
        memoryId: String,
        version: Int?,
    ) {
        val epoch = queryLong(
            db,
            "SELECT deletion_epoch FROM scopes WHERE scope_id=?",
            scopeId,
        )
        db.insertOrThrow(
            "audit_events",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put("audit_id", java.util.UUID.randomUUID().toString())
                put("operation_id", operationId)
                put("action", action)
                put("target_id", memoryId)
                putNull("from_version")
                if (version == null) putNull("to_version") else put(
                    "to_version",
                    version,
                )
                put("result_code", "OK")
                put("deletion_epoch", epoch)
                put("created_at", now())
                put("expires_at", now() + config.historySeconds)
            },
        )
    }

    private fun acceptCandidate(
        scopeId: String,
        conflict: StoredConflict,
        a0Confirmed: Boolean,
    ): MemoryV2ConflictResolution {
        val candidate = JSONObject(conflict.candidateJson)
        if (
            !MemoryV2ConflictPolicy.canAcceptCandidate(
                existingTier = conflict.existingTier,
                candidateTier = candidate.optString("tier"),
                a0Confirmed = a0Confirmed,
            )
        ) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.FIELD_NOT_EDITABLE,
            )
        }
        val evidence = candidate.optJSONArray("evidence")
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_EVIDENCE)
        val commandEvidence = buildList {
            for (index in 0 until evidence.length()) {
                val item = evidence.optJSONObject(index) ?: continue
                add(
                    MemoryCommandEvidence(
                        messageId = item.optString("message_id"),
                        sourceRevision = item.optInt("source_revision"),
                        quote = item.optString("quote"),
                    ),
                )
            }
        }
        if (commandEvidence.isEmpty()) {
            throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_EVIDENCE)
        }
        val candidateTier = candidate.optString("tier", "A1")
        val scope = repository.scopeById(scopeId)
        val commandSeq = queryLong(
            "SELECT op_watermark FROM scopes WHERE scope_id=?",
            scopeId,
        ).toInt() + 1
        val result = commands.save(
            scope,
            SaveMemoryCommand(
                operationId = "resolve-conflict-${MemoryV2Crypto.sha256(conflict.id)}",
                commandSeq = commandSeq,
                tier = candidateTier,
                predicate = candidate.optString("predicate"),
                subjectId = candidate.optString("subject_id"),
                viewpointId = candidate.optString("viewpoint_id")
                    .takeIf { it.isNotBlank() && !candidate.isNull("viewpoint_id") },
                text = candidate.optString("text"),
                value = decodeValue(candidate.optString("value_json")),
                epistemic = candidate.optString("epistemic", "declared"),
                evidence = commandEvidence,
                memoryId = conflict.memoryId,
                expectedVersion = conflict.targetVersion,
                pinned = candidateTier == "A0" || queryLong(
                    "SELECT pinned FROM memories WHERE scope_id=? AND memory_id=?",
                    scopeId,
                    conflict.memoryId,
                ) == 1L,
                coreAuthorized = a0Confirmed &&
                    (conflict.existingTier == "A0" || candidateTier == "A0"),
                commitmentState = candidate.optString("commitment_state")
                    .takeIf {
                        it.isNotBlank() && !candidate.isNull("commitment_state")
                    },
                allowConflictResolution = true,
                temporalJson = candidate.optString(
                    "temporal_json",
                    SaveMemoryCommand.EMPTY_TEMPORAL,
                ),
            ),
        )
        markResolved(scopeId, conflict.id, "resolved", "accepted_candidate")
        return MemoryV2ConflictResolution(
            status = result.status,
            memoryId = result.memoryId ?: conflict.memoryId,
            version = result.version,
        )
    }

    private fun markResolved(
        scopeId: String,
        conflictId: String,
        state: String,
        resolution: String,
    ) {
        database.writableDatabase.update(
            "memory_conflicts",
            ContentValues().apply {
                put("state", state)
                put("resolution", resolution)
                put("resolved_at", now())
                put("updated_at", now())
            },
            "scope_id=? AND conflict_id=? AND state='pending'",
            arrayOf(scopeId, conflictId),
        )
    }

    private fun readConflict(
        scopeId: String,
        conflictId: String,
    ): StoredConflict? {
        return database.readableDatabase.rawQuery(
            "SELECT c.conflict_id,c.memory_id,c.target_version," +
                "c.candidate_json,c.state,m.tier " +
                "FROM memory_conflicts c JOIN memories m " +
                "ON m.scope_id=c.scope_id AND m.memory_id=c.memory_id " +
                "WHERE c.scope_id=? AND c.conflict_id=? LIMIT 1",
            arrayOf(scopeId, conflictId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            StoredConflict(
                id = cursor.getString(0),
                memoryId = cursor.getString(1),
                targetVersion = cursor.getInt(2),
                candidateJson = cursor.getString(3),
                state = cursor.getString(4),
                existingTier = cursor.getString(5).orEmpty(),
            )
        }
    }

    private fun queryLong(sql: String, vararg args: String): Long {
        return queryLong(database.readableDatabase, sql, *args)
    }

    private fun queryLong(
        db: android.database.sqlite.SQLiteDatabase,
        sql: String,
        vararg args: String,
    ): Long {
        return db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun decodeValue(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            JSONArray("[$raw]").getString(0)
        }.getOrDefault(raw)
    }

    private data class StoredConflict(
        val id: String,
        val memoryId: String,
        val targetVersion: Int,
        val candidateJson: String,
        val state: String,
        val existingTier: String,
    )

    private data class A0Proposal(
        val scopeId: String,
        val memoryId: String,
        val version: Int,
        val predicate: String,
        val deletionEpoch: Int,
    )

    companion object {
        const val KEEP_EXISTING = "KEEP_EXISTING"
        const val ACCEPT_CANDIDATE = "ACCEPT_CANDIDATE"
        const val DISMISS = "DISMISS"
        const val APPROVE_A0 = "APPROVE_A0"
        const val REJECT_A0 = "REJECT_A0"
    }
}
