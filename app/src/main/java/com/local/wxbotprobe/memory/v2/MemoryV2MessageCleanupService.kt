package com.local.wxbotprobe.memory.v2

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class MemoryV2CleanupResult(
    val scheduled: Int = 0,
    val redacted: Int = 0,
    val purged: Int = 0,
    val deferred: Int = 0,
) {
    val changed: Boolean
        get() = scheduled > 0 || redacted > 0 || purged > 0 || deferred > 0
}

class MemoryV2MessageCleanupService(
    context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val bodyRetention: MemoryMessageBodyRetention =
        MemoryCleanupSettings.bodyRetention(context),
) {
    private val config = MemoryV2ConfigLoader.load(context)

    fun runOnce(limit: Int = 100): MemoryV2CleanupResult {
        val db = database.writableDatabase
        val touchedScopes = linkedSetOf<String>()
        db.beginTransaction()
        try {
            val scheduled = scheduleEligible(db, limit.coerceIn(1, 500))
            val redacted = redactDue(db, limit.coerceIn(1, 500), touchedScopes)
            val purged = purgeReferenceFree(
                db,
                limit.coerceIn(1, 500),
                touchedScopes,
            )
            val deferred = deferredCount(db)
            touchedScopes.forEach { refreshMessageLedger(db, it) }
            db.setTransactionSuccessful()
            return MemoryV2CleanupResult(
                scheduled = scheduled,
                redacted = redacted,
                purged = purged,
                deferred = deferred,
            )
        } finally {
            db.endTransaction()
        }
    }

    private fun scheduleEligible(db: SQLiteDatabase, limit: Int): Int {
        val current = now()
        val candidates = mutableListOf<CleanupCandidate>()
        db.rawQuery(
            "SELECT m.scope_id,m.message_id,m.session_id,m.client_message_id,m.seq " +
                "FROM messages m LEFT JOIN message_cleanup c " +
                "ON c.scope_id=m.scope_id AND c.message_id=m.message_id " +
                "WHERE m.state='live' AND m.body IS NOT NULL AND c.message_id IS NULL " +
                "AND m.extraction_state IN ('done','skipped','gap') " +
                "AND m.summary_state IN ('done','gap') " +
                "AND NOT EXISTS (" +
                "SELECT 1 FROM dependencies d JOIN jobs j " +
                "ON j.scope_id=d.scope_id AND j.job_id=d.child_id " +
                "WHERE d.scope_id=m.scope_id AND d.parent_kind='message' " +
                "AND d.parent_id=m.message_id AND d.child_kind='job' " +
                "AND j.state IN ('pending','leased')) " +
                "ORDER BY m.received_at,m.message_id LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                candidates += CleanupCandidate(
                    scopeId = cursor.getString(0),
                    messageId = cursor.getString(1),
                    sessionId = cursor.getString(2),
                    clientMessageId = cursor.getString(3),
                    seq = cursor.getInt(4),
                )
            }
        }
        candidates.forEach { candidate ->
            db.insertWithOnConflict(
                "message_cleanup",
                null,
                ContentValues().apply {
                    put("scope_id", candidate.scopeId)
                    put("message_id", candidate.messageId)
                    put("session_id", candidate.sessionId)
                    put("client_message_id", candidate.clientMessageId)
                    put("seq", candidate.seq)
                    put("completed_at", current)
                    put(
                        "body_due_at",
                        MemoryV2CleanupPolicy.bodyDueAt(current, bodyRetention),
                    )
                    put(
                        "metadata_due_at",
                        MemoryV2CleanupPolicy.metadataDueAt(
                            current,
                            config.historySeconds,
                        ),
                    )
                    putNull("body_redacted_at")
                    put("state", "pending")
                    putNull("last_error")
                    put("created_at", current)
                    put("updated_at", current)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
        return candidates.size
    }

    private fun redactDue(
        db: SQLiteDatabase,
        limit: Int,
        touchedScopes: MutableSet<String>,
    ): Int {
        val current = now()
        val candidates = mutableListOf<RedactionCandidate>()
        db.rawQuery(
            "SELECT c.scope_id,c.message_id,c.session_id,c.client_message_id," +
                "m.revision,m.extraction_state,m.summary_state,m.body " +
                "FROM message_cleanup c JOIN messages m " +
                "ON m.scope_id=c.scope_id AND m.message_id=c.message_id " +
                "WHERE c.state='pending' AND c.body_due_at<=? " +
                "ORDER BY c.body_due_at,c.message_id LIMIT ?",
            arrayOf(current.toString(), limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                candidates += RedactionCandidate(
                    scopeId = cursor.getString(0),
                    messageId = cursor.getString(1),
                    revision = cursor.getInt(4),
                    extractionState = cursor.getString(5),
                    summaryState = cursor.getString(6),
                    hasBody = !cursor.isNull(7),
                )
            }
        }
        var redacted = 0
        candidates.forEach { candidate ->
            val activeJob = hasActiveJob(
                db,
                candidate.scopeId,
                candidate.messageId,
            )
            if (!MemoryV2CleanupPolicy.isFullyProcessed(
                    candidate.extractionState,
                    candidate.summaryState,
                    activeJob,
                )
            ) {
                db.update(
                    "message_cleanup",
                    ContentValues().apply {
                        put("body_due_at", current + 3_600L)
                        put("last_error", "TASKS_NOT_TERMINAL")
                        put("updated_at", current)
                    },
                    "scope_id=? AND message_id=? AND state='pending'",
                    arrayOf(candidate.scopeId, candidate.messageId),
                )
                return@forEach
            }
            if (candidate.hasBody) {
                db.execSQL(
                    "UPDATE messages SET body=NULL,state='expired'," +
                        "revision=revision+1 WHERE scope_id=? AND message_id=?",
                    arrayOf(candidate.scopeId, candidate.messageId),
                )
            }
            db.update(
                "message_cleanup",
                ContentValues().apply {
                    put("body_redacted_at", current)
                    put("state", "redacted")
                    putNull("last_error")
                    put("updated_at", current)
                },
                "scope_id=? AND message_id=? AND state='pending'",
                arrayOf(candidate.scopeId, candidate.messageId),
            )
            insertAudit(
                db,
                candidate.scopeId,
                candidate.messageId,
                candidate.revision,
                "message_body_redacted",
                current,
            )
            touchedScopes += candidate.scopeId
            redacted++
        }
        return redacted
    }

    private fun purgeReferenceFree(
        db: SQLiteDatabase,
        limit: Int,
        touchedScopes: MutableSet<String>,
    ): Int {
        val current = now()
        val candidates = mutableListOf<PurgeCandidate>()
        db.rawQuery(
            "SELECT c.scope_id,c.message_id,m.revision," +
                "(SELECT COUNT(*) FROM evidence e WHERE e.scope_id=c.scope_id " +
                "AND e.source_message_id=c.message_id)," +
                "(SELECT COUNT(*) FROM dependencies d WHERE d.scope_id=c.scope_id " +
                "AND d.parent_kind='message' AND d.parent_id=c.message_id)," +
                "(SELECT COUNT(*) FROM scheduled_tasks s " +
                "WHERE s.scope_id=c.scope_id " +
                "AND s.authorization_message_id=c.message_id) " +
                "FROM message_cleanup c JOIN messages m " +
                "ON m.scope_id=c.scope_id AND m.message_id=c.message_id " +
                "WHERE c.state='redacted' AND c.metadata_due_at<=? " +
                "ORDER BY c.metadata_due_at,c.message_id LIMIT ?",
            arrayOf(current.toString(), limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                candidates += PurgeCandidate(
                    scopeId = cursor.getString(0),
                    messageId = cursor.getString(1),
                    revision = cursor.getInt(2),
                    references = cursor.getInt(3) +
                        cursor.getInt(4) +
                        cursor.getInt(5),
                )
            }
        }
        var purged = 0
        candidates.forEach { candidate ->
            if (candidate.references > 0) {
                db.update(
                    "message_cleanup",
                    ContentValues().apply {
                        put("metadata_due_at", current + 86_400L)
                        put("last_error", "REFERENCES_PRESENT")
                        put("updated_at", current)
                    },
                    "scope_id=? AND message_id=? AND state='redacted'",
                    arrayOf(candidate.scopeId, candidate.messageId),
                )
                return@forEach
            }
            insertAudit(
                db,
                candidate.scopeId,
                candidate.messageId,
                candidate.revision,
                "message_metadata_purged",
                current,
            )
            db.delete(
                "coverage",
                "scope_id=? AND message_id=?",
                arrayOf(candidate.scopeId, candidate.messageId),
            )
            db.delete(
                "messages",
                "scope_id=? AND message_id=?",
                arrayOf(candidate.scopeId, candidate.messageId),
            )
            db.update(
                "message_cleanup",
                ContentValues().apply {
                    put("state", "purged")
                    putNull("last_error")
                    put("updated_at", current)
                },
                "scope_id=? AND message_id=?",
                arrayOf(candidate.scopeId, candidate.messageId),
            )
            touchedScopes += candidate.scopeId
            purged++
        }
        return purged
    }

    private fun hasActiveJob(
        db: SQLiteDatabase,
        scopeId: String,
        messageId: String,
    ): Boolean {
        return db.rawQuery(
            "SELECT COUNT(*) FROM dependencies d JOIN jobs j " +
                "ON j.scope_id=d.scope_id AND j.job_id=d.child_id " +
                "WHERE d.scope_id=? AND d.parent_kind='message' " +
                "AND d.parent_id=? AND d.child_kind='job' " +
                "AND j.state IN ('pending','leased')",
            arrayOf(scopeId, messageId),
        ).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) > 0
        }
    }

    private fun deferredCount(db: SQLiteDatabase): Int {
        return db.rawQuery(
            "SELECT COUNT(*) FROM message_cleanup WHERE state='pending' " +
                "AND body_due_at<=? AND last_error='TASKS_NOT_TERMINAL'",
            arrayOf(now().toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun insertAudit(
        db: SQLiteDatabase,
        scopeId: String,
        messageId: String,
        fromRevision: Int,
        action: String,
        current: Long,
    ) {
        val deletionEpoch = db.rawQuery(
            "SELECT deletion_epoch FROM scopes WHERE scope_id=?",
            arrayOf(scopeId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        db.insert(
            "audit_events",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put("audit_id", UUID.randomUUID().toString())
                put("operation_id", "message-cleanup")
                put("action", action)
                put("target_id", messageId)
                put("from_version", fromRevision)
                put("to_version", fromRevision + 1)
                put("result_code", "OK")
                put("deletion_epoch", deletionEpoch)
                put("created_at", current)
                put("expires_at", current + config.historySeconds)
            },
        )
    }

    private fun refreshMessageLedger(db: SQLiteDatabase, scopeId: String) {
        val bytes = db.rawQuery(
            "SELECT COUNT(*),COALESCE(SUM(COALESCE(length(CAST(body AS BLOB)),0)+" +
                "length(CAST(origin_json AS BLOB))+?),0) " +
                "FROM messages WHERE scope_id=?",
            arrayOf(config.rowOverheadBytes.toString(), scopeId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return
            }
            cursor.getInt(0) to cursor.getLong(1)
        }
        db.insertWithOnConflict(
            "capacity_ledger",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put("resource", "messages")
                put("used_records", bytes.first)
                put("used_bytes", bytes.second)
                put("reservation_bytes", 0)
                put("accounting_version", 1)
                put("last_tx_id", UUID.randomUUID().toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private data class CleanupCandidate(
        val scopeId: String,
        val messageId: String,
        val sessionId: String,
        val clientMessageId: String,
        val seq: Int,
    )

    private data class RedactionCandidate(
        val scopeId: String,
        val messageId: String,
        val revision: Int,
        val extractionState: String,
        val summaryState: String,
        val hasBody: Boolean,
    )

    private data class PurgeCandidate(
        val scopeId: String,
        val messageId: String,
        val revision: Int,
        val references: Int,
    )

    companion object {
        private val executor = Executors.newSingleThreadExecutor {
            Thread(it, "memory-v2-cleanup").apply { isDaemon = true }
        }
        private val scheduled = AtomicBoolean(false)

        fun kick(context: Context) {
            if (!scheduled.compareAndSet(false, true)) {
                return
            }
            val appContext = context.applicationContext
            executor.execute {
                try {
                    MemoryV2MessageCleanupService(appContext).runOnce()
                } finally {
                    scheduled.set(false)
                }
            }
        }
    }
}
