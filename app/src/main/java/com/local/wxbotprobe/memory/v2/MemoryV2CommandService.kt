package com.local.wxbotprobe.memory.v2

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class MemoryV2CommandService(
    context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val config = MemoryV2ConfigLoader.load(context)
    private val predicates = MemoryV2PredicateRegistry.load(context)
    private val crypto = MemoryV2Crypto()

    fun targetFactKey(
        scopeId: String,
        command: SaveMemoryCommand,
    ): String {
        val rule = predicates.rule(command.predicate)
        val normalisedValue = predicates.normalisedValue(rule, command.value)
        val eventId = command.eventId
            ?: command.memoryId
            ?: UUID.randomUUID().toString()
        return factKey(
            scopeId = scopeId,
            subjectId = command.subjectId,
            predicate = command.predicate,
            viewpointId = command.viewpointId,
            value = normalisedValue,
            cardinality = rule.cardinality,
            eventId = eventId,
        )
    }

    fun save(
        scope: TrustedScopeContext,
        command: SaveMemoryCommand,
    ): MemoryCommandResult {
        require(command.operationId.isNotBlank()) { "operationId is required" }
        val digest = requestDigest("save", scope.scopeId, command)
        return transaction(scope, command.operationId, command.commandSeq, digest) { db ->
            val currentScope = requireScope(db, scope.scopeId)
            val rule = predicates.rule(command.predicate)
            if (rule.requiresViewpoint && command.viewpointId.isNullOrBlank()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.VIEWPOINT_REQUIRED)
            }
            if (rule.requiredPermission == "persona_editor" && !command.personaEditor) {
                throw MemoryV2Exception(MemoryV2ErrorCode.FIELD_NOT_EDITABLE)
            }
            if (
                command.tier == "A0" &&
                (
                    !command.coreAuthorized ||
                        !rule.allowA0 ||
                        command.epistemic == "inferred"
                    )
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.FIELD_NOT_EDITABLE)
            }
            if (
                command.epistemic == "inferred" &&
                command.predicate.startsWith("profile.")
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_EVIDENCE)
            }
            val normalisedValue = predicates.normalisedValue(rule, command.value)
            val itemBytes = (
                command.text + normalisedValue + command.temporalJson
                ).toByteArray(Charsets.UTF_8).size
            if (itemBytes > config.maxItemUtf8Bytes) {
                throw MemoryV2Exception(MemoryV2ErrorCode.ITEM_TOO_LARGE)
            }
            JSONObject(command.temporalJson)
            if (command.evidence.isEmpty()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_EVIDENCE)
            }
            val located = command.evidence.map { evidence ->
                locateEvidence(
                    db = db,
                    scopeId = scope.scopeId,
                    evidence = evidence,
                    predicate = command.predicate,
                )
            }
            ensureEntity(db, scope.scopeId, command.subjectId)
            command.viewpointId?.takeIf { it.isNotBlank() }?.let {
                ensureEntity(db, scope.scopeId, it)
            }
            val eventId = command.eventId
                ?: command.memoryId
                ?: UUID.randomUUID().toString()
            val factKey = factKey(
                scopeId = scope.scopeId,
                subjectId = command.subjectId,
                predicate = command.predicate,
                viewpointId = command.viewpointId,
                value = normalisedValue,
                cardinality = rule.cardinality,
                eventId = eventId,
            )
            if (
                !command.relearn &&
                fieldBlocked(db, scope.scopeId, command.predicate)
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.FIELD_BLOCKED)
            }
            if (
                !command.relearn &&
                factBlocked(db, scope.scopeId, factKey)
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.RELEARN_REQUIRED)
            }
            val existingById = command.memoryId?.let {
                findMemory(db, scope.scopeId, it)
            }
            val existingByFact = findMemoryByFact(db, scope.scopeId, factKey)
            val existing = existingById ?: existingByFact
            if (existing == null && command.expectedVersion != null) {
                throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
            }
            if (existing != null) {
                if (command.expectedVersion != existing.version) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
                }
                if (existing.valueJson != jsonString(normalisedValue)) {
                    if (!command.allowConflictResolution) {
                        throw MemoryV2Exception(
                            MemoryV2ErrorCode.CONFLICT_NEEDS_REVIEW,
                        )
                    }
                    if (
                        existing.subjectId != command.subjectId ||
                        existing.predicate != command.predicate ||
                        existing.viewpointId != command.viewpointId
                    ) {
                        throw MemoryV2Exception(MemoryV2ErrorCode.SCHEMA_INVALID)
                    }
                }
            }
            val memoryId = existing?.memoryId
                ?: command.memoryId
                ?: UUID.randomUUID().toString()
            val version = (existing?.version ?: 0) + 1
            val anchor = located.maxOf { it.source.receivedAt }
            val ttl = config.tiers[command.tier]?.ttlSeconds
            val expiresAt = if (command.tier == "A2") {
                val calculated = anchor + (ttl
                    ?: throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT))
                minOf(calculated, existing?.expiresAt ?: Long.MAX_VALUE)
            } else {
                null
            }
            if (expiresAt != null && expiresAt <= now()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_EXPIRED)
            }
            assertTierCapacity(
                db,
                scope.scopeId,
                command.tier,
                itemBytes + config.rowOverheadBytes,
                replacing = existing != null,
            )
            if (existing != null) {
                writeHistory(db, scope.scopeId, existing)
            }
            val memoryValues = ContentValues().apply {
                    put("scope_id", scope.scopeId)
                    put("memory_id", memoryId)
                    put("version", version)
                    put("tier", command.tier)
                    put("fact_key", factKey)
                    put("subject_id", command.subjectId)
                    if (command.viewpointId.isNullOrBlank()) {
                        putNull("viewpoint_id")
                    } else {
                        put("viewpoint_id", command.viewpointId)
                    }
                    put("predicate", command.predicate)
                    put("text", command.text)
                    put("value_json", jsonString(normalisedValue))
                    put("epistemic", command.epistemic)
                    put(
                        "lifecycle",
                        if (command.commitmentState == "proposed") {
                            "needs_review"
                        } else {
                            "active"
                        },
                    )
                    putNull("retracted_at")
                    put("pinned", if (command.pinned) 1 else 0)
                    if (command.commitmentState == null) {
                        putNull("commitment_state")
                    } else {
                        put("commitment_state", command.commitmentState)
                    }
                    put("temporal_json", command.temporalJson)
                    put("retention_score", 0.5)
                    put("source_anchor", anchor)
                    if (expiresAt == null) putNull("expires_at") else put(
                        "expires_at",
                        expiresAt,
                    )
                    put("created_at", existing?.createdAt ?: now())
                    put("updated_at", now())
                    put("deletion_epoch", currentScope.deletionEpoch)
                }
            if (existing == null) {
                db.insertOrThrow("memories", null, memoryValues)
            } else {
                val updated = db.update(
                    "memories",
                    memoryValues,
                    "scope_id=? AND memory_id=? AND version=?",
                    arrayOf(
                        scope.scopeId,
                        existing.memoryId,
                        existing.version.toString(),
                    ),
                )
                if (updated != 1) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
                }
                db.delete(
                    "evidence",
                    "scope_id=? AND memory_id=?",
                    arrayOf(scope.scopeId, existing.memoryId),
                )
            }
            located.forEach { evidence ->
                val evidenceId = MemoryV2Crypto.sha256(
                    listOf(
                        scope.scopeId,
                        memoryId,
                        evidence.source.messageId,
                        evidence.source.revision,
                        evidence.location.startCodepoint,
                        evidence.location.endCodepoint,
                    ).joinToString("|"),
                )
                db.insertWithOnConflict(
                    "evidence",
                    null,
                    ContentValues().apply {
                        put("scope_id", scope.scopeId)
                        put("evidence_id", evidenceId)
                        put("memory_id", memoryId)
                        put("source_message_id", evidence.source.messageId)
                        put("source_revision", evidence.source.revision)
                        put("source_kind", evidence.source.role)
                        put("quote", evidence.evidence.quote)
                        put("start_codepoint", evidence.location.startCodepoint)
                        put("end_codepoint", evidence.location.endCodepoint)
                        put("source_sha256", evidence.location.sourceSha256)
                        put("captured_at", now())
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                insertDependency(
                    db,
                    scope.scopeId,
                    "message",
                    evidence.source.messageId,
                    "memory",
                    memoryId,
                    evidence.source.revision,
                )
            }
            emitOutbox(db, scope.scopeId, memoryId, version)
            db.update(
                "scopes",
                ContentValues().apply {
                    put("revision", currentScope.revision + 1)
                },
                "scope_id=?",
                arrayOf(scope.scopeId),
            )
            refreshLedgers(
                db,
                scope.scopeId,
                "memories",
                "evidence",
                "dependencies",
                "outbox",
            )
            val result = MemoryCommandResult(
                status = "saved",
                operationId = command.operationId,
                memoryId = memoryId,
                version = version,
                deletionEpoch = currentScope.deletionEpoch,
            )
            result
        }
    }

    fun pin(
        scope: TrustedScopeContext,
        command: PinMemoryCommand,
    ): MemoryCommandResult {
        val digest = requestDigest("pin", scope.scopeId, command)
        return transaction(scope, command.operationId, command.commandSeq, digest) { db ->
            val currentScope = requireScope(db, scope.scopeId)
            val memory = findMemory(db, scope.scopeId, command.memoryId)
                ?: throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
            if (memory.version != command.expectedVersion) {
                throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
            }
            writeHistory(db, scope.scopeId, memory)
            db.update(
                "memories",
                ContentValues().apply {
                    put("pinned", if (command.pinned) 1 else 0)
                    put("version", memory.version + 1)
                    put("updated_at", now())
                },
                "scope_id=? AND memory_id=?",
                arrayOf(scope.scopeId, command.memoryId),
            )
            emitOutbox(
                db,
                scope.scopeId,
                command.memoryId,
                memory.version + 1,
            )
            db.update(
                "scopes",
                ContentValues().apply {
                    put("revision", currentScope.revision + 1)
                },
                "scope_id=?",
                arrayOf(scope.scopeId),
            )
            refreshLedgers(db, scope.scopeId, "memories", "outbox")
            MemoryCommandResult(
                status = "saved",
                operationId = command.operationId,
                memoryId = command.memoryId,
                version = memory.version + 1,
                deletionEpoch = currentScope.deletionEpoch,
            )
        }
    }

    fun retract(
        scope: TrustedScopeContext,
        command: RetractMemoryCommand,
    ): MemoryCommandResult {
        val digest = requestDigest("retract", scope.scopeId, command)
        return transaction(scope, command.operationId, command.commandSeq, digest) { db ->
            val currentScope = requireScope(db, scope.scopeId)
            val memory = findMemory(db, scope.scopeId, command.memoryId)
                ?: throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
            if (memory.version != command.expectedVersion) {
                throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
            }
            retractRows(db, scope.scopeId, listOf(memory))
            db.update(
                "scopes",
                ContentValues().apply {
                    put("revision", currentScope.revision + 1)
                },
                "scope_id=?",
                arrayOf(scope.scopeId),
            )
            refreshLedgers(db, scope.scopeId, "memories", "outbox")
            MemoryCommandResult(
                status = "saved",
                operationId = command.operationId,
                memoryId = memory.memoryId,
                version = memory.version + 1,
                deletionEpoch = currentScope.deletionEpoch,
                removedMemoryIds = listOf(memory.memoryId),
            )
        }
    }

    fun retractTier(
        scope: TrustedScopeContext,
        command: RetractTierCommand,
    ): MemoryCommandResult {
        require(command.tier in setOf("A0", "A1", "A2")) {
            "unsupported memory tier"
        }
        val digest = requestDigest("retract-tier", scope.scopeId, command)
        return transaction(scope, command.operationId, command.commandSeq, digest) { db ->
            val currentScope = requireScope(db, scope.scopeId)
            val memories = queryStrings(
                db,
                "SELECT memory_id FROM memories WHERE scope_id=? AND tier=? " +
                    "AND retracted_at IS NULL ORDER BY memory_id",
                arrayOf(scope.scopeId, command.tier),
            ).mapNotNull { memoryId ->
                findMemory(db, scope.scopeId, memoryId)
            }
            retractRows(db, scope.scopeId, memories)
            if (memories.isNotEmpty()) {
                db.update(
                    "scopes",
                    ContentValues().apply {
                        put("revision", currentScope.revision + 1)
                    },
                    "scope_id=?",
                    arrayOf(scope.scopeId),
                )
                refreshLedgers(db, scope.scopeId, "memories", "outbox")
            }
            MemoryCommandResult(
                status = "saved",
                operationId = command.operationId,
                deletionEpoch = currentScope.deletionEpoch,
                removedMemoryIds = memories.map { it.memoryId },
            )
        }
    }

    private fun retractRows(
        db: SQLiteDatabase,
        scopeId: String,
        memories: List<StoredMemory>,
    ) {
        memories.forEach { memory ->
            writeHistory(db, scopeId, memory)
            val updated = db.update(
                "memories",
                ContentValues().apply {
                    put("version", memory.version + 1)
                    put("retracted_at", now())
                    put("updated_at", now())
                },
                "scope_id=? AND memory_id=? AND version=?",
                arrayOf(
                    scopeId,
                    memory.memoryId,
                    memory.version.toString(),
                ),
            )
            if (updated != 1) {
                throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
            }
            emitOutbox(db, scopeId, memory.memoryId, memory.version + 1)
        }
    }

    fun forget(
        scope: TrustedScopeContext,
        command: ForgetMemoryCommand,
    ): MemoryCommandResult {
        val digest = requestDigest("forget", scope.scopeId, command)
        return transaction(scope, command.operationId, command.commandSeq, digest) { db ->
            val currentScope = requireScope(db, scope.scopeId)
            val memory = command.memoryId?.let {
                findMemory(db, scope.scopeId, it)
            } ?: if (command.memoryId == null) {
                null
            } else {
                throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
            }
            val sourceIds = command.sourceMessageIds.toMutableSet()
            if (memory != null) {
                sourceIds += evidenceSourceIds(db, scope.scopeId, memory.memoryId)
            }
            if (memory == null && sourceIds.isEmpty() && command.denyField == null) {
                throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT)
            }
            val memoryIds = linkedSetOf<String>()
            memory?.let { memoryIds += it.memoryId }
            sourceIds.forEach { sourceId ->
                queryStrings(
                    db,
                    "SELECT memory_id FROM evidence WHERE scope_id=? " +
                        "AND source_message_id=?",
                    arrayOf(scope.scopeId, sourceId),
                ).forEach(memoryIds::add)
            }
            val epoch = currentScope.deletionEpoch + 1
            db.update(
                "scopes",
                ContentValues().apply {
                    put("deletion_epoch", epoch)
                    put("revision", currentScope.revision + 1)
                },
                "scope_id=?",
                arrayOf(scope.scopeId),
            )
            val cutoff = JSONObject()
            querySessions(db, scope.scopeId).forEach { (sessionId, nextSeq) ->
                cutoff.put(sessionId, nextSeq - 1)
            }
            if (memory != null) {
                putTombstone(
                    db,
                    scope.scopeId,
                    "fact",
                    memory.factKey,
                    epoch,
                    cutoff,
                    command.operationId,
                    "old_sources",
                )
            }
            sourceIds.forEach { sourceId ->
                putTombstone(
                    db,
                    scope.scopeId,
                    "message",
                    sourceId,
                    epoch,
                    cutoff,
                    command.operationId,
                    "old_sources",
                )
            }
            command.denyField?.let { field ->
                putTombstone(
                    db,
                    scope.scopeId,
                    "field",
                    field,
                    epoch,
                    cutoff,
                    command.operationId,
                    "deny_field",
                )
            }
            memoryIds.forEach { memoryId ->
                db.delete(
                    "memory_history",
                    "scope_id=? AND memory_id=?",
                    arrayOf(scope.scopeId, memoryId),
                )
                db.delete(
                    "evidence",
                    "scope_id=? AND memory_id=?",
                    arrayOf(scope.scopeId, memoryId),
                )
                db.delete(
                    "memories",
                    "scope_id=? AND memory_id=?",
                    arrayOf(scope.scopeId, memoryId),
                )
                db.delete(
                    "dependencies",
                    "scope_id=? AND child_kind='memory' AND child_id=?",
                    arrayOf(scope.scopeId, memoryId),
                )
            }
            sourceIds.forEach { sourceId ->
                db.execSQL(
                    "UPDATE messages SET body=NULL,content_sha256=''," +
                        "state='forgotten',revision=revision+1," +
                        "extraction_state='gap',summary_state='gap' " +
                        "WHERE scope_id=? AND message_id=?",
                    arrayOf(scope.scopeId, sourceId),
                )
                db.delete(
                    "aliases",
                    "scope_id=? AND source_message_id=?",
                    arrayOf(scope.scopeId, sourceId),
                )
            }
            db.update(
                "jobs",
                ContentValues().apply {
                    put("state", "cancelled")
                    put("envelope_json", "{}")
                    put("error_code", "SOURCE_FORGOTTEN")
                    putNull("lease_owner")
                    putNull("lease_until")
                },
                "scope_id=? AND state IN ('pending','leased')",
                arrayOf(scope.scopeId),
            )
            db.execSQL(
                "UPDATE sessions SET working_summary='{}',working_sources='[]'," +
                    "working_version=working_version+1 WHERE scope_id=?",
                arrayOf(scope.scopeId),
            )
            db.insertWithOnConflict(
                "outbox",
                null,
                ContentValues().apply {
                    put("scope_id", scope.scopeId)
                    put("event_id", "resync-${UUID.randomUUID()}")
                    put("kind", "scope_resync")
                    put("target_id", "*")
                    put("target_version", 0)
                    put("deletion_epoch", epoch)
                    put("state", "pending")
                    put("attempts", 0)
                    put("next_attempt_at", now())
                    put("created_at", now())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            refreshLedgers(
                db,
                scope.scopeId,
                "memories",
                "evidence",
                "dependencies",
                "messages",
                "jobs",
                "sessions",
                "outbox",
                "tombstones",
            )
            MemoryCommandResult(
                status = "saved",
                operationId = command.operationId,
                deletionEpoch = epoch,
                removedMemoryIds = memoryIds.toList(),
            )
        }
    }

    fun close() {
        database.close()
    }

    private fun transaction(
        scope: TrustedScopeContext,
        operationId: String,
        commandSeq: Int,
        requestDigest: String,
        block: (SQLiteDatabase) -> MemoryCommandResult,
    ): MemoryCommandResult {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val currentScope = requireScope(db, scope.scopeId)
            val previous = db.query(
                "operations",
                arrayOf("request_digest", "result_json"),
                "scope_id=? AND operation_id=?",
                arrayOf(scope.scopeId, operationId),
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
            if (previous != null) {
                if (previous.first != requestDigest) {
                    throw MemoryV2Exception(
                        MemoryV2ErrorCode.IDEMPOTENCY_CONFLICT,
                    )
                }
                db.setTransactionSuccessful()
                return decodeResult(previous.second).copy(
                    status = "unchanged",
                    transaction = "read_only",
                )
            }
            val opWatermark = queryLong(
                db,
                "SELECT op_watermark FROM scopes WHERE scope_id=?",
                arrayOf(scope.scopeId),
            )
            if (commandSeq <= opWatermark) {
                throw MemoryV2Exception(MemoryV2ErrorCode.OPERATION_EXPIRED)
            }
            val result = block(db)
            db.insertOrThrow(
                "operations",
                null,
                ContentValues().apply {
                    put("scope_id", scope.scopeId)
                    put("operation_id", operationId)
                    put("command_seq", commandSeq)
                    put("request_digest", requestDigest)
                    put("result_json", encodeResult(result))
                    put("created_at", now())
                },
            )
            db.update(
                "scopes",
                ContentValues().apply { put("op_watermark", commandSeq) },
                "scope_id=?",
                arrayOf(scope.scopeId),
            )
            pruneOperations(db, scope.scopeId)
            appendAudit(db, scope.scopeId, operationId, result)
            refreshLedgers(
                db,
                scope.scopeId,
                "operations",
                "audit_events",
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

    private fun locateEvidence(
        db: SQLiteDatabase,
        scopeId: String,
        evidence: MemoryCommandEvidence,
        predicate: String,
    ): LocatedCommandEvidence {
        val source = db.query(
            "messages",
            arrayOf(
                "message_id",
                "revision",
                "role",
                "body",
                "received_at",
                "cache_expires_at",
                "state",
            ),
            "scope_id=? AND message_id=?",
            arrayOf(scopeId, evidence.messageId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_UNAVAILABLE)
            }
            val body = cursor.getString(3)
            if (
                cursor.getString(6) != "live" ||
                body == null ||
                cursor.getLong(5) <= now()
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_UNAVAILABLE)
            }
            if (cursor.getInt(1) != evidence.sourceRevision) {
                throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_CHANGED)
            }
            SourceSnapshot(
                messageId = cursor.getString(0),
                revision = cursor.getInt(1),
                role = cursor.getString(2),
                body = body,
                receivedAt = cursor.getLong(4),
            )
        }
        if (source.role == "assistant" && predicate != "event.summary") {
            throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_EVIDENCE)
        }
        val location = MemoryV2QuoteLocator.locate(
            body = source.body,
            quote = evidence.quote,
            maxQuoteUtf8Bytes = config.maxQuoteUtf8Bytes,
        )
        return LocatedCommandEvidence(
            evidence = evidence,
            source = source,
            location = location,
        )
    }

    private fun factKey(
        scopeId: String,
        subjectId: String,
        predicate: String,
        viewpointId: String?,
        value: String,
        cardinality: String,
        eventId: String,
    ): String {
        val fields = buildList {
            add(subjectId)
            add(predicate)
            add(viewpointId.orEmpty())
            if (cardinality == "multi") add(value)
            if (cardinality == "event") add(eventId)
        }
        return crypto.hmac(
            "fact:$scopeId",
            fields.joinToString("\u0000"),
        )
    }

    private fun requestDigest(
        action: String,
        scopeId: String,
        command: Any,
    ): String {
        return crypto.hmac(
            "request:$scopeId:$action",
            commandDigestPayload(command),
        )
    }

    private fun requireScope(
        db: SQLiteDatabase,
        scopeId: String,
    ): TrustedScopeContext {
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
            if (!cursor.moveToFirst() || cursor.getString(
                    cursor.getColumnIndexOrThrow("state"),
                ) != "active"
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            return TrustedScopeContext(
                scopeId = scopeId,
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
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            if (cursor.getString(0) != "resolved") {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
        }
    }

    private fun findMemory(
        db: SQLiteDatabase,
        scopeId: String,
        memoryId: String,
    ): StoredMemory? {
        return queryMemory(
            db,
            "scope_id=? AND memory_id=?",
            arrayOf(scopeId, memoryId),
        )
    }

    private fun findMemoryByFact(
        db: SQLiteDatabase,
        scopeId: String,
        factKey: String,
    ): StoredMemory? {
        return queryMemory(
            db,
            "scope_id=? AND fact_key=?",
            arrayOf(scopeId, factKey),
        )
    }

    private fun queryMemory(
        db: SQLiteDatabase,
        where: String,
        args: Array<String>,
    ): StoredMemory? {
        db.query(
            "memories",
            null,
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
            return StoredMemory(
                memoryId = cursor.string("memory_id"),
                version = cursor.int("version"),
                factKey = cursor.string("fact_key"),
                subjectId = cursor.string("subject_id"),
                viewpointId = cursor.getNullableString("viewpoint_id"),
                predicate = cursor.string("predicate"),
                valueJson = cursor.string("value_json"),
                tier = cursor.string("tier"),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                expiresAt = cursor.getLongOrNull("expires_at"),
            )
        }
    }

    private fun writeHistory(
        db: SQLiteDatabase,
        scopeId: String,
        memory: StoredMemory,
    ) {
        val snapshot = db.query(
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
            ),
            "scope_id=? AND memory_id=?",
            arrayOf(scopeId, memory.memoryId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.MEMORY_NOT_FOUND)
            }
            JSONObject()
                .put("memory_id", cursor.string("memory_id"))
                .put("version", cursor.int("version"))
                .put("fact_key", cursor.string("fact_key"))
                .put("tier", cursor.string("tier"))
                .put("subject_id", cursor.string("subject_id"))
                .put("viewpoint_id", cursor.getNullableString("viewpoint_id") ?: JSONObject.NULL)
                .put("predicate", cursor.string("predicate"))
                .put("text", cursor.string("text"))
                .put("value_json", cursor.string("value_json"))
                .put("epistemic", cursor.string("epistemic"))
                .put("lifecycle", cursor.string("lifecycle"))
                .put(
                    "retracted_at",
                    cursor.getLongOrNull("retracted_at") ?: JSONObject.NULL,
                )
                .put(
                    "commitment_state",
                    cursor.getNullableString("commitment_state") ?: JSONObject.NULL,
                )
                .put("temporal_json", cursor.string("temporal_json"))
                .put(
                    "retention_score",
                    cursor.getDouble(
                        cursor.getColumnIndexOrThrow("retention_score"),
                    ),
                )
                .put("source_anchor", cursor.getLong(
                    cursor.getColumnIndexOrThrow("source_anchor"),
                ))
                .put(
                    "expires_at",
                    cursor.getLongOrNull("expires_at") ?: JSONObject.NULL,
                )
                .put("updated_at", cursor.getLong(
                    cursor.getColumnIndexOrThrow("updated_at"),
                ))
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
                        ).use { evidenceCursor ->
                            while (evidenceCursor.moveToNext()) {
                                put(
                                    JSONObject()
                                        .put("evidence_id", evidenceCursor.getString(0))
                                        .put("message_id", evidenceCursor.getString(1))
                                        .put("source_revision", evidenceCursor.getInt(2))
                                        .put("source_kind", evidenceCursor.getString(3))
                                        .put("quote", evidenceCursor.getString(4))
                                        .put("start_codepoint", evidenceCursor.getInt(5))
                                        .put("end_codepoint", evidenceCursor.getInt(6))
                                        .put("source_sha256", evidenceCursor.getString(7))
                                        .put("captured_at", evidenceCursor.getLong(8)),
                                )
                            }
                        }
                    },
                )
        }
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

    private fun assertTierCapacity(
        db: SQLiteDatabase,
        scopeId: String,
        tier: String,
        addBytes: Long,
        replacing: Boolean,
    ) {
        val limit = config.tiers[tier] ?: return
        val usage = db.rawQuery(
            "SELECT COUNT(*) AS records, COALESCE(SUM(" +
                "length(CAST(text AS BLOB)) + length(CAST(value_json AS BLOB)) + " +
                "${config.rowOverheadBytes}),0) AS bytes " +
                "FROM memories WHERE scope_id=? AND tier=?",
            arrayOf(scopeId, tier),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0) to cursor.getLong(1)
        }
        val records = usage.first + if (replacing) 0 else 1
        val bytes = usage.second + if (replacing) 0 else addBytes
        if (
            (limit.maxRecords != null && records > limit.maxRecords) ||
            (limit.maxBytes != null && bytes > limit.maxBytes)
        ) {
            throw MemoryV2Exception(MemoryV2ErrorCode.RESOURCE_CAPACITY_EXHAUSTED)
        }
    }

    private fun fieldBlocked(db: SQLiteDatabase, scopeId: String, field: String): Boolean {
        return exists(
            db,
            "SELECT 1 FROM tombstones WHERE scope_id=? " +
                "AND target_kind='field' AND target_key=?",
            arrayOf(scopeId, field),
        )
    }

    private fun factBlocked(db: SQLiteDatabase, scopeId: String, factKey: String): Boolean {
        return exists(
            db,
            "SELECT 1 FROM tombstones WHERE scope_id=? " +
                "AND target_kind='fact' AND target_key=?",
            arrayOf(scopeId, factKey),
        )
    }

    private fun insertDependency(
        db: SQLiteDatabase,
        scopeId: String,
        parentKind: String,
        parentId: String,
        childKind: String,
        childId: String,
        revision: Int?,
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
                if (revision == null) putNull("source_revision") else put(
                    "source_revision",
                    revision,
                )
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

    private fun evidenceSourceIds(
        db: SQLiteDatabase,
        scopeId: String,
        memoryId: String,
    ): Set<String> {
        return queryStrings(
            db,
            "SELECT source_message_id FROM evidence WHERE scope_id=? AND memory_id=?",
            arrayOf(scopeId, memoryId),
        ).toSet()
    }

    private fun putTombstone(
        db: SQLiteDatabase,
        scopeId: String,
        kind: String,
        key: String,
        epoch: Int,
        cutoff: JSONObject,
        operationId: String,
        mode: String,
    ) {
        db.insertWithOnConflict(
            "tombstones",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put(
                    "tombstone_id",
                    MemoryV2Crypto.sha256("$scopeId|$kind|$key"),
                )
                put("target_kind", kind)
                put("target_key", key)
                put("deletion_epoch", epoch)
                put("source_cutoff_json", cutoff.toString())
                put("mode", mode)
                put("operation_id", operationId)
                put("created_at", now())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun querySessions(
        db: SQLiteDatabase,
        scopeId: String,
    ): List<Pair<String, Int>> {
        db.query(
            "sessions",
            arrayOf("session_id", "next_seq"),
            "scope_id=?",
            arrayOf(scopeId),
            null,
            null,
            null,
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0) to cursor.getInt(1))
                }
            }
        }
    }

    private fun refreshLedgers(
        db: SQLiteDatabase,
        scopeId: String,
        vararg resources: String,
    ) {
        resources.forEach { resource ->
            val usage = resourceUsage(db, scopeId, resource)
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

    private fun resourceUsage(
        db: SQLiteDatabase,
        scopeId: String,
        resource: String,
    ): Pair<Long, Int> {
        val table = when (resource) {
            "memories" -> "memories"
            "evidence" -> "evidence"
            "dependencies" -> "dependencies"
            "outbox" -> "outbox"
            "operations" -> "operations"
            "audit_events" -> "audit_events"
            "messages" -> "messages"
            "jobs" -> "jobs"
            "sessions" -> "sessions"
            "tombstones" -> "tombstones"
            "memory_conflicts" -> "memory_conflicts"
            "memory_relations" -> "memory_relations"
            else -> return 0L to 0
        }
        val byteExpression = when (table) {
            "messages" -> "COALESCE(length(CAST(body AS BLOB)),0)+length(CAST(origin_json AS BLOB))"
            "memories" -> "length(CAST(text AS BLOB))+length(CAST(value_json AS BLOB))+length(CAST(temporal_json AS BLOB))"
            "evidence" -> "length(CAST(quote AS BLOB))"
            "operations" -> "length(CAST(result_json AS BLOB))"
            "jobs" -> "length(CAST(envelope_json AS BLOB))"
            "sessions" -> "length(CAST(working_summary AS BLOB))+length(CAST(working_sources AS BLOB))"
            "tombstones" -> "length(CAST(source_cutoff_json AS BLOB))"
            "memory_conflicts" -> "length(CAST(candidate_json AS BLOB))"
            "memory_relations" -> "0"
            else -> "0"
        }
        return db.rawQuery(
            "SELECT COALESCE(SUM($byteExpression+${config.rowOverheadBytes}),0) " +
                "AS bytes, COUNT(*) AS records FROM $table WHERE scope_id=?",
            arrayOf(scopeId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0) to cursor.getInt(1)
        }
    }

    private fun pruneOperations(db: SQLiteDatabase, scopeId: String) {
        val limit = config.resources["operations"]?.maxRecords ?: return
        db.execSQL(
            "DELETE FROM operations WHERE scope_id=? AND operation_id IN (" +
                "SELECT operation_id FROM operations WHERE scope_id=? " +
                "ORDER BY command_seq DESC LIMIT -1 OFFSET ?)",
            arrayOf(scopeId, scopeId, limit),
        )
    }

    private fun appendAudit(
        db: SQLiteDatabase,
        scopeId: String,
        operationId: String,
        result: MemoryCommandResult,
    ) {
        val epoch = queryLong(
            db,
            "SELECT deletion_epoch FROM scopes WHERE scope_id=?",
            arrayOf(scopeId),
        )
        db.insertOrThrow(
            "audit_events",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put("audit_id", UUID.randomUUID().toString())
                put("operation_id", operationId)
                put("action", result.status)
                if (result.memoryId == null) putNull("target_id") else put(
                    "target_id",
                    result.memoryId,
                )
                putNull("from_version")
                if (result.version == null) putNull("to_version") else put(
                    "to_version",
                    result.version,
                )
                put("result_code", result.errorCode ?: "OK")
                put("deletion_epoch", epoch)
                put("created_at", now())
                put("expires_at", now() + config.historySeconds)
            },
        )
    }

    private fun encodeResult(result: MemoryCommandResult): String {
        return JSONObject()
            .put("status", result.status)
            .put("operationId", result.operationId)
            .put("memoryId", result.memoryId)
            .put("version", result.version)
            .put("deletionEpoch", result.deletionEpoch)
            .put("removedMemoryIds", JSONArray(result.removedMemoryIds))
            .toString()
    }

    private fun decodeResult(raw: String): MemoryCommandResult {
        val root = JSONObject(raw)
        return MemoryCommandResult(
            status = root.optString("status", "unchanged"),
            operationId = root.optString("operationId"),
            memoryId = root.optString("memoryId").takeIf { it.isNotBlank() },
            version = if (root.isNull("version")) null else root.optInt("version"),
            deletionEpoch = if (root.isNull("deletionEpoch")) {
                null
            } else {
                root.optInt("deletionEpoch")
            },
            removedMemoryIds = root.optJSONArray("removedMemoryIds")
                ?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            add(array.optString(index))
                        }
                    }
                }
                .orEmpty(),
        )
    }

    private fun exists(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): Boolean {
        db.rawQuery(sql, args).use { cursor ->
            return cursor.moveToFirst()
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

    private fun queryStrings(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): List<String> {
        db.rawQuery(sql, args).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
    }

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

    private fun Cursor.getNullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun jsonString(value: String): String {
        return JSONArray().put(value).toString().removePrefix("[").removeSuffix("]")
    }

    private data class SourceSnapshot(
        val messageId: String,
        val revision: Int,
        val role: String,
        val body: String,
        val receivedAt: Long,
    )

    private data class LocatedCommandEvidence(
        val evidence: MemoryCommandEvidence,
        val source: SourceSnapshot,
        val location: LocatedQuote,
    )

    private data class StoredMemory(
        val memoryId: String,
        val version: Int,
        val factKey: String,
        val subjectId: String,
        val viewpointId: String?,
        val predicate: String,
        val valueJson: String,
        val tier: String,
        val createdAt: Long,
        val expiresAt: Long?,
    )
}
