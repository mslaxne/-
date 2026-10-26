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
        return existingTier != "A0" ||
            a0Confirmed && candidateTier == "A0"
    }
}

data class MemoryV2ConflictResolution(
    val status: String,
    val memoryId: String,
    val version: Int?,
)

class MemoryV2ConflictService(
    private val context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val repository = MemoryV2Repository(context, database, now)
    private val commands = MemoryV2CommandService(context, database, now)

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
                tier = candidate.optString("tier", "A1"),
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
                pinned = queryLong(
                    "SELECT pinned FROM memories WHERE scope_id=? AND memory_id=?",
                    scopeId,
                    conflict.memoryId,
                ) == 1L,
                coreAuthorized = conflict.existingTier == "A0" && a0Confirmed,
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
        return database.readableDatabase.rawQuery(sql, args).use { cursor ->
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

    companion object {
        const val KEEP_EXISTING = "KEEP_EXISTING"
        const val ACCEPT_CANDIDATE = "ACCEPT_CANDIDATE"
        const val DISMISS = "DISMISS"
    }
}
