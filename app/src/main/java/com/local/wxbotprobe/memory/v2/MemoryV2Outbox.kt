package com.local.wxbotprobe.memory.v2

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class MemoryV2OutboxEvent(
    val scopeId: String,
    val eventId: String,
    val kind: String,
    val targetId: String,
    val targetVersion: Int,
    val deletionEpoch: Int,
    val attempts: Int,
    val createdAt: Long,
)

fun interface MemoryV2OutboxProcessor {
    fun process(event: MemoryV2OutboxEvent)
}

object SqlRetrievalOutboxProcessor : MemoryV2OutboxProcessor {
    override fun process(event: MemoryV2OutboxEvent) {
        // Retrieval reads the live SQL tables directly, so no external index write is needed.
    }
}

object MemoryV2OutboxPolicy {
    const val MAX_ATTEMPTS = 5

    fun backoffMillis(failedAttempts: Int): Long {
        val exponent = (failedAttempts - 1).coerceIn(0, 3)
        return minOf(60_000L, 5_000L * (1L shl exponent))
    }

    fun exhausted(failedAttempts: Int): Boolean {
        return failedAttempts >= MAX_ATTEMPTS
    }
}

class MemoryV2OutboxWorker(
    private val context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val processor: MemoryV2OutboxProcessor = SqlRetrievalOutboxProcessor,
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val leaseSeconds: Long = 60L,
) {
    private val config = MemoryV2ConfigLoader.load(context)

    fun runOnce(owner: String = "memory-v2-outbox"): Boolean {
        val event = lease(owner) ?: return false
        return try {
            processor.process(event)
            acknowledge(event, owner)
            true
        } catch (error: MemoryV2Exception) {
            fail(event, owner, error.code)
            true
        } catch (_: Throwable) {
            fail(
                event,
                owner,
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE,
            )
            true
        }
    }

    fun reconcileExpiredLeases(): Int {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val reconciled = reconcileExpiredLeases(db, now())
            db.setTransactionSuccessful()
            return reconciled
        } finally {
            db.endTransaction()
        }
    }

    private fun lease(owner: String): MemoryV2OutboxEvent? {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val leasedAt = now()
            reconcileExpiredLeases(db, leasedAt)
            val event = db.query(
                "outbox",
                arrayOf(
                    "scope_id",
                    "event_id",
                    "kind",
                    "target_id",
                    "target_version",
                    "deletion_epoch",
                    "attempts",
                    "created_at",
                ),
                "state='pending' AND next_attempt_at<=?",
                arrayOf(leasedAt.toString()),
                null,
                null,
                "next_attempt_at,created_at",
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    MemoryV2OutboxEvent(
                        scopeId = cursor.string("scope_id"),
                        eventId = cursor.string("event_id"),
                        kind = cursor.string("kind"),
                        targetId = cursor.string("target_id"),
                        targetVersion = cursor.int("target_version"),
                        deletionEpoch = cursor.int("deletion_epoch"),
                        attempts = cursor.int("attempts"),
                        createdAt = cursor.long("created_at"),
                    )
                }
            } ?: run {
                db.setTransactionSuccessful()
                return null
            }
            val updated = db.update(
                "outbox",
                ContentValues().apply {
                    put("state", "leased")
                    put("lease_owner", owner)
                    put("lease_until", leasedAt + leaseSeconds)
                },
                "scope_id=? AND event_id=? AND state='pending'",
                arrayOf(event.scopeId, event.eventId),
            )
            if (updated != 1) {
                db.setTransactionSuccessful()
                return null
            }
            db.setTransactionSuccessful()
            return event
        } finally {
            db.endTransaction()
        }
    }

    private fun acknowledge(
        event: MemoryV2OutboxEvent,
        owner: String,
    ) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val deleted = db.delete(
                "outbox",
                "scope_id=? AND event_id=? AND state='leased' AND lease_owner=?",
                arrayOf(event.scopeId, event.eventId, owner),
            )
            if (deleted != 1) {
                throw MemoryV2Exception(
                    MemoryV2ErrorCode.STORAGE_UNAVAILABLE,
                    "outbox lease was lost before acknowledgement",
                )
            }
            refreshLedger(db, event.scopeId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun fail(
        event: MemoryV2OutboxEvent,
        owner: String,
        code: MemoryV2ErrorCode,
    ) {
        val failedAttempts = event.attempts + 1
        val exhausted = MemoryV2OutboxPolicy.exhausted(failedAttempts)
        val failedAt = now()
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val updated = db.update(
                "outbox",
                ContentValues().apply {
                    put("state", if (exhausted) "failed" else "pending")
                    put("attempts", failedAttempts)
                    putNull("lease_owner")
                    putNull("lease_until")
                    put(
                        "next_attempt_at",
                        if (exhausted) {
                            0L
                        } else {
                            failedAt + MemoryV2OutboxPolicy.backoffMillis(failedAttempts)
                        },
                    )
                    put("error_code", code.name)
                },
                "scope_id=? AND event_id=? AND state='leased' AND lease_owner=?",
                arrayOf(event.scopeId, event.eventId, owner),
            )
            if (updated != 1) {
                throw MemoryV2Exception(
                    MemoryV2ErrorCode.STORAGE_UNAVAILABLE,
                    "outbox lease was lost before failure acknowledgement",
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun reconcileExpiredLeases(
        db: SQLiteDatabase,
        reconciledAt: Long,
    ): Int {
        db.execSQL(
            "UPDATE outbox SET " +
                "attempts=attempts+1, " +
                "state=CASE WHEN attempts+1>=? THEN 'failed' ELSE 'pending' END, " +
                "lease_owner=NULL, lease_until=NULL, " +
                "next_attempt_at=?, error_code=? " +
                "WHERE state='leased' AND lease_until<=?",
            arrayOf(
                MemoryV2OutboxPolicy.MAX_ATTEMPTS,
                reconciledAt,
                MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name,
                reconciledAt,
            ),
        )
        return db.rawQuery(
            "SELECT changes()",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun refreshLedger(
        db: SQLiteDatabase,
        scopeId: String,
    ) {
        val (records, bytes) = db.rawQuery(
            "SELECT COUNT(*), " +
                "COALESCE(SUM(${config.rowOverheadBytes}),0) " +
                "FROM outbox WHERE scope_id=?",
            arrayOf(scopeId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0) to cursor.getLong(1)
        }
        db.insertWithOnConflict(
            "capacity_ledger",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put("resource", "outbox")
                put("used_bytes", bytes)
                put("used_records", records)
                put("reservation_bytes", 0)
                put("accounting_version", 1)
                put("last_tx_id", UUID.randomUUID().toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
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

    companion object {
        private val executor = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "memory-v2-outbox").apply { isDaemon = true }
        }
        private val scheduled = AtomicBoolean(false)

        fun kick(context: Context) {
            if (!scheduled.compareAndSet(false, true)) {
                return
            }
            val appContext = context.applicationContext
            executor.schedule(
                {
                    try {
                        val worker = MemoryV2OutboxWorker(appContext)
                        var processed = 0
                        while (processed < 8 && worker.runOnce("memory-v2-outbox-kick")) {
                            processed++
                        }
                    } finally {
                        scheduled.set(false)
                    }
                },
                0L,
                TimeUnit.MILLISECONDS,
            )
        }
    }
}
