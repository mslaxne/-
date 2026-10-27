package com.local.wxbotprobe.memory.v2

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

class MemoryV2Repository(
    private val context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) : AutoCloseable {
    private val config = MemoryV2ConfigLoader.load(context)
    private val identities = MemoryV2IdentityStore(context)

    fun ensureScope(
        resolved: ResolvedScopeInput,
        contactDisplayName: String,
    ): TrustedScopeContext {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            var scope = findScope(db, resolved.key)
            if (scope == null) {
                val scopeId = UUID.randomUUID().toString()
                db.insertOrThrow(
                    "scopes",
                    null,
                    ContentValues().apply {
                        put("scope_id", scopeId)
                        put("owner_id", resolved.key.ownerId)
                        put("contact_id", resolved.key.contactId)
                        put("agent_id", resolved.key.agentId)
                        put("persona_id", resolved.key.personaId)
                        put("realm_id", resolved.key.realmId)
                        put("persona_revision", resolved.personaRevision)
                        put("revision", 0)
                        put("deletion_epoch", 0)
                        put("import_generation", 1)
                        put("policy_version", "2.0.0")
                        put("config_version", "2.0.0")
                        put("retention_policy", "independent_evidence")
                        put("state", "active")
                        put("auto_learn_blocked", 0)
                        put("op_watermark", 0)
                        put("restore_guard_id", identities.restoreGuardId)
                        put("created_at", now())
                    },
                )
                ensureEntity(
                    db = db,
                    scopeId = scopeId,
                    entityId = "user-${resolved.key.contactId}",
                    kind = "user",
                    displayName = contactDisplayName,
                )
                ensureEntity(
                    db = db,
                    scopeId = scopeId,
                    entityId = "agent-${resolved.key.agentId}",
                    kind = "agent",
                    displayName = "agent",
                )
                scope = readScope(db, scopeId)
                    ?: error("scope was not created")
            } else {
                MemoryV2RestoreFence.requireMatch(
                    scope.restoreGuardId,
                    identities.restoreGuardId,
                )
            }
            if (scope.personaRevision != resolved.personaRevision) {
                db.update(
                    "scopes",
                    ContentValues().apply {
                        put("persona_revision", resolved.personaRevision)
                    },
                    "scope_id=?",
                    arrayOf(scope.scopeId),
                )
                scope = scope.copy(personaRevision = resolved.personaRevision)
            }
            db.setTransactionSuccessful()
            return scope
        } finally {
            finishTransaction(db)
        }
    }

    fun appendMessage(
        scope: TrustedScopeContext,
        request: AppendMessageRequest,
    ): AppendMessageResult {
        require(request.clientMessageId.isNotBlank()) {
            "clientMessageId is required"
        }
        require(request.role in setOf("user", "assistant", "tool", "config")) {
            "unsupported role"
        }
        require(request.timezone.isNotBlank()) { "timezone is required" }
        val bodyBytes = request.text.toByteArray(Charsets.UTF_8).size
        if (bodyBytes > config.maxMessageUtf8Bytes) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.RESOURCE_CAPACITY_EXHAUSTED,
                "message exceeds max_message_utf8_bytes",
            )
        }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val current = readScope(db, scope.scopeId)
                ?: throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            if (current.state != "active") {
                throw MemoryV2Exception(
                    MemoryV2ErrorCode.UNAUTHORIZED_SCOPE,
                    "scope state=${current.state}",
                )
            }
            val sessionId = request.sessionId?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            val session = findSession(db, scope.scopeId, sessionId)
            if (request.sessionId != null && session == null) {
                throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT)
            }
            val existing = findMessageByClientId(
                db,
                scope.scopeId,
                sessionId,
                request.clientMessageId,
            )
            if (existing != null) {
                db.setTransactionSuccessful()
                return existing
            }
            val purged = findPurgedMessage(
                db,
                scope.scopeId,
                sessionId,
                request.clientMessageId,
            )
            if (purged != null) {
                db.setTransactionSuccessful()
                return purged
            }
            val nextSeq = session?.nextSeq ?: 1
            val cacheExpiresAt = minOf(
                request.receivedAt + config.sourceCacheSeconds,
                request.hostExpiresAt ?: Long.MAX_VALUE,
            )
            val originJson = "{}"
            val estimatedMessageBytes = bodyBytes +
                originJson.toByteArray(Charsets.UTF_8).size +
                config.rowOverheadBytes
            assertResourceCapacity(
                db = db,
                scopeId = scope.scopeId,
                resource = "messages",
                addRecords = 1,
                addBytes = estimatedMessageBytes,
            )
            if (session == null) {
                val workingSummary = "{}"
                val workingSources = "[]"
                assertResourceCapacity(
                    db = db,
                    scopeId = scope.scopeId,
                    resource = "sessions",
                    addRecords = 1,
                    addBytes = workingSummary.toByteArray(Charsets.UTF_8).size +
                        workingSources.toByteArray(Charsets.UTF_8).size +
                        config.rowOverheadBytes,
                )
                db.insertOrThrow(
                    "sessions",
                    null,
                    ContentValues().apply {
                        put("scope_id", scope.scopeId)
                        put("session_id", sessionId)
                        put("next_seq", nextSeq + 1)
                        put("working_summary", workingSummary)
                        put("working_version", 0)
                        put("working_sources", workingSources)
                        put("deny_before_seq", 0)
                        put("state", "open")
                        put("created_at", request.receivedAt)
                        put("updated_at", request.receivedAt)
                    },
                )
            } else {
                db.update(
                    "sessions",
                    ContentValues().apply {
                        put("next_seq", nextSeq + 1)
                        put("updated_at", request.receivedAt)
                    },
                    "scope_id=? AND session_id=?",
                    arrayOf(scope.scopeId, sessionId),
                )
            }
            val messageId = UUID.randomUUID().toString()
            db.insertOrThrow(
                "messages",
                null,
                ContentValues().apply {
                    put("scope_id", scope.scopeId)
                    put("message_id", messageId)
                    put("session_id", sessionId)
                    put("client_message_id", request.clientMessageId)
                    put("seq", nextSeq)
                    put("revision", 1)
                    put("role", request.role)
                    put("origin_json", originJson)
                    put("body", request.text)
                    put("content_sha256", MemoryV2QuoteLocator.sha256(request.text))
                    put("occurred_at", request.occurredAt)
                    put("received_at", request.receivedAt)
                    put("timezone", request.timezone)
                    put("cache_expires_at", cacheExpiresAt)
                    if (request.hostExpiresAt == null) {
                        putNull("host_expires_at")
                    } else {
                        put("host_expires_at", request.hostExpiresAt)
                    }
                    put("state", "live")
                    put("extraction_state", "pending")
                    put("summary_state", "pending")
                },
            )
            refreshResourceLedger(db, scope.scopeId, "messages")
            refreshResourceLedger(db, scope.scopeId, "sessions")
            db.setTransactionSuccessful()
            return AppendMessageResult(
                status = "saved",
                scopeId = scope.scopeId,
                sessionId = sessionId,
                messageId = messageId,
                seq = nextSeq,
            )
        } catch (error: MemoryV2Exception) {
            throw error
        } catch (error: Throwable) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE,
                error.message.orEmpty(),
            )
        } finally {
            finishTransaction(db)
        }
    }

    fun hasClientMessage(
        scopeId: String,
        clientMessageId: String,
    ): Boolean {
        if (clientMessageId.isBlank()) {
            return false
        }
        return database.readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE scope_id=? AND client_message_id=? LIMIT 1",
            arrayOf(scopeId, clientMessageId),
        ).use { it.moveToFirst() }
    }

    fun closeSession(
        scope: TrustedScopeContext,
        sessionId: String,
        closedAt: Long = now(),
    ): Boolean {
        if (sessionId.isBlank()) {
            return false
        }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val updated = db.update(
                "sessions",
                ContentValues().apply {
                    put("state", "closed")
                    put("updated_at", closedAt)
                },
                "scope_id=? AND session_id=?",
                arrayOf(scope.scopeId, sessionId),
            )
            db.setTransactionSuccessful()
            return updated == 1
        } finally {
            db.endTransaction()
        }
    }

    fun recoverOpenSession(
        scope: TrustedScopeContext,
        recoveredAt: Long = now(),
    ): MemoryV2SessionRecovery? {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val sessions = db.query(
                "sessions",
                arrayOf("session_id", "next_seq", "updated_at"),
                "scope_id=? AND state='open'",
                arrayOf(scope.scopeId),
                null,
                null,
                "updated_at DESC, created_at DESC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            MemoryV2OpenSession(
                                sessionId = cursor.string("session_id"),
                                nextSeq = cursor.int("next_seq"),
                                updatedAt = cursor.long("updated_at"),
                            ),
                        )
                    }
                }
            }
            val latest = sessions.firstOrNull()
            if (latest == null) {
                db.setTransactionSuccessful()
                return null
            }
            val closedDuplicates = if (sessions.size == 1) {
                0
            } else {
                db.update(
                    "sessions",
                    ContentValues().apply {
                        put("state", "closed")
                        put("updated_at", recoveredAt)
                    },
                    "scope_id=? AND state='open' AND session_id<>?",
                    arrayOf(scope.scopeId, latest.sessionId),
                )
            }
            db.setTransactionSuccessful()
            return MemoryV2SessionRecovery(
                session = latest,
                closedDuplicates = closedDuplicates,
            )
        } catch (error: Throwable) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE,
                error.message.orEmpty(),
            )
        } finally {
            db.endTransaction()
        }
    }

    fun closeOpenSessions(closedAt: Long = now()): Int {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val closed = db.update(
                "sessions",
                ContentValues().apply {
                    put("state", "closed")
                    put("updated_at", closedAt)
                },
                "state='open'",
                null,
            )
            db.setTransactionSuccessful()
            return closed
        } finally {
            db.endTransaction()
        }
    }

    fun liveMessageIds(
        scope: TrustedScopeContext,
        sessionId: String,
    ): List<String> {
        return database.readableDatabase.query(
            "messages",
            arrayOf("message_id"),
            "scope_id=? AND session_id=? AND state='live' AND body IS NOT NULL",
            arrayOf(scope.scopeId, sessionId),
            null,
            null,
            "seq",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.string("message_id"))
                }
            }
        }
    }

    fun pendingExtractionIds(
        scope: TrustedScopeContext,
        sessionId: String,
    ): List<String> {
        return database.readableDatabase.query(
            "messages",
            arrayOf("message_id"),
            "scope_id=? AND session_id=? AND state='live' " +
                "AND extraction_state='pending' AND body IS NOT NULL",
            arrayOf(scope.scopeId, sessionId),
            null,
            null,
            "seq",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.string("message_id"))
                }
            }
        }
    }

    fun health(scopeId: String): MemoryV2Health {
        val db = database.readableDatabase
        return MemoryV2Health(
            scopeId = scopeId,
            activeMemories = count(
                db,
                "SELECT COUNT(*) FROM memories WHERE scope_id=? " +
                    "AND lifecycle='active' AND retracted_at IS NULL " +
                    "AND (expires_at IS NULL OR expires_at>?)",
                arrayOf(scopeId, now().toString()),
            ),
            activeSources = count(
                db,
                "SELECT COUNT(*) FROM messages WHERE scope_id=? " +
                    "AND state='live' AND body IS NOT NULL AND cache_expires_at>?",
                arrayOf(scopeId, now().toString()),
            ),
            pendingJobs = count(
                db,
                "SELECT COUNT(*) FROM jobs WHERE scope_id=? " +
                    "AND state IN ('pending','leased')",
                arrayOf(scopeId),
            ),
            pendingOutbox = count(
                db,
                "SELECT COUNT(*) FROM outbox WHERE scope_id=? " +
                    "AND state IN ('pending','leased')",
                arrayOf(scopeId),
            ),
            usedBytes = longValue(
                db,
                "SELECT COALESCE(SUM(used_bytes),0) FROM capacity_ledger WHERE scope_id=?",
                arrayOf(scopeId),
            ),
        )
    }

    fun scopeById(scopeId: String): TrustedScopeContext {
        val scope = readScope(database.readableDatabase, scopeId)
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
        if (scope.state != "active") {
            throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
        }
        return scope
    }

    fun scopeForKey(key: MemoryScopeKey): TrustedScopeContext? {
        return findScope(database.readableDatabase, key)
    }

    override fun close() {
        database.close()
    }

    private fun findScope(db: SQLiteDatabase, key: MemoryScopeKey): TrustedScopeContext? {
        db.query(
            "scopes",
            null,
            "owner_id=? AND contact_id=? AND agent_id=? AND persona_id=? AND realm_id=?",
            arrayOf(
                key.ownerId,
                key.contactId,
                key.agentId,
                key.personaId,
                key.realmId,
            ),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return readScope(cursor, key)
        }
    }

    private fun readScope(db: SQLiteDatabase, scopeId: String): TrustedScopeContext? {
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
            val key = MemoryScopeKey(
                ownerId = cursor.string("owner_id"),
                contactId = cursor.string("contact_id"),
                agentId = cursor.string("agent_id"),
                personaId = cursor.string("persona_id"),
                realmId = cursor.string("realm_id"),
            )
            return readScope(cursor, key)
        }
    }

    private fun readScope(cursor: Cursor, key: MemoryScopeKey): TrustedScopeContext {
        return TrustedScopeContext(
            scopeId = cursor.string("scope_id"),
            key = key,
            personaRevision = cursor.int("persona_revision"),
            revision = cursor.int("revision"),
            deletionEpoch = cursor.int("deletion_epoch"),
            importGeneration = cursor.int("import_generation"),
            retentionPolicy = cursor.string("retention_policy"),
            state = cursor.string("state"),
            restoreGuardId = cursor.string("restore_guard_id"),
        )
    }

    private fun ensureEntity(
        db: SQLiteDatabase,
        scopeId: String,
        entityId: String,
        kind: String,
        displayName: String,
    ) {
        val safeName = displayName.trim().ifBlank { entityId }
        db.insertWithOnConflict(
            "entities",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put("entity_id", entityId)
                put("kind", kind)
                put("display_name", safeName)
                put("version", 1)
                put("resolution", "resolved")
                put("created_at", now())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        val normalized = Normalizer.normalize(safeName, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .trim()
        if (normalized.isNotBlank()) {
            db.insertWithOnConflict(
                "aliases",
                null,
                ContentValues().apply {
                    put("scope_id", scopeId)
                    put("alias_id", UUID.randomUUID().toString())
                    put("entity_id", entityId)
                    put("normalized", normalized)
                    put("original", safeName)
                    putNull("source_message_id")
                    putNull("source_revision")
                    put("created_at", now())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    private fun findSession(
        db: SQLiteDatabase,
        scopeId: String,
        sessionId: String,
    ): SessionRow? {
        db.query(
            "sessions",
            arrayOf("next_seq"),
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
            return SessionRow(nextSeq = cursor.int("next_seq"))
        }
    }

    private fun findMessageByClientId(
        db: SQLiteDatabase,
        scopeId: String,
        sessionId: String,
        clientMessageId: String,
    ): AppendMessageResult? {
        db.query(
            "messages",
            arrayOf("message_id", "seq"),
            "scope_id=? AND session_id=? AND client_message_id=?",
            arrayOf(scopeId, sessionId, clientMessageId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return AppendMessageResult(
                status = "unchanged",
                scopeId = scopeId,
                sessionId = sessionId,
                messageId = cursor.string("message_id"),
                seq = cursor.int("seq"),
            )
        }
    }

    private fun findPurgedMessage(
        db: SQLiteDatabase,
        scopeId: String,
        sessionId: String,
        clientMessageId: String,
    ): AppendMessageResult? {
        return db.query(
            "message_cleanup",
            arrayOf("message_id", "seq"),
            "scope_id=? AND session_id=? AND client_message_id=? AND state='purged'",
            arrayOf(scopeId, sessionId, clientMessageId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            AppendMessageResult(
                status = "unchanged",
                scopeId = scopeId,
                sessionId = sessionId,
                messageId = cursor.getString(0),
                seq = cursor.getInt(1),
            )
        }
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
            usage.records + addRecords > limit.maxRecords ||
            usage.bytes + addBytes > limit.maxBytes
        ) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.RESOURCE_CAPACITY_EXHAUSTED,
                resource,
            )
        }
    }

    private fun refreshResourceLedger(
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
            "messages" -> "SELECT COUNT(*) AS records, " +
                "COALESCE(SUM(COALESCE(length(CAST(body AS BLOB)),0) + " +
                "length(CAST(origin_json AS BLOB)) + ${config.rowOverheadBytes}),0) " +
                "AS bytes FROM messages WHERE scope_id=?"
            "sessions" -> "SELECT COUNT(*) AS records, " +
                "COALESCE(SUM(length(CAST(working_summary AS BLOB)) + " +
                "length(CAST(working_sources AS BLOB)) + ${config.rowOverheadBytes}),0) " +
                "AS bytes FROM sessions WHERE scope_id=?"
            else -> return ResourceUsage(0L, 0)
        }
        db.rawQuery(sql, arrayOf(scopeId)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return ResourceUsage(0L, 0)
            }
            return ResourceUsage(
                bytes = cursor.getLong(cursor.getColumnIndexOrThrow("bytes")),
                records = cursor.getInt(cursor.getColumnIndexOrThrow("records")),
            )
        }
    }

    private fun count(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): Int {
        db.rawQuery(sql, args).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun longValue(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): Long {
        db.rawQuery(sql, args).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun Cursor.string(column: String): String {
        return getString(getColumnIndexOrThrow(column)).orEmpty()
    }

    private fun Cursor.int(column: String): Int {
        return getInt(getColumnIndexOrThrow(column))
    }

    private fun Cursor.long(column: String): Long {
        return getLong(getColumnIndexOrThrow(column))
    }

    private fun finishTransaction(db: SQLiteDatabase) {
        try {
            db.endTransaction()
        } catch (error: SQLiteException) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE,
                error.message.orEmpty(),
            )
        }
    }

    private data class SessionRow(
        val nextSeq: Int,
    )

    private data class ResourceUsage(
        val bytes: Long,
        val records: Int,
    )
}
