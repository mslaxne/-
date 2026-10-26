package com.local.wxbotprobe.memory.v2

import android.content.ContentValues
import android.content.Context
import java.util.UUID
import org.json.JSONArray

class MemoryV2ManualMemoryService(
    private val context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val admin = MemoryV2AdminStore(context, database)
    private val repository = MemoryV2Repository(context, database)
    private val commands = MemoryV2CommandService(context, database, now)

    fun add(
        scopeId: String,
        tier: String,
        text: String,
    ): MemoryCommandResult {
        val scope = admin.scope(scopeId)
        val cleanText = requireText(text)
        val subjectId = subjectEntityId(scopeId)
        val predicate = when (tier) {
            "A0" -> "relationship.role"
            "A1" -> "experience.event"
            "A2" -> "event.summary"
            else -> throw MemoryV2Exception(
                MemoryV2ErrorCode.INVALID_ARGUMENT,
            )
        }
        val evidence = appendHostEvidence(scope, cleanText)
        val eventId = if (tier == "A0") null else UUID.randomUUID().toString()
        val commandSeq = nextCommandSeq(scopeId)
        return commands.save(
            scope,
            SaveMemoryCommand(
                operationId = "manual-add-${UUID.randomUUID()}",
                commandSeq = commandSeq,
                tier = tier,
                predicate = predicate,
                subjectId = subjectId,
                viewpointId = null,
                text = cleanText,
                value = cleanText,
                epistemic = if (tier == "A0") "configured" else "declared",
                evidence = listOf(evidence),
                eventId = eventId,
                pinned = tier == "A0",
                coreAuthorized = tier == "A0",
            ),
        )
    }

    fun editText(
        scopeId: String,
        memoryId: String,
        expectedVersion: Int,
        text: String,
    ): MemoryCommandResult {
        val scope = admin.scope(scopeId)
        val cleanText = requireText(text)
        val existing = readEditable(scopeId, memoryId)
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
        if (existing.version != expectedVersion) {
            throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
        }
        val evidence = appendHostEvidence(scope, cleanText)
        val commandSeq = nextCommandSeq(scopeId)
        return commands.save(
            scope,
            SaveMemoryCommand(
                operationId = "manual-edit-${UUID.randomUUID()}",
                commandSeq = commandSeq,
                tier = existing.tier,
                predicate = existing.predicate,
                subjectId = existing.subjectId,
                viewpointId = existing.viewpointId,
                text = cleanText,
                value = cleanText,
                epistemic = existing.epistemic,
                evidence = listOf(evidence),
                memoryId = memoryId,
                expectedVersion = expectedVersion,
                eventId = memoryId,
                pinned = existing.pinned,
                commitmentState = existing.commitmentState,
                coreAuthorized = existing.tier == "A0",
                personaEditor = existing.predicate == "persona.description",
                allowConflictResolution = true,
                temporalJson = existing.temporalJson,
            ),
        )
    }

    fun retract(
        scopeId: String,
        memoryId: String,
        expectedVersion: Int,
    ): MemoryCommandResult {
        return commands.retract(
            admin.scope(scopeId),
            RetractMemoryCommand(
                operationId = "manual-retract-${UUID.randomUUID()}",
                commandSeq = nextCommandSeq(scopeId),
                memoryId = memoryId,
                expectedVersion = expectedVersion,
            ),
        )
    }

    fun clearTier(
        scopeId: String,
        tier: String,
    ): MemoryCommandResult {
        return commands.retractTier(
            admin.scope(scopeId),
            RetractTierCommand(
                operationId = "manual-clear-$tier-${UUID.randomUUID()}",
                commandSeq = nextCommandSeq(scopeId),
                tier = tier,
            ),
        )
    }

    private fun appendHostEvidence(
        scope: TrustedScopeContext,
        text: String,
    ): MemoryCommandEvidence {
        val timestamp = now()
        val appended = repository.appendMessage(
            scope,
            AppendMessageRequest(
                clientMessageId = "manual-memory-${UUID.randomUUID()}",
                sessionId = null,
                role = "config",
                text = text,
                occurredAt = timestamp,
                receivedAt = timestamp,
                timezone = MemoryV2ConfigLoader.load(context).timezone,
            ),
        )
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "messages",
                ContentValues().apply {
                    put("extraction_state", "skipped")
                    put("summary_state", "done")
                },
                "scope_id=? AND message_id=?",
                arrayOf(scope.scopeId, appended.messageId),
            )
            db.update(
                "sessions",
                ContentValues().apply {
                    put("state", "closed")
                    put("updated_at", timestamp)
                },
                "scope_id=? AND session_id=?",
                arrayOf(scope.scopeId, appended.sessionId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return MemoryCommandEvidence(
            messageId = appended.messageId,
            sourceRevision = 1,
            quote = text,
        )
    }

    private fun subjectEntityId(scopeId: String): String {
        return database.readableDatabase.query(
            "entities",
            arrayOf("entity_id"),
            "scope_id=? AND kind='user'",
            arrayOf(scopeId),
            null,
            null,
            "created_at",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(
                    MemoryV2ErrorCode.UNAUTHORIZED_SCOPE,
                )
            }
            cursor.getString(0).orEmpty()
        }
    }

    private fun nextCommandSeq(scopeId: String): Int {
        return database.readableDatabase.rawQuery(
            "SELECT op_watermark+1 FROM scopes WHERE scope_id=?",
            arrayOf(scopeId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(
                    MemoryV2ErrorCode.UNAUTHORIZED_SCOPE,
                )
            }
            cursor.getInt(0)
        }
    }

    private fun readEditable(
        scopeId: String,
        memoryId: String,
    ): EditableMemory? {
        return database.readableDatabase.query(
            "memories",
            arrayOf(
                "version",
                "tier",
                "predicate",
                "subject_id",
                "viewpoint_id",
                "value_json",
                "epistemic",
                "commitment_state",
                "temporal_json",
                "pinned",
            ),
            "scope_id=? AND memory_id=? AND retracted_at IS NULL",
            arrayOf(scopeId, memoryId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return EditableMemory(
                version = cursor.getInt(0),
                tier = cursor.getString(1).orEmpty(),
                predicate = cursor.getString(2).orEmpty(),
                subjectId = cursor.getString(3).orEmpty(),
                viewpointId = if (cursor.isNull(4)) {
                    null
                } else {
                    cursor.getString(4)
                },
                value = decodeStringValue(cursor.getString(5).orEmpty()),
                epistemic = cursor.getString(6).orEmpty(),
                commitmentState = if (cursor.isNull(7)) {
                    null
                } else {
                    cursor.getString(7)
                },
                temporalJson = cursor.getString(8).orEmpty(),
                pinned = cursor.getInt(9) == 1,
            )
        }
    }

    private fun decodeStringValue(raw: String): String {
        return runCatching {
            JSONArray("[$raw]").getString(0)
        }.getOrDefault(raw)
    }

    private fun requireText(text: String): String {
        val clean = text.trim()
        if (clean.isEmpty()) {
            throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT)
        }
        if (clean.toByteArray(Charsets.UTF_8).size > 2_000) {
            throw MemoryV2Exception(MemoryV2ErrorCode.ITEM_TOO_LARGE)
        }
        return clean
    }

    private data class EditableMemory(
        val version: Int,
        val tier: String,
        val predicate: String,
        val subjectId: String,
        val viewpointId: String?,
        val value: String,
        val epistemic: String,
        val commitmentState: String?,
        val temporalJson: String,
        val pinned: Boolean,
    )
}
