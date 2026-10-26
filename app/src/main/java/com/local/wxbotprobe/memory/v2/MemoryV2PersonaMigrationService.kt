package com.local.wxbotprobe.memory.v2

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class MemoryV2MigrationRelationImpact(
    val relationId: String,
    val relationType: String,
    val direction: String,
    val counterpartMemoryId: String,
    val counterpartTier: String,
    val targetCounterpartMemoryId: String?,
) {
    val copyable: Boolean
        get() = targetCounterpartMemoryId != null
}

data class MemoryV2MigrationPreview(
    val sourceScopeId: String,
    val targetScopeId: String,
    val contactId: String,
    val sourcePersonaId: String,
    val targetPersonaId: String,
    val sourceMemoryId: String,
    val targetMemoryId: String,
    val tier: String,
    val sourceVersion: Int,
    val text: String,
    val evidenceCount: Int,
    val targetFactKey: String,
    val targetFactConflictMemoryId: String?,
    val targetFactConflictText: String?,
    val sourcePendingConflictCount: Int,
    val targetActiveMemoryCount: Int,
    val relations: List<MemoryV2MigrationRelationImpact>,
    val alreadyMigrated: Boolean,
    val blockCode: String?,
    val requiresA0Confirmation: Boolean,
) {
    val copyableRelationCount: Int
        get() = relations.count { it.copyable }

    val omittedRelationCount: Int
        get() = relations.count { !it.copyable }
}

data class MemoryV2MigrationResult(
    val status: String,
    val sourceScopeId: String,
    val targetScopeId: String,
    val sourceMemoryId: String,
    val targetMemoryId: String,
    val targetVersion: Int,
    val targetFactKey: String,
    val copiedRelationCount: Int,
    val omittedRelationCount: Int,
)

data class MemoryV2MigrationRecord(
    val sourceScopeId: String,
    val targetScopeId: String,
    val contactId: String,
    val sourcePersonaId: String,
    val targetPersonaId: String,
    val sourceMemoryId: String,
    val targetMemoryId: String,
    val targetVersion: Int,
    val state: String,
    val relationSummaryJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val committedAt: Long?,
    val rolledBackAt: Long?,
)

data class MemoryV2MigrationRollbackResult(
    val status: String,
    val sourceScopeId: String,
    val targetScopeId: String,
    val sourceMemoryId: String,
    val targetMemoryId: String,
    val targetVersion: Int,
    val auditState: String,
)

object MemoryV2MigrationPolicy {
    fun validateScopePair(
        source: MemoryScopeKey,
        target: MemoryScopeKey,
    ): String? {
        return when {
            source.contactId != target.contactId -> "CROSS_CONTACT_FORBIDDEN"
            source.ownerId != target.ownerId ||
                source.agentId != target.agentId ||
                source.realmId != target.realmId -> "SCOPE_IDENTITY_MISMATCH"
            source.personaId == target.personaId -> "SAME_PERSONA"
            else -> null
        }
    }

    fun targetMemoryId(
        sourceScopeId: String,
        sourceMemoryId: String,
        targetScopeId: String,
    ): String {
        return "pm-mem-" + MemoryV2Crypto.sha256(
            "$sourceScopeId|$sourceMemoryId|$targetScopeId",
        ).take(32)
    }

    fun canRollback(state: String): Boolean {
        return state == "committed" || state == "rolled_back"
    }
}

