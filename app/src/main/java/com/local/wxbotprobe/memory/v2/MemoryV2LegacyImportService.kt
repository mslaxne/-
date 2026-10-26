package com.local.wxbotprobe.memory.v2

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.local.wxbotprobe.bot.PersonaSelectionService
import com.local.wxbotprobe.memory.ChatIndexEntry
import com.local.wxbotprobe.memory.ChatMemoryStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class LegacyImportResult(
    val status: String,
    val runId: String? = null,
    val imported: Int = 0,
    val quarantined: Int = 0,
    val expired: Int = 0,
    val blocked: Int = 0,
    val errorCode: String? = null,
)

class MemoryV2LegacyImportService(
    private val context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val config = MemoryV2ConfigLoader.load(context)
    private val crypto = MemoryV2Crypto()
    private val scopeResolver = MemoryV2ScopeResolver(context)
    private val contactIdentities = MemoryV2ContactIdentityStore(context)
    private val personaSelection = PersonaSelectionService(context)
    private val scopeRepository = MemoryV2Repository(context, database, now)

    fun plan(store: ChatMemoryStore): LegacyMigrationPlan {
        return MemoryV2LegacyMigrationPlanner(
            a2TtlSeconds = config.tiers["A2"]?.ttlSeconds
                ?: throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT),
            maxQuoteUtf8Bytes = config.maxQuoteUtf8Bytes,
        ).plan(store, now() * 1_000L) { entry ->
            val selection = personaSelection.selectedForContact(entry.chatId)
            scopeResolver.resolve(
                contactId = entry.chatId,
                personaId = selection.personaId,
                persona = selection.persona,
            )
        }
    }

    fun import(
        store: ChatMemoryStore,
        legacyRoot: File,
        plan: LegacyMigrationPlan,
        approvedContactIds: Set<String>,
        generation: Int = 1,
    ): LegacyImportResult {
        return importInternal(
            store = store,
            legacyRoot = legacyRoot,
            plan = plan,
            approvedContactIds = approvedContactIds,
            generation = generation,
            maxCommittedItems = null,
        )
    }

    internal fun importWithCheckpointLimit(
        store: ChatMemoryStore,
        legacyRoot: File,
        plan: LegacyMigrationPlan,
        approvedContactIds: Set<String>,
        generation: Int = 1,
        maxCommittedItems: Int?,
    ): LegacyImportResult {
        require(maxCommittedItems == null || maxCommittedItems >= 0)
        return importInternal(
            store = store,
            legacyRoot = legacyRoot,
            plan = plan,
            approvedContactIds = approvedContactIds,
            generation = generation,
            maxCommittedItems = maxCommittedItems,
        )
    }

    private fun importInternal(
        store: ChatMemoryStore,
        legacyRoot: File,
        plan: LegacyMigrationPlan,
        approvedContactIds: Set<String>,
        generation: Int,
        maxCommittedItems: Int?,
    ): LegacyImportResult {
        val migrationLimit = config.resources["migration"]
        if (
            migrationLimit != null &&
            plan.totalItems > migrationLimit.maxRecords
        ) {
            return LegacyImportResult(
                status = "failed",
                errorCode = MemoryV2ErrorCode.RESOURCE_CAPACITY_EXHAUSTED.name,
            )
        }
        val importableContacts = plan.contacts.filter {
            it.items.any { item -> item.state == "importable" }
        }
        if (importableContacts.any { it.contactId !in approvedContactIds }) {
            return LegacyImportResult(
                status = "needs_confirmation",
                errorCode = MemoryV2ErrorCode.LEGACY_MAPPING_NOT_APPROVED.name,
            )
        }
        val sourceHash = hashLegacyRoot(legacyRoot)
        val mappingHash = sha256(
            plan.contacts.sortedBy { it.contactId }.joinToString("\n") {
                listOf(
                    it.contactId,
                    it.scopeKey.ownerId,
                    it.scopeKey.agentId,
                    it.scopeKey.personaId,
                    it.scopeKey.realmId,
                ).joinToString("|")
            },
        )
        val db = database.writableDatabase
        val existing = db.query(
            "migration_runs",
            arrayOf("run_id", "state"),
            "source_sha256=? AND mapping_sha256=? AND generation=?",
            arrayOf(sourceHash, mappingHash, generation.toString()),
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
        if (existing != null && existing.second == "committed") {
            val counts = migrationReceiptCounts(db, existing.first)
            return LegacyImportResult(
                status = "unchanged",
                runId = existing.first,
                imported = counts.imported,
                quarantined = counts.quarantined,
                expired = counts.expired,
                blocked = counts.blocked,
            )
        }

        val entries = store.allChats().associateBy { it.chatId }
        if (importableContacts.any { it.contactId !in entries }) {
            return LegacyImportResult(
                status = "failed",
                errorCode = MemoryV2ErrorCode.LEGACY_MAPPING_STALE.name,
            )
        }
        plan.contacts.forEach { contact ->
            entries[contact.contactId]?.let { entry ->
                contactIdentities.bind(entry.identityKey, entry.chatId)
            }
        }
        val resolvedByContact = mutableMapOf<String, ResolvedScopeInput>()
        plan.contacts.filter { it.items.isNotEmpty() }.forEach { contact ->
            val selection = personaSelection.selectedForContact(contact.contactId)
            val resolved = scopeResolver.resolve(
                contactId = contact.contactId,
                personaId = selection.personaId,
                persona = selection.persona,
            )
            if (resolved.key != contact.scopeKey) {
                return LegacyImportResult(
                    status = "failed",
                    errorCode = MemoryV2ErrorCode.LEGACY_MAPPING_STALE.name,
                )
            }
            resolvedByContact[contact.contactId] = resolved
        }
        val scopes = mutableMapOf<String, TrustedScopeContext>()
        plan.contacts.filter { it.items.isNotEmpty() }.forEach { contact ->
            val entry = entries[contact.contactId] ?: return@forEach
            val resolved = resolvedByContact.getValue(contact.contactId)
            scopes[contact.contactId] = scopeRepository.ensureScope(
                resolved = resolved,
                contactDisplayName = entry.displayName,
            )
        }
        if (scopes.values.any { it.deletionEpoch > 0 }) {
            return LegacyImportResult(
                status = "blocked",
                errorCode = MemoryV2ErrorCode.LEGACY_DELETION_BARRIER.name,
                blocked = importableContacts.sumOf { contact ->
                    contact.items.count { it.state == "importable" }
                },
            )
        }
        val runId = existing?.first ?: UUID.randomUUID().toString()
        if (existing == null) {
            db.beginTransaction()
            try {
                if (scopes.values.any { scope ->
                        db.rawQuery(
                            "SELECT deletion_epoch FROM scopes WHERE scope_id=?",
                            arrayOf(scope.scopeId),
                        ).use { cursor ->
                            cursor.moveToFirst() && cursor.getInt(0) > 0
                        }
                    }
                ) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.LEGACY_DELETION_BARRIER)
                }
                db.insertOrThrow(
                    "migration_runs",
                    null,
                    ContentValues().apply {
                        put("run_id", runId)
                        put("source_sha256", sourceHash)
                        put("mapping_sha256", mappingHash)
                        put("generation", generation)
                        put("state", "pending")
                        put("created_at", now())
                    },
                )
                db.setTransactionSuccessful()
            } catch (error: Throwable) {
                return LegacyImportResult(
                    status = "failed",
                    runId = runId,
                    errorCode = if (error is MemoryV2Exception) {
                        error.code.name
                    } else {
                        MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name
                    },
                )
            } finally {
                db.endTransaction()
            }
        } else {
            db.update(
                "migration_runs",
                ContentValues().apply { put("state", "pending") },
                "run_id=?",
                arrayOf(runId),
            )
        }
        val initialCounts = migrationReceiptCounts(db, runId)
        var imported = initialCounts.imported
        var quarantined = initialCounts.quarantined
        var expired = initialCounts.expired
        var blocked = initialCounts.blocked
        var committedThisCall = 0
        plan.contacts.forEach { contactPlan ->
                val entry = entries[contactPlan.contactId] ?: return@forEach
                val scope = scopes[contactPlan.contactId] ?: return@forEach
                val memory = store.loadMemory(entry)
                contactPlan.items.forEach { item ->
                    if (hasMigrationReceipt(db, runId, item.legacyId)) {
                        return@forEach
                    }
                    if (
                        maxCommittedItems != null &&
                        committedThisCall >= maxCommittedItems
                    ) {
                        return LegacyImportResult(
                            status = "paused",
                            runId = runId,
                            imported = imported,
                            quarantined = quarantined,
                            expired = expired,
                            blocked = blocked,
                        )
                    }
                    var failure: Throwable? = null
                    // The receipt and imported rows commit together as a resume checkpoint.
                    db.beginTransaction()
                    try {
                        db.update(
                            "migration_runs",
                            ContentValues().apply { put("state", "pending") },
                            "run_id=?",
                            arrayOf(runId),
                        )
                        assertMigrationScopeWritable(db, scope.scopeId)
                    when (item.state) {
                        "importable" -> {
                            val event = memory.events.firstOrNull {
                                "event:${it.id}" == item.legacyId
                            }
                            if (event == null) {
                                recordMigrationItem(
                                    db,
                                    runId,
                                    item,
                                    "quarantined",
                                    "LEGACY_EVENT_CHANGED",
                                    scope.scopeId,
                                )
                                quarantined++
                            } else {
                                val source = store.sessionMessages(
                                    event.sourceSessionId,
                                ).firstOrNull {
                                    it.id in event.sourceMessageIds
                                }
                                if (source == null) {
                                    recordMigrationItem(
                                        db,
                                        runId,
                                        item,
                                        "quarantined",
                                        "LEGACY_SOURCE_UNAVAILABLE",
                                        scope.scopeId,
                                    )
                                    quarantined++
                                } else {
                                    val ttlSeconds = config.tiers["A2"]?.ttlSeconds
                                        ?: throw MemoryV2Exception(
                                            MemoryV2ErrorCode.INVALID_ARGUMENT,
                                        )
                                    val expiresAt = source.timestamp +
                                        ttlSeconds * 1_000L
                                    if (expiresAt <= now() * 1_000L) {
                                        recordMigrationItem(
                                            db,
                                            runId,
                                            item,
                                            "expired",
                                            "MEMORY_EXPIRED",
                                            scope.scopeId,
                                        )
                                        expired++
                                    } else {
                                        val memoryId = importEvent(
                                            db,
                                            scope,
                                            event.id,
                                            event.text,
                                            event.importance,
                                            source,
                                        )
                                        recordMigrationItem(
                                            db,
                                            runId,
                                            item,
                                            "imported",
                                            null,
                                            scope.scopeId,
                                            memoryId,
                                        )
                                        imported++
                                    }
                                }
                            }
                        }
                        "quarantined" -> {
                            recordMigrationItem(
                                db,
                                runId,
                                item,
                                "quarantined",
                                item.reason,
                                scope.scopeId,
                            )
                            quarantined++
                        }
                        "expired" -> {
                            recordMigrationItem(
                                db,
                                runId,
                                item,
                                "expired",
                                item.reason,
                                scope.scopeId,
                            )
                            expired++
                        }
                        else -> {
                            recordMigrationItem(
                                db,
                                runId,
                                item,
                                "blocked",
                                item.reason,
                                scope.scopeId,
                            )
                            blocked++
                        }
                    }
                        db.setTransactionSuccessful()
                    } catch (error: Throwable) {
                        failure = error
                    } finally {
                        db.endTransaction()
                    }
                    failure?.let { error ->
                        markMigrationRunFailed(db, runId)
                        val failedCounts = migrationReceiptCounts(db, runId)
                        return LegacyImportResult(
                            status = "failed",
                            runId = runId,
                            imported = failedCounts.imported,
                            quarantined = failedCounts.quarantined,
                            expired = failedCounts.expired,
                            blocked = failedCounts.blocked,
                            errorCode = if (error is MemoryV2Exception) {
                                error.code.name
                            } else {
                                MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name
                            },
                        )
                    }
                    committedThisCall++
                }
        }
            markMigrationRunCommitted(db, runId)
            val finalCounts = migrationReceiptCounts(db, runId)
            return LegacyImportResult(
                status = if (committedThisCall > 0) "saved" else "unchanged",
                runId = runId,
                imported = finalCounts.imported,
                quarantined = finalCounts.quarantined,
                expired = finalCounts.expired,
                blocked = finalCounts.blocked,
            )
    }

    private fun importEvent(
        db: SQLiteDatabase,
        scope: TrustedScopeContext,
        eventId: String,
        eventText: String,
        importance: Double,
        source: com.local.wxbotprobe.memory.SessionMessage,
    ): String {
        if (eventText.toByteArray(Charsets.UTF_8).size > config.maxItemUtf8Bytes) {
            throw MemoryV2Exception(MemoryV2ErrorCode.ITEM_TOO_LARGE)
        }
        val sessionId = "legacy-session-" + sha256(
            "${scope.scopeId}|${source.id}|session",
        ).take(32)
        val messageId = "legacy-message-" + sha256(
            "${scope.scopeId}|${source.id}",
        ).take(32)
        val memoryId = "legacy-memory-" + sha256(
            "${scope.scopeId}|$eventId",
        ).take(32)
        val existingMemory = db.rawQuery(
            "SELECT 1 FROM memories WHERE scope_id=? AND memory_id=?",
            arrayOf(scope.scopeId, memoryId),
        ).use { cursor -> cursor.moveToFirst() }
        if (existingMemory) {
            refreshLedgers(
                db,
                scope.scopeId,
                "memories",
                "evidence",
                "messages",
                "sessions",
                "dependencies",
                "outbox",
            )
            return memoryId
        }
        val location = MemoryV2QuoteLocator.locate(
            source.text,
            source.text,
            config.maxQuoteUtf8Bytes,
        )
        val valueJson = jsonString(eventText)
        val temporalJson = JSONObject()
            .put("occurred_at", source.timestamp / 1_000L)
            .put("local_date", JSONObject.NULL)
            .put("timezone", config.timezone)
            .put("precision", "instant")
            .put("original", "")
            .toString()
        val memoryBytes = eventText.toByteArray(Charsets.UTF_8).size +
            valueJson.toByteArray(Charsets.UTF_8).size +
            temporalJson.toByteArray(Charsets.UTF_8).size +
            config.rowOverheadBytes
        assertTierCapacity(db, scope.scopeId, "A2", memoryBytes)
        assertResourceCapacity(
            db,
            scope.scopeId,
            "memories",
            1,
            memoryBytes,
        )
        assertResourceCapacity(
            db,
            scope.scopeId,
            "evidence",
            1,
            source.text.toByteArray(Charsets.UTF_8).size + config.rowOverheadBytes,
        )
        val sourceStored = db.rawQuery(
            "SELECT 1 FROM messages WHERE scope_id=? AND message_id=?",
            arrayOf(scope.scopeId, messageId),
        ).use { cursor -> cursor.moveToFirst() }
        if (!sourceStored) {
            assertResourceCapacity(
                db,
                scope.scopeId,
                "messages",
                1,
                "{}".toByteArray(Charsets.UTF_8).size + config.rowOverheadBytes,
            )
            assertResourceCapacity(
                db,
                scope.scopeId,
                "sessions",
                1,
                2 + config.rowOverheadBytes,
            )
        }
        db.insertWithOnConflict(
            "sessions",
            null,
            ContentValues().apply {
                put("scope_id", scope.scopeId)
                put("session_id", sessionId)
                put("next_seq", 1)
                put("working_summary", "{}")
                put("working_version", 0)
                put("working_sources", "[]")
                put("deny_before_seq", 0)
                put("state", "closed")
                put("created_at", source.timestamp / 1_000L)
                put("updated_at", now())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        db.insertWithOnConflict(
            "messages",
            null,
            ContentValues().apply {
                put("scope_id", scope.scopeId)
                put("message_id", messageId)
                put("session_id", sessionId)
                put("client_message_id", "legacy:${source.id}")
                put("seq", 1)
                put("revision", 1)
                put("role", source.role)
                put(
                    "origin_json",
                    JSONObject()
                        .put("format", "normalized-legacy-v1")
                        .put("legacy_message_id", source.id)
                        .toString(),
                )
                putNull("body")
                put("content_sha256", location.sourceSha256)
                put("occurred_at", source.timestamp / 1_000L)
                put("received_at", source.timestamp / 1_000L)
                put("timezone", config.timezone)
                put(
                    "cache_expires_at",
                    source.timestamp / 1_000L,
                )
                putNull("host_expires_at")
                put("state", "expired")
                put("extraction_state", "skipped")
                put("summary_state", "gap")
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        val anchor = source.timestamp / 1_000L
        val expiresAt = anchor + (
            config.tiers["A2"]?.ttlSeconds
                ?: throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT)
            )
        val factKey = crypto.hmac(
            "fact:${scope.scopeId}",
            listOf("user-${scope.key.contactId}", "event.summary", eventId)
                .joinToString("\u0000"),
        )
        db.insertOrThrow(
            "memories",
            null,
            ContentValues().apply {
                put("scope_id", scope.scopeId)
                put("memory_id", memoryId)
                put("version", 1)
                put("tier", "A2")
                put("fact_key", factKey)
                put("subject_id", "user-${scope.key.contactId}")
                putNull("viewpoint_id")
                put("predicate", "event.summary")
                put("text", eventText)
                put("value_json", valueJson)
                put("epistemic", "inferred")
                put("lifecycle", "active")
                put("pinned", 0)
                putNull("commitment_state")
                put("temporal_json", temporalJson)
                put("retention_score", importance.coerceIn(0.0, 1.0))
                put("source_anchor", anchor)
                put("expires_at", expiresAt)
                put("created_at", now())
                put("updated_at", now())
                put("deletion_epoch", scope.deletionEpoch)
            },
        )
        val evidenceId = "legacy-evidence-" + sha256(
            "$memoryId|$messageId",
        ).take(32)
        db.insertOrThrow(
            "evidence",
            null,
            ContentValues().apply {
                put("scope_id", scope.scopeId)
                put("evidence_id", evidenceId)
                put("memory_id", memoryId)
                put("source_message_id", messageId)
                put("source_revision", 1)
                put("source_kind", source.role)
                put("quote", source.text)
                put("start_codepoint", location.startCodepoint)
                put("end_codepoint", location.endCodepoint)
                put("source_sha256", location.sourceSha256)
                put("captured_at", now())
            },
        )
        insertDependency(db, scope.scopeId, messageId, memoryId)
        emitOutbox(db, scope.scopeId, memoryId, 1)
        db.execSQL(
            "UPDATE scopes SET revision=revision+1 WHERE scope_id=?",
            arrayOf(scope.scopeId),
        )
        refreshLedgers(
            db,
            scope.scopeId,
            "memories",
            "evidence",
            "messages",
            "sessions",
            "dependencies",
            "outbox",
        )
        return memoryId
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
                "length(CAST(text AS BLOB))+" +
                "length(CAST(value_json AS BLOB))+" +
                "length(CAST(temporal_json AS BLOB))+" +
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
            usage.first + addRecords > limit.maxRecords ||
            usage.second + addBytes > limit.maxBytes
        ) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.RESOURCE_CAPACITY_EXHAUSTED,
                resource,
            )
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
                    put("used_bytes", usage.second)
                    put("used_records", usage.first)
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
    ): Pair<Int, Long> {
        val (expression, table) = when (resource) {
            "memories" ->
                "length(CAST(text AS BLOB))+" +
                    "length(CAST(value_json AS BLOB))+" +
                    "length(CAST(temporal_json AS BLOB))" to "memories"
            "evidence" -> "length(CAST(quote AS BLOB))" to "evidence"
            "messages" ->
                "COALESCE(length(CAST(body AS BLOB)),0)+" +
                    "length(CAST(origin_json AS BLOB))" to "messages"
            "sessions" ->
                "length(CAST(working_summary AS BLOB))+" +
                    "length(CAST(working_sources AS BLOB))" to "sessions"
            "dependencies", "outbox" -> "0" to resource
            else -> return 0 to 0L
        }
        return db.rawQuery(
            "SELECT COUNT(*),COALESCE(SUM($expression+" +
                "${config.rowOverheadBytes}),0) FROM $table WHERE scope_id=?",
            arrayOf(scopeId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use 0 to 0L
            }
            cursor.getInt(0) to cursor.getLong(1)
        }
    }

    private fun hasMigrationReceipt(
        db: SQLiteDatabase,
        runId: String,
        itemKey: String,
    ): Boolean {
        return db.rawQuery(
            "SELECT 1 FROM migration_items WHERE run_id=? AND item_key=?",
            arrayOf(runId, itemKey),
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    private fun assertMigrationScopeWritable(
        db: SQLiteDatabase,
        scopeId: String,
    ) {
        val state = db.rawQuery(
            "SELECT state,deletion_epoch FROM scopes WHERE scope_id=?",
            arrayOf(scopeId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            cursor.getString(0) to cursor.getInt(1)
        }
        if (state.first != "active" || state.second > 0) {
            throw MemoryV2Exception(MemoryV2ErrorCode.LEGACY_DELETION_BARRIER)
        }
    }

    private fun migrationReceiptCounts(
        db: SQLiteDatabase,
        runId: String,
    ): MigrationReceiptCounts {
        var imported = 0
        var quarantined = 0
        var expired = 0
        var blocked = 0
        db.rawQuery(
            "SELECT state,COUNT(*) FROM migration_items " +
                "WHERE run_id=? GROUP BY state",
            arrayOf(runId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val count = cursor.getInt(1)
                when (cursor.getString(0)) {
                    "imported" -> imported = count
                    "quarantined" -> quarantined = count
                    "expired" -> expired = count
                    "blocked" -> blocked = count
                }
            }
        }
        return MigrationReceiptCounts(
            imported = imported,
            quarantined = quarantined,
            expired = expired,
            blocked = blocked,
        )
    }

    private fun markMigrationRunFailed(
        db: SQLiteDatabase,
        runId: String,
    ) {
        db.update(
            "migration_runs",
            ContentValues().apply { put("state", "failed") },
            "run_id=?",
            arrayOf(runId),
        )
    }

    private fun markMigrationRunCommitted(
        db: SQLiteDatabase,
        runId: String,
    ) {
        db.update(
            "migration_runs",
            ContentValues().apply { put("state", "committed") },
            "run_id=?",
            arrayOf(runId),
        )
    }

    private fun recordMigrationItem(
        db: SQLiteDatabase,
        runId: String,
        item: LegacyMigrationItem,
        state: String,
        reason: String?,
        scopeId: String?,
        targetId: String? = null,
    ) {
        db.insertOrThrow(
            "migration_items",
            null,
            ContentValues().apply {
                put("run_id", runId)
                put("item_key", item.legacyId)
                if (scopeId == null) putNull("scope_id") else put(
                    "scope_id",
                    scopeId,
                )
                put("state", state)
                if (reason == null) putNull("reason") else put("reason", reason)
                if (targetId == null) putNull("target_id") else put(
                    "target_id",
                    targetId,
                )
                if (item.sourceReceivedAt == null) {
                    putNull("source_received_at")
                } else {
                    put("source_received_at", item.sourceReceivedAt / 1_000L)
                }
            },
        )
    }

    private data class MigrationReceiptCounts(
        val imported: Int,
        val quarantined: Int,
        val expired: Int,
        val blocked: Int,
    )

    private fun insertDependency(
        db: SQLiteDatabase,
        scopeId: String,
        messageId: String,
        memoryId: String,
    ) {
        db.insertWithOnConflict(
            "dependencies",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put(
                    "dependency_id",
                    sha256("$scopeId|message|$messageId|memory|$memoryId"),
                )
                put("parent_kind", "message")
                put("parent_id", messageId)
                put("child_kind", "memory")
                put("child_id", memoryId)
                put("source_revision", 1)
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
        db.insertWithOnConflict(
            "outbox",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put("event_id", "index-${sha256("$scopeId|$memoryId")}")
                put("kind", "index_sync")
                put("target_id", memoryId)
                put("target_version", version)
                put("deletion_epoch", 0)
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

    private fun hashLegacyRoot(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray())
                digest.update(0)
                digest.update(file.readBytes())
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String {
        return MemoryV2Crypto.sha256(value)
    }

    private fun jsonString(value: String): String {
        return JSONArray().put(value).toString()
            .removePrefix("[")
            .removeSuffix("]")
    }
}