class MemoryV2PersonaMigrationService(
    private val context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val repository = MemoryV2Repository(context, database, now)
    private val commands = MemoryV2CommandService(context, database, now)
    private val config = MemoryV2ConfigLoader.load(context)

    fun preview(
        sourceScopeId: String,
        targetScopeId: String,
        memoryId: String,
    ): MemoryV2MigrationPreview {
        val source = requireScope(sourceScopeId)
        val target = requireScope(targetScopeId)
        validateScopes(source, target)
        val memory = readSourceMemory(sourceScopeId, memoryId)
        val targetMemoryId = targetMemoryId(source, target, memoryId)
        val targetFactKey = commands.targetFactKey(
            targetScopeId,
            commandFor(
                memory = memory,
                targetMemoryId = targetMemoryId,
                evidence = emptyList(),
                a0Confirmed = false,
            ),
        )
        val targetConflict = database.readableDatabase.query(
            "memories",
            arrayOf("memory_id", "text"),
            "scope_id=? AND fact_key=?",
            arrayOf(targetScopeId, targetFactKey),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                cursor.getString(0) to cursor.getString(1)
            }
        }
        val sourceConflicts = count(
            "SELECT COUNT(*) FROM memory_conflicts " +
                "WHERE scope_id=? AND memory_id=? AND state='pending'",
            sourceScopeId,
            memoryId,
        )
        val relations = relationImpacts(sourceScopeId, targetScopeId, memoryId)
        val audit = readAudit(sourceScopeId, targetScopeId, memoryId)
        val targetById = readMemory(targetScopeId, targetMemoryId)
        val alreadyMigrated = audit?.state == "committed"
        val blockCode = when {
            alreadyMigrated -> null
            audit?.state == "rolled_back" -> "MIGRATION_ROLLED_BACK"
            sourceConflicts > 0 -> "SOURCE_CONFLICT_NEEDS_REVIEW"
            targetById != null &&
                (targetById.retracted || targetById.factKey != targetFactKey) ->
                "TARGET_MEMORY_CONFLICT"
            targetConflict != null && targetConflict.first != targetMemoryId ->
                "TARGET_FACT_CONFLICT"
            memory.evidence.isEmpty() -> "SOURCE_EVIDENCE_MISSING"
            memory.tier == "A2" &&
                (memory.expiresAt == null || memory.expiresAt <= now()) ->
                "MEMORY_EXPIRED"
            else -> null
        }
        return MemoryV2MigrationPreview(
            sourceScopeId = sourceScopeId,
            targetScopeId = targetScopeId,
            contactId = source.key.contactId,
            sourcePersonaId = source.key.personaId,
            targetPersonaId = target.key.personaId,
            sourceMemoryId = memoryId,
            targetMemoryId = targetMemoryId,
            tier = memory.tier,
            sourceVersion = memory.version,
            text = memory.text,
            evidenceCount = memory.evidence.size,
            targetFactKey = targetFactKey,
            targetFactConflictMemoryId = targetConflict?.first,
            targetFactConflictText = targetConflict?.second,
            sourcePendingConflictCount = sourceConflicts,
            targetActiveMemoryCount = repository.health(targetScopeId).activeMemories,
            relations = relations,
            alreadyMigrated = alreadyMigrated,
            blockCode = blockCode,
            requiresA0Confirmation = memory.tier == "A0",
        )
    }

    fun migrate(
        sourceScopeId: String,
        targetScopeId: String,
        memoryId: String,
        a0Confirmed: Boolean = false,
        acceptRelationOmission: Boolean = false,
    ): MemoryV2MigrationResult {
        val preview = preview(sourceScopeId, targetScopeId, memoryId)
        if (preview.alreadyMigrated) {
            val audit = readAudit(sourceScopeId, targetScopeId, memoryId)
                ?: error("committed migration audit missing")
            return MemoryV2MigrationResult(
                status = "unchanged",
                sourceScopeId = sourceScopeId,
                targetScopeId = targetScopeId,
                sourceMemoryId = memoryId,
                targetMemoryId = audit.targetMemoryId,
                targetVersion = audit.targetVersion,
                targetFactKey = audit.targetFactKey.orEmpty(),
                copiedRelationCount = 0,
                omittedRelationCount = 0,
            )
        }
        preview.blockCode?.let {
            throw MemoryV2Exception(
                migrationErrorCode(it),
                it,
            )
        }
        if (preview.requiresA0Confirmation && !a0Confirmed) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.FIELD_NOT_EDITABLE,
                "A0_CONFIRMATION_REQUIRED",
            )
        }
        if (preview.omittedRelationCount > 0 && !acceptRelationOmission) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.INVALID_EVIDENCE,
                "RELATION_CLOSURE_REQUIRED",
            )
        }
        val source = requireScope(sourceScopeId)
        val target = requireScope(targetScopeId)
        val memory = readSourceMemory(sourceScopeId, memoryId)
        ensureAudit(preview)
        val evidence = try {
            prepareTargetEvidence(source, target, memory, preview)
        } catch (error: Throwable) {
            markAuditFailed(sourceScopeId, targetScopeId, memoryId, error)
            throw error
        }
        val existingTarget = readMemory(targetScopeId, preview.targetMemoryId)
        val saveResult = if (existingTarget == null) {
            try {
                commands.save(
                    scope = target,
                    command = commandFor(
                        memory = memory,
                        targetMemoryId = preview.targetMemoryId,
                        evidence = evidence,
                        a0Confirmed = a0Confirmed,
                    ).copy(
                        operationId = migrationOperationId(preview.targetMemoryId),
                        commandSeq = nextCommandSeq(targetScopeId),
                    ),
                )
            } catch (error: Throwable) {
                markAuditFailed(
                    sourceScopeId,
                    targetScopeId,
                    memoryId,
                    error,
                )
                throw error
            }
        } else {
            MemoryCommandResult(
                status = "unchanged",
                operationId = migrationOperationId(preview.targetMemoryId),
                memoryId = preview.targetMemoryId,
                version = existingTarget.version,
            )
        }
        val relationSummary = JSONObject()
        val copied = JSONArray()
        val omitted = JSONArray()
        var relationFailure: Throwable? = null
        database.writableDatabase.beginTransaction()
        try {
            preview.relations.forEach { impact ->
                if (impact.copyable) {
                    val targetCounterpartId =
                        impact.targetCounterpartMemoryId.orEmpty()
                    val targetVersion = readMemory(
                        targetScopeId,
                        targetCounterpartId,
                    )?.version ?: 1
                    val targetSource = if (impact.direction == "outgoing") {
                        preview.targetMemoryId
                    } else {
                        targetCounterpartId
                    }
                    val targetTarget = if (impact.direction == "outgoing") {
                        targetCounterpartId
                    } else {
                        preview.targetMemoryId
                    }
                    val relationId = "pm-rel-" + MemoryV2Crypto.sha256(
                        "$targetScopeId|$targetSource|$targetTarget|" +
                            impact.relationType,
                    ).take(32)
                    database.writableDatabase.insertWithOnConflict(
                        "memory_relations",
                        null,
                        ContentValues().apply {
                            put("scope_id", targetScopeId)
                            put("relation_id", relationId)
                            put("source_memory_id", targetSource)
                            put("target_memory_id", targetTarget)
                            put("relation_type", impact.relationType)
                            put("target_version", targetVersion)
                            put("created_at", now())
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                    copied.put(
                        JSONObject()
                            .put("source_relation_id", impact.relationId)
                            .put("target_relation_id", relationId),
                    )
                } else {
                    omitted.put(
                        JSONObject()
                            .put("source_relation_id", impact.relationId)
                            .put("counterpart_memory_id", impact.counterpartMemoryId)
                            .put("counterpart_tier", impact.counterpartTier)
                            .put("reason", "COUNTERPART_NOT_MIGRATED"),
                    )
                }
            }
            relationSummary
                .put("copied", copied)
                .put("omitted", omitted)
            database.writableDatabase.update(
                "memories",
                ContentValues().apply {
                    put("source_anchor", memory.sourceAnchor)
                    put("retention_score", memory.retentionScore)
                    if (memory.expiresAt == null) {
                        putNull("expires_at")
                    } else {
                        put("expires_at", memory.expiresAt)
                    }
                    put("updated_at", now())
                },
                "scope_id=? AND memory_id=?",
                arrayOf(targetScopeId, preview.targetMemoryId),
            )
            database.writableDatabase.update(
                "persona_migration_audit",
                ContentValues().apply {
                    put("state", "committed")
                    put("target_version", saveResult.version ?: 1)
                    put("target_fact_key", preview.targetFactKey)
                    put("relation_summary_json", relationSummary.toString())
                    putNull("last_error")
                    put("updated_at", now())
                    put("committed_at", now())
                },
                "source_scope_id=? AND source_memory_id=? AND target_scope_id=?",
                arrayOf(sourceScopeId, memoryId, targetScopeId),
            )
            refreshLedger(
                database.writableDatabase,
                targetScopeId,
                "relations",
            )
            database.writableDatabase.setTransactionSuccessful()
        } catch (error: Throwable) {
            relationFailure = error
        } finally {
            database.writableDatabase.endTransaction()
        }
        relationFailure?.let { error ->
            markAuditFailed(sourceScopeId, targetScopeId, memoryId, error)
            throw error
        }
        return MemoryV2MigrationResult(
            status = if (saveResult.status == "unchanged") {
                "unchanged"
            } else {
                "saved"
            },
            sourceScopeId = sourceScopeId,
            targetScopeId = targetScopeId,
            sourceMemoryId = memoryId,
            targetMemoryId = preview.targetMemoryId,
            targetVersion = saveResult.version ?: 1,
            targetFactKey = preview.targetFactKey,
            copiedRelationCount = copied.length(),
            omittedRelationCount = omitted.length(),
        )
    }

    fun migrations(
        sourceScopeId: String,
        memoryId: String,
    ): List<MemoryV2MigrationRecord> {
        return database.readableDatabase.query(
            "persona_migration_audit",
            arrayOf(
                "source_scope_id",
                "target_scope_id",
                "contact_id",
                "source_persona_id",
                "target_persona_id",
                "source_memory_id",
                "target_memory_id",
                "target_version",
                "state",
                "relation_summary_json",
                "created_at",
                "updated_at",
                "committed_at",
                "rolled_back_at",
            ),
            "source_scope_id=? AND source_memory_id=?",
            arrayOf(sourceScopeId, memoryId),
            null,
            null,
            "updated_at DESC, target_scope_id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MemoryV2MigrationRecord(
                            sourceScopeId = cursor.getString(0),
                            targetScopeId = cursor.getString(1),
                            contactId = cursor.getString(2),
                            sourcePersonaId = cursor.getString(3),
                            targetPersonaId = cursor.getString(4),
                            sourceMemoryId = cursor.getString(5),
                            targetMemoryId = cursor.getString(6),
                            targetVersion = cursor.getInt(7),
                            state = cursor.getString(8),
                            relationSummaryJson = cursor.getString(9),
                            createdAt = cursor.getLong(10),
                            updatedAt = cursor.getLong(11),
                            committedAt = if (cursor.isNull(12)) {
                                null
                            } else {
                                cursor.getLong(12)
                            },
                            rolledBackAt = if (cursor.isNull(13)) {
                                null
                            } else {
                                cursor.getLong(13)
                            },
                        ),
                    )
                }
            }
        }
    }

    fun rollback(
        sourceScopeId: String,
        targetScopeId: String,
        memoryId: String,
        confirmed: Boolean = false,
    ): MemoryV2MigrationRollbackResult {
        if (!confirmed) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.INVALID_ARGUMENT,
                "ROLLBACK_CONFIRMATION_REQUIRED",
            )
        }
        val source = requireScope(sourceScopeId)
        val target = requireScope(targetScopeId)
        validateScopes(source, target)
        if (readMemory(sourceScopeId, memoryId) == null) {
            throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
        }
        val audit = readAudit(sourceScopeId, targetScopeId, memoryId)
            ?: throw MemoryV2Exception(
                MemoryV2ErrorCode.MEMORY_NOT_FOUND,
                "MIGRATION_AUDIT_NOT_FOUND",
            )
        if (!MemoryV2MigrationPolicy.canRollback(audit.state)) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.OPERATION_EXPIRED,
                "MIGRATION_NOT_COMMITTED",
            )
        }
        if (audit.state == "rolled_back") {
                val targetMemory = readMemory(targetScopeId, audit.targetMemoryId)
                if (targetMemory == null || targetMemory.retracted) {
                    return MemoryV2MigrationRollbackResult(
                        status = "unchanged",
                        sourceScopeId = sourceScopeId,
                        targetScopeId = targetScopeId,
                        sourceMemoryId = memoryId,
                        targetMemoryId = audit.targetMemoryId,
                        targetVersion = targetMemory?.version ?: audit.targetVersion,
                        auditState = "rolled_back",
                    )
                }
        }
        val targetMemory = readMemory(targetScopeId, audit.targetMemoryId)
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
        val result = if (targetMemory.retracted) {
            MemoryCommandResult(
                status = "unchanged",
                operationId = rollbackOperationId(audit.targetMemoryId),
                memoryId = audit.targetMemoryId,
                version = targetMemory.version,
            )
        } else {
            commands.retract(
                scope = target,
                command = RetractMemoryCommand(
                    operationId = rollbackOperationId(audit.targetMemoryId),
                    commandSeq = nextCommandSeq(targetScopeId),
                    memoryId = audit.targetMemoryId,
                    expectedVersion = targetMemory.version,
                ),
            )
        }
        database.writableDatabase.update(
            "persona_migration_audit",
            ContentValues().apply {
                put("state", "rolled_back")
                put("target_version", result.version ?: targetMemory.version)
                put("rolled_back_at", now())
                put("updated_at", now())
                putNull("last_error")
            },
            "source_scope_id=? AND source_memory_id=? AND target_scope_id=?",
            arrayOf(sourceScopeId, memoryId, targetScopeId),
        )
        return MemoryV2MigrationRollbackResult(
            status = "rolled_back",
            sourceScopeId = sourceScopeId,
            targetScopeId = targetScopeId,
            sourceMemoryId = memoryId,
            targetMemoryId = audit.targetMemoryId,
            targetVersion = result.version ?: targetMemory.version,
            auditState = "rolled_back",
        )
    }

    private fun prepareTargetEvidence(
        source: TrustedScopeContext,
        target: TrustedScopeContext,
        memory: SourceMemory,
        preview: MemoryV2MigrationPreview,
    ): List<MemoryCommandEvidence> {
        val sessionId = "pm-session-" + MemoryV2Crypto.sha256(
            "${source.scopeId}|${target.scopeId}|${memory.memoryId}",
        ).take(32)
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            ensureTargetEntities(db, source.scopeId, target.scopeId, memory)
            val existingSession = db.query(
                "sessions",
                arrayOf("next_seq"),
                "scope_id=? AND session_id=?",
                arrayOf(target.scopeId, sessionId),
                null,
                null,
                null,
                "1",
            ).use { it.moveToFirst() }
            val origins = memory.evidence.map { sourceEvidence ->
                JSONObject()
                    .put("format", "persona-migration-v1")
                    .put("source_scope_id", source.scopeId)
                    .put("source_memory_id", memory.memoryId)
                    .put("source_evidence_id", sourceEvidence.evidenceId)
                    .put("source_message_id", sourceEvidence.sourceMessageId)
                    .toString()
            }
            val missingMessageIndexes = memory.evidence.mapIndexedNotNull {
                    index, sourceEvidence ->
                val messageId = "pm-msg-" + MemoryV2Crypto.sha256(
                    "${preview.targetMemoryId}|${sourceEvidence.evidenceId}",
                ).take(32)
                val exists = db.query(
                    "messages",
                    arrayOf("message_id"),
                    "scope_id=? AND message_id=?",
                    arrayOf(target.scopeId, messageId),
                    null,
                    null,
                    null,
                    "1",
                ).use { it.moveToFirst() }
                if (exists) null else index
            }
            assertResourceCapacity(
                db = db,
                scopeId = target.scopeId,
                resource = "messages",
                addRecords = missingMessageIndexes.size,
                addBytes = missingMessageIndexes.sumOf { index ->
                    memory.evidence[index].quote
                        .toByteArray(Charsets.UTF_8).size +
                        origins[index].toByteArray(Charsets.UTF_8).size +
                        config.rowOverheadBytes
                },
            )
            if (!existingSession) {
                assertResourceCapacity(
                    db = db,
                    scopeId = target.scopeId,
                    resource = "sessions",
                    addRecords = 1,
                    addBytes = 2 + config.rowOverheadBytes,
                )
            }
            if (!existingSession) {
                db.insertOrThrow(
                    "sessions",
                    null,
                    ContentValues().apply {
                        put("scope_id", target.scopeId)
                        put("session_id", sessionId)
                        put("next_seq", memory.evidence.size + 1)
                        put("working_summary", "{}")
                        put("working_version", 0)
                        put("working_sources", "[]")
                        put("deny_before_seq", 0)
                        put("state", "closed")
                        put("created_at", now())
                        put("updated_at", now())
                    },
                )
            }
            val evidence = memory.evidence.mapIndexed { index, sourceEvidence ->
                val messageId = "pm-msg-" + MemoryV2Crypto.sha256(
                    "${preview.targetMemoryId}|${sourceEvidence.evidenceId}",
                ).take(32)
                val body = sourceEvidence.quote
                val bodyHash = MemoryV2Crypto.sha256(body)
                db.insertWithOnConflict(
                    "messages",
                    null,
                    ContentValues().apply {
                        put("scope_id", target.scopeId)
                        put("message_id", messageId)
                        put("session_id", sessionId)
                        put(
                            "client_message_id",
                            "persona-migration:${preview.targetMemoryId}:" +
                                sourceEvidence.evidenceId,
                        )
                        put("seq", index + 1)
                        put("revision", 1)
                        put("role", sourceEvidence.sourceKind)
                        put("origin_json", origins[index])
                        put("body", body)
                        put("content_sha256", bodyHash)
                        put("occurred_at", now())
                        put("received_at", now())
                        put("timezone", config.timezone)
                        put("cache_expires_at", now() + config.sourceCacheSeconds)
                        putNull("host_expires_at")
                        put("state", "live")
                        put("extraction_state", "skipped")
                        put("summary_state", "done")
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                MemoryCommandEvidence(
                    messageId = messageId,
                    sourceRevision = 1,
                    quote = body,
                )
            }
            refreshLedger(db, target.scopeId, "messages")
            refreshLedger(db, target.scopeId, "sessions")
            db.setTransactionSuccessful()
            return evidence
        } finally {
            db.endTransaction()
        }
    }

    private fun ensureTargetEntities(
        db: SQLiteDatabase,
        sourceScopeId: String,
        targetScopeId: String,
        memory: SourceMemory,
    ) {
        val ids = listOfNotNull(memory.subjectId, memory.viewpointId)
        ids.forEach { entityId ->
            val targetExists = db.query(
                "entities",
                arrayOf("entity_id"),
                "scope_id=? AND entity_id=?",
                arrayOf(targetScopeId, entityId),
                null,
                null,
                null,
                "1",
            ).use { it.moveToFirst() }
            if (targetExists) {
                return@forEach
            }
            val sourceEntity = db.query(
                "entities",
                arrayOf("kind", "display_name", "version", "resolution", "created_at"),
                "scope_id=? AND entity_id=?",
                arrayOf(sourceScopeId, entityId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    listOf(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getString(3),
                        cursor.getLong(4),
                    )
                }
            } ?: throw MemoryV2Exception(
                MemoryV2ErrorCode.INVALID_EVIDENCE,
                "entity missing: $entityId",
            )
            db.insertOrThrow(
                "entities",
                null,
                ContentValues().apply {
                    put("scope_id", targetScopeId)
                    put("entity_id", entityId)
                    put("kind", sourceEntity[0].toString())
                    put("display_name", sourceEntity[1].toString())
                    put("version", (sourceEntity[2] as Number).toInt())
                    put("resolution", sourceEntity[3].toString())
                    put("created_at", (sourceEntity[4] as Number).toLong())
                },
            )
        }
    }

    private fun ensureAudit(preview: MemoryV2MigrationPreview) {
        database.writableDatabase.insertWithOnConflict(
            "persona_migration_audit",
            null,
            ContentValues().apply {
                put("source_scope_id", preview.sourceScopeId)
                put("target_scope_id", preview.targetScopeId)
                put("contact_id", preview.contactId)
                put("source_persona_id", preview.sourcePersonaId)
                put("target_persona_id", preview.targetPersonaId)
                put("source_memory_id", preview.sourceMemoryId)
                put("target_memory_id", preview.targetMemoryId)
                put("source_version", preview.sourceVersion)
                put("target_version", 1)
                putNull("target_fact_key")
                put("state", "pending")
                put("relation_summary_json", "{}")
                putNull("last_error")
                put("created_at", now())
                put("updated_at", now())
                putNull("committed_at")
                putNull("rolled_back_at")
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun markAuditFailed(
        sourceScopeId: String,
        targetScopeId: String,
        memoryId: String,
        error: Throwable,
    ) {
        database.writableDatabase.update(
            "persona_migration_audit",
            ContentValues().apply {
                put("state", "failed")
                put("last_error", error.message.orEmpty())
                put("updated_at", now())
            },
            "source_scope_id=? AND source_memory_id=? AND target_scope_id=?",
            arrayOf(sourceScopeId, memoryId, targetScopeId),
        )
    }

    private fun commandFor(
        memory: SourceMemory,
        targetMemoryId: String,
        evidence: List<MemoryCommandEvidence>,
        a0Confirmed: Boolean,
    ): SaveMemoryCommand {
        return SaveMemoryCommand(
            operationId = migrationOperationId(targetMemoryId),
            commandSeq = 1,
            tier = memory.tier,
            predicate = memory.predicate,
            subjectId = memory.subjectId,
            viewpointId = memory.viewpointId,
            text = memory.text,
            value = decodeValue(memory.valueJson, memory.text),
            epistemic = memory.epistemic,
            evidence = evidence,
            memoryId = targetMemoryId,
            eventId = memory.memoryId,
            pinned = memory.pinned,
            commitmentState = memory.commitmentState,
            coreAuthorized = memory.tier == "A0" && a0Confirmed,
            personaEditor = memory.predicate.startsWith("persona."),
            temporalJson = memory.temporalJson,
        )
    }

    private fun readSourceMemory(scopeId: String, memoryId: String): SourceMemory {
        val db = database.readableDatabase
        val memory = db.query(
            "memories",
            arrayOf(
                "version",
                "tier",
                "subject_id",
                "viewpoint_id",
                "predicate",
                "text",
                "value_json",
                "epistemic",
                "lifecycle",
                "pinned",
                "commitment_state",
                "temporal_json",
                "retention_score",
                "source_anchor",
                "expires_at",
            ),
            "scope_id=? AND memory_id=? AND retracted_at IS NULL",
            arrayOf(scopeId, memoryId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
            }
            if (cursor.getString(8) != "active") {
                throw MemoryV2Exception(
                    MemoryV2ErrorCode.INVALID_ARGUMENT,
                    "memory lifecycle=${cursor.getString(8)}",
                )
            }
            SourceMemory(
                memoryId = memoryId,
                version = cursor.getInt(0),
                tier = cursor.getString(1),
                subjectId = cursor.getString(2),
                viewpointId = if (cursor.isNull(3)) null else cursor.getString(3),
                predicate = cursor.getString(4),
                text = cursor.getString(5),
                valueJson = cursor.getString(6),
                epistemic = cursor.getString(7),
                pinned = cursor.getInt(9) == 1,
                commitmentState = if (cursor.isNull(10)) null else cursor.getString(10),
                temporalJson = cursor.getString(11),
                retentionScore = cursor.getDouble(12),
                sourceAnchor = cursor.getLong(13),
                expiresAt = if (cursor.isNull(14)) null else cursor.getLong(14),
                evidence = emptyList(),
            )
        }
        val evidence = db.query(
            "evidence",
            arrayOf(
                "evidence_id",
                "source_message_id",
                "source_revision",
                "source_kind",
                "quote",
                "source_sha256",
            ),
            "scope_id=? AND memory_id=?",
            arrayOf(scopeId, memoryId),
            null,
            null,
            "captured_at,evidence_id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SourceEvidence(
                            evidenceId = cursor.getString(0),
                            sourceMessageId = cursor.getString(1),
                            sourceRevision = cursor.getInt(2),
                            sourceKind = cursor.getString(3),
                            quote = cursor.getString(4),
                            sourceSha256 = cursor.getString(5),
                        ),
                    )
                }
            }
        }
        return memory.copy(evidence = evidence)
    }

    private fun readMemory(scopeId: String, memoryId: String): TargetMemory? {
        return database.readableDatabase.query(
            "memories",
            arrayOf("version", "fact_key", "retracted_at"),
            "scope_id=? AND memory_id=?",
            arrayOf(scopeId, memoryId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                TargetMemory(
                    version = cursor.getInt(0),
                    factKey = cursor.getString(1),
                    retracted = !cursor.isNull(2),
                )
            }
        }
    }

    private fun relationImpacts(
        sourceScopeId: String,
        targetScopeId: String,
        memoryId: String,
    ): List<MemoryV2MigrationRelationImpact> {
        return database.readableDatabase.query(
            "memory_relations",
            arrayOf(
                "relation_id",
                "source_memory_id",
                "target_memory_id",
                "relation_type",
            ),
            "scope_id=? AND (source_memory_id=? OR target_memory_id=?)",
            arrayOf(sourceScopeId, memoryId, memoryId),
            null,
            null,
            "relation_id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val sourceMemoryId = cursor.getString(1)
                    val targetMemoryId = cursor.getString(2)
                    val direction = if (sourceMemoryId == memoryId) {
                        "outgoing"
                    } else {
                        "incoming"
                    }
                    val counterpartId = if (direction == "outgoing") {
                        targetMemoryId
                    } else {
                        sourceMemoryId
                    }
                    val counterpartTier = database.readableDatabase.query(
                        "memories",
                        arrayOf("tier"),
                        "scope_id=? AND memory_id=?",
                        arrayOf(sourceScopeId, counterpartId),
                        null,
                        null,
                        null,
                        "1",
                    ).use { tierCursor ->
                        if (tierCursor.moveToFirst()) tierCursor.getString(0) else ""
                    }
                    val mapped = mappedTargetMemory(
                        sourceScopeId,
                        targetScopeId,
                        counterpartId,
                    )
                    add(
                        MemoryV2MigrationRelationImpact(
                            relationId = cursor.getString(0),
                            relationType = cursor.getString(3),
                            direction = direction,
                            counterpartMemoryId = counterpartId,
                            counterpartTier = counterpartTier,
                            targetCounterpartMemoryId = mapped,
                        ),
                    )
                }
            }
        }
    }

    private fun mappedTargetMemory(
        sourceScopeId: String,
        targetScopeId: String,
        sourceMemoryId: String,
    ): String? {
        return database.readableDatabase.query(
            "persona_migration_audit a JOIN memories m " +
                "ON m.scope_id=a.target_scope_id " +
                "AND m.memory_id=a.target_memory_id",
            arrayOf("a.target_memory_id"),
            "a.source_scope_id=? AND a.target_scope_id=? " +
                "AND a.source_memory_id=? AND a.state='committed' " +
                "AND m.retracted_at IS NULL",
            arrayOf(sourceScopeId, targetScopeId, sourceMemoryId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun readAudit(
        sourceScopeId: String,
        targetScopeId: String,
        memoryId: String,
    ): MigrationAudit? {
        return database.readableDatabase.query(
            "persona_migration_audit",
            arrayOf(
                "state",
                "target_memory_id",
                "target_version",
                "target_fact_key",
            ),
            "source_scope_id=? AND source_memory_id=? AND target_scope_id=?",
            arrayOf(sourceScopeId, memoryId, targetScopeId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                MigrationAudit(
                    state = cursor.getString(0),
                    targetMemoryId = cursor.getString(1),
                    targetVersion = cursor.getInt(2),
                    targetFactKey = if (cursor.isNull(3)) null else cursor.getString(3),
                )
            }
        }
    }

    private fun requireScope(scopeId: String): TrustedScopeContext {
        return database.readableDatabase.query(
            "scopes",
            arrayOf(
                "owner_id",
                "contact_id",
                "agent_id",
                "persona_id",
                "realm_id",
                "persona_revision",
                "revision",
                "deletion_epoch",
                "import_generation",
                "retention_policy",
                "state",
                "restore_guard_id",
            ),
            "scope_id=?",
            arrayOf(scopeId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            val scope = TrustedScopeContext(
                scopeId = scopeId,
                key = MemoryScopeKey(
                    ownerId = cursor.getString(0),
                    contactId = cursor.getString(1),
                    agentId = cursor.getString(2),
                    personaId = cursor.getString(3),
                    realmId = cursor.getString(4),
                ),
                personaRevision = cursor.getInt(5),
                revision = cursor.getInt(6),
                deletionEpoch = cursor.getInt(7),
                importGeneration = cursor.getInt(8),
                retentionPolicy = cursor.getString(9),
                state = cursor.getString(10),
                restoreGuardId = cursor.getString(11),
            )
            if (scope.state != "active") {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            scope
        }
    }

    private fun validateScopes(
        source: TrustedScopeContext,
        target: TrustedScopeContext,
    ) {
        when (
            MemoryV2MigrationPolicy.validateScopePair(
                source.key,
                target.key,
            )
        ) {
            "CROSS_CONTACT_FORBIDDEN" -> {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.UNAUTHORIZED_SCOPE,
                "cross-contact migration is forbidden",
            )
            }
            "SCOPE_IDENTITY_MISMATCH" -> throw MemoryV2Exception(
                MemoryV2ErrorCode.UNAUTHORIZED_SCOPE,
            )
            "SAME_PERSONA" -> throw MemoryV2Exception(
                MemoryV2ErrorCode.INVALID_ARGUMENT,
                "source and target persona must differ",
            )
        }
    }

    private fun targetMemoryId(
        source: TrustedScopeContext,
        target: TrustedScopeContext,
        memoryId: String,
    ): String {
        return MemoryV2MigrationPolicy.targetMemoryId(
            source.scopeId,
            memoryId,
            target.scopeId,
        )
    }

    private fun migrationOperationId(targetMemoryId: String): String {
        return "persona-migration-$targetMemoryId"
    }

    private fun rollbackOperationId(targetMemoryId: String): String {
        return "persona-migration-rollback-$targetMemoryId"
    }

    private fun nextCommandSeq(scopeId: String): Int {
        return database.readableDatabase.rawQuery(
            "SELECT op_watermark+1 FROM scopes WHERE scope_id=?",
            arrayOf(scopeId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            cursor.getInt(0)
        }
    }

    private fun decodeValue(raw: String, fallback: String): String {
        return runCatching {
            JSONArray("[$raw]").getString(0)
        }.getOrDefault(fallback)
    }

    private fun assertResourceCapacity(
        db: SQLiteDatabase,
        scopeId: String,
        resource: String,
        addRecords: Int,
        addBytes: Long,
    ) {
        val limit = config.resources[resource] ?: return
        val usage = resourceUsage(db, scopeId, resource)
        if (
            (limit.maxRecords > 0 &&
                usage.records + addRecords > limit.maxRecords) ||
            (limit.maxBytes > 0 && usage.bytes + addBytes > limit.maxBytes)
        ) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.RESOURCE_CAPACITY_EXHAUSTED,
                resource,
            )
        }
    }

    private fun refreshLedger(
        db: SQLiteDatabase,
        scopeId: String,
        resource: String,
    ) {
        val usage = resourceUsage(db, scopeId, resource)
        db.insertWithOnConflict(
            "capacity_ledger",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put("resource", resource)
                put("used_bytes", usage.bytes)
                put("used_records", usage.records)
                put("reservation_bytes", 0)
                put("accounting_version", 1)
                put("last_tx_id", UUID.randomUUID().toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun resourceUsage(
        db: SQLiteDatabase,
        scopeId: String,
        resource: String,
    ): ResourceUsage {
        val sql = when (resource) {
            "messages" ->
                "SELECT COUNT(*),COALESCE(SUM(" +
                    "COALESCE(length(CAST(body AS BLOB)),0)+" +
                    "length(CAST(origin_json AS BLOB))+" +
                    "${config.rowOverheadBytes}),0) FROM messages " +
                    "WHERE scope_id=?"
            "sessions" ->
                "SELECT COUNT(*),COALESCE(SUM(" +
                    "length(CAST(working_summary AS BLOB))+" +
                    "length(CAST(working_sources AS BLOB))+" +
                    "${config.rowOverheadBytes}),0) FROM sessions " +
                    "WHERE scope_id=?"
            "relations" ->
                "SELECT COUNT(*),0 FROM memory_relations WHERE scope_id=?"
            else -> return ResourceUsage(0, 0)
        }
        return db.rawQuery(sql, arrayOf(scopeId)).use { cursor ->
            if (!cursor.moveToFirst()) {
                ResourceUsage(0, 0)
            } else {
                ResourceUsage(cursor.getInt(0), cursor.getLong(1))
            }
        }
    }

    private fun count(sql: String, vararg args: String): Int {
        return database.readableDatabase.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun migrationErrorCode(blockCode: String): MemoryV2ErrorCode {
        return when (blockCode) {
            "SOURCE_CONFLICT_NEEDS_REVIEW",
            "TARGET_FACT_CONFLICT",
            "TARGET_MEMORY_CONFLICT",
            -> MemoryV2ErrorCode.CONFLICT_NEEDS_REVIEW
            "SOURCE_EVIDENCE_MISSING" -> MemoryV2ErrorCode.INVALID_EVIDENCE
            "MEMORY_EXPIRED" -> MemoryV2ErrorCode.MEMORY_EXPIRED
            "MIGRATION_ROLLED_BACK" -> MemoryV2ErrorCode.OPERATION_EXPIRED
            else -> MemoryV2ErrorCode.INVALID_ARGUMENT
        }
    }

    private data class SourceEvidence(
        val evidenceId: String,
        val sourceMessageId: String,
        val sourceRevision: Int,
        val sourceKind: String,
        val quote: String,
        val sourceSha256: String,
    )

    private data class SourceMemory(
        val memoryId: String,
        val version: Int,
        val tier: String,
        val subjectId: String,
        val viewpointId: String?,
        val predicate: String,
        val text: String,
        val valueJson: String,
        val epistemic: String,
        val pinned: Boolean,
        val commitmentState: String?,
        val temporalJson: String,
        val retentionScore: Double,
        val sourceAnchor: Long,
        val expiresAt: Long?,
        val evidence: List<SourceEvidence>,
    )

    private data class MigrationAudit(
        val state: String,
        val targetMemoryId: String,
        val targetVersion: Int,
        val targetFactKey: String?,
    )

    private data class TargetMemory(
        val version: Int,
        val factKey: String,
        val retracted: Boolean,
    )

    private data class ResourceUsage(
        val records: Int,
        val bytes: Long,
    )
}
