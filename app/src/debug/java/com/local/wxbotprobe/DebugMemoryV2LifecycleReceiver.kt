package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import com.local.wxbotprobe.memory.v2.AppendMessageRequest
import com.local.wxbotprobe.memory.v2.MemoryScopeKey
import com.local.wxbotprobe.memory.v2.MemoryV2Database
import com.local.wxbotprobe.memory.v2.MemoryV2JobService
import com.local.wxbotprobe.memory.v2.MemoryV2Repository
import com.local.wxbotprobe.memory.v2.ResolvedScopeInput
import java.io.File
import org.json.JSONObject

class DebugMemoryV2LifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val phase = intent?.getStringExtra(EXTRA_PHASE).orEmpty()
        Thread(
            {
                val result = runCatching {
                    when (phase) {
                        PHASE_PREPARE -> prepare(context.applicationContext)
                        PHASE_VERIFY -> verify(context.applicationContext)
                        PHASE_CLEANUP -> cleanup(context.applicationContext)
                        else -> error("unknown lifecycle phase: $phase")
                    }
                }
                val output = File(context.filesDir, OUTPUT_PATH)
                output.parentFile?.mkdirs()
                output.writeText(
                    result.fold(
                        onSuccess = { it.toString(2) },
                        onFailure = {
                            JSONObject()
                                .put("status", "failed")
                                .put("phase", phase)
                                .put("error", it.message.orEmpty())
                                .put("stack", it.stackTraceToString())
                                .toString(2)
                        },
                    ),
                    Charsets.UTF_8,
                )
                android.util.Log.i("MemoryV2Lifecycle", output.readText())
                pending.finish()
            },
            "memory-v2-lifecycle",
        ).start()
    }

    private fun prepare(context: Context): JSONObject {
        deleteDatabaseFiles(context)
        val database = MemoryV2Database(context, DATABASE_NAME)
        return try {
            val nowSeconds = System.currentTimeMillis() / 1_000L
            val repository = MemoryV2Repository(
                context = context,
                database = database,
                now = { nowSeconds },
            )
            val scope = repository.ensureScope(
                resolved = TEST_SCOPE,
                contactDisplayName = "Lifecycle test",
            )
            val first = repository.appendMessage(
                scope = scope,
                request = AppendMessageRequest(
                    clientMessageId = FIRST_CLIENT_ID,
                    sessionId = null,
                    role = "user",
                    text = "process-kill lifecycle fixture",
                    occurredAt = nowSeconds,
                    receivedAt = nowSeconds,
                    timezone = "Asia/Shanghai",
                ),
            )
            val jobs = MemoryV2JobService(
                context = context,
                database = database,
                now = { System.currentTimeMillis() / 1_000L },
            )
            val enqueued = jobs.enqueue(
                scope = scope,
                sessionId = first.sessionId,
                kind = "extract",
                sourceIds = listOf(first.messageId),
                jobId = JOB_ID,
            )
            val leased = jobs.lease(JOB_OWNER, limit = 1).single()
            val session = sessionState(database, scope.scopeId, first.sessionId)
            val job = jobState(database, scope.scopeId, JOB_ID)
            JSONObject()
                .put("status", "prepared")
                .put("phase", PHASE_PREPARE)
                .put("database", DATABASE_NAME)
                .put("scope_id", scope.scopeId)
                .put("session_id", first.sessionId)
                .put("message_id", first.messageId)
                .put("message_seq", first.seq)
                .put("job_id", enqueued.jobId)
                .put("lease_owner", JOB_OWNER)
                .put("lease_attempts", leased.attempts)
                .put("lease_until", job.get("lease_until"))
                .put("session", session)
                .put("job", job)
                .put("integrity", integrity(database))
        } finally {
            database.close()
        }
    }

    private fun verify(context: Context): JSONObject {
        val database = MemoryV2Database(context, DATABASE_NAME)
        return try {
            val db = database.writableDatabase
            val scopeId = queryString(
                db,
                "SELECT scope_id FROM scopes WHERE contact_id=? LIMIT 1",
                arrayOf(CONTACT_ID),
            ) ?: error("lifecycle scope is missing")
            val sessionId = queryString(
                db,
                "SELECT session_id FROM sessions WHERE scope_id=? ORDER BY created_at LIMIT 1",
                arrayOf(scopeId),
            ) ?: error("lifecycle session is missing")
            val initial = JSONObject()
                .put("session", sessionState(database, scopeId, sessionId))
                .put("job", jobState(database, scopeId, JOB_ID))
                .put("message_count", queryInt(
                    db,
                    "SELECT COUNT(*) FROM messages WHERE scope_id=? AND session_id=?",
                    arrayOf(scopeId, sessionId),
                ))
                .put("integrity", integrity(database))

            val leaseUntil = queryLong(
                db,
                "SELECT lease_until FROM jobs WHERE scope_id=? AND job_id=?",
                arrayOf(scopeId, JOB_ID),
            ) ?: error("lifecycle job lease is missing")
            val remainingSeconds = (leaseUntil - System.currentTimeMillis() / 1_000L)
                .coerceAtLeast(0L)
            if (remainingSeconds > 0L) {
                return initial
                    .put("status", "waiting_for_lease")
                    .put("phase", PHASE_VERIFY)
                    .put("lease_remaining_seconds", remainingSeconds)
            }

            val scope = repositoryScope(scopeId)
            val repository = MemoryV2Repository(
                context = context,
                database = database,
                now = { System.currentTimeMillis() / 1_000L },
            )
            val jobs = MemoryV2JobService(
                context = context,
                database = database,
            )
            val recovered = repository.recoverOpenSession(scope)
                ?: error("open session was not recovered")
            if (recovered.session.sessionId != sessionId) {
                error("recovered the wrong session")
            }
            val reclaimed = jobs.lease(RECOVERY_OWNER, limit = 1).singleOrNull()
                ?: error("expired job lease was not reclaimed")
            if (reclaimed.jobId != JOB_ID || reclaimed.attempts != 2) {
                error("unexpected reclaimed job ${reclaimed.jobId}/${reclaimed.attempts}")
            }
            val nowSeconds = System.currentTimeMillis() / 1_000L
            val second = repository.appendMessage(
                scope = scope,
                request = AppendMessageRequest(
                    clientMessageId = SECOND_CLIENT_ID,
                    sessionId = recovered.session.sessionId,
                    role = "assistant",
                    text = "continued after process restart",
                    occurredAt = nowSeconds,
                    receivedAt = nowSeconds,
                    timezone = "Asia/Shanghai",
                ),
            )
            if (second.seq != 2 || second.sessionId != sessionId) {
                error("sequence did not continue after recovery")
            }
            val replay = repository.appendMessage(
                scope = scope,
                request = AppendMessageRequest(
                    clientMessageId = SECOND_CLIENT_ID,
                    sessionId = sessionId,
                    role = "assistant",
                    text = "continued after process restart",
                    occurredAt = nowSeconds,
                    receivedAt = nowSeconds,
                    timezone = "Asia/Shanghai",
                ),
            )
            if (replay.status != "unchanged" || replay.seq != second.seq) {
                error("idempotent replay changed the message")
            }
            val closed = repository.closeSession(scope, sessionId, nowSeconds)
            if (!closed) {
                error("session close failed")
            }
            val finalIntegrity = integrity(database)
            if (finalIntegrity.getString("quick_check") != "ok") {
                error("database integrity check failed")
            }
            JSONObject()
                .put("status", "passed")
                .put("phase", PHASE_VERIFY)
                .put("scope_id", scopeId)
                .put("session_id", sessionId)
                .put("initial", initial)
                .put("recovered_session_id", recovered.session.sessionId)
                .put("recovered_next_seq", recovered.session.nextSeq)
                .put("closed_duplicates", recovered.closedDuplicates)
                .put("reclaimed_job_id", reclaimed.jobId)
                .put("reclaimed_attempts", reclaimed.attempts)
                .put("appended_seq", second.seq)
                .put("replay_status", replay.status)
                .put("final", JSONObject()
                    .put("session", sessionState(database, scopeId, sessionId))
                    .put("job", jobState(database, scopeId, JOB_ID))
                    .put("message_count", queryInt(
                        db,
                        "SELECT COUNT(*) FROM messages WHERE scope_id=? AND session_id=?",
                        arrayOf(scopeId, sessionId),
                    ))
                    .put("integrity", finalIntegrity))
        } finally {
            database.close()
        }
    }

    private fun cleanup(context: Context): JSONObject {
        val deleted = deleteDatabaseFiles(context)
        File(context.filesDir, OUTPUT_PATH).delete()
        return JSONObject()
            .put("status", "cleaned")
            .put("phase", PHASE_CLEANUP)
            .put("deleted", deleted)
    }

    private fun repositoryScope(scopeId: String): com.local.wxbotprobe.memory.v2.TrustedScopeContext {
        return com.local.wxbotprobe.memory.v2.TrustedScopeContext(
            scopeId = scopeId,
            key = TEST_SCOPE.key,
            personaRevision = TEST_SCOPE.personaRevision,
            revision = 0,
            deletionEpoch = 0,
            importGeneration = 1,
            retentionPolicy = "independent_evidence",
            state = "active",
            restoreGuardId = "lifecycle-restore-guard",
        )
    }

    private fun sessionState(
        database: MemoryV2Database,
        scopeId: String,
        sessionId: String,
    ): JSONObject {
        return database.readableDatabase.query(
            "sessions",
            arrayOf("state", "next_seq", "updated_at"),
            "scope_id=? AND session_id=?",
            arrayOf(scopeId, sessionId),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                error("session row is missing")
            }
            JSONObject()
                .put("state", cursor.getString(cursor.getColumnIndexOrThrow("state")))
                .put("next_seq", cursor.getInt(cursor.getColumnIndexOrThrow("next_seq")))
                .put("updated_at", cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
        }
    }

    private fun jobState(
        database: MemoryV2Database,
        scopeId: String,
        jobId: String,
    ): JSONObject {
        return database.readableDatabase.query(
            "jobs",
            arrayOf("state", "attempts", "lease_owner", "lease_until", "next_attempt_at"),
            "scope_id=? AND job_id=?",
            arrayOf(scopeId, jobId),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                error("job row is missing")
            }
            JSONObject()
                .put("state", cursor.getString(cursor.getColumnIndexOrThrow("state")))
                .put("attempts", cursor.getInt(cursor.getColumnIndexOrThrow("attempts")))
                .put("lease_owner", cursor.getStringOrNull("lease_owner"))
                .put("lease_until", cursor.getLongOrNull("lease_until"))
                .put("next_attempt_at", cursor.getLong(cursor.getColumnIndexOrThrow("next_attempt_at")))
        }
    }

    private fun integrity(database: MemoryV2Database): JSONObject {
        val db = database.readableDatabase
        val quickCheck = queryString(db, "PRAGMA quick_check", emptyArray())
            ?: "no_result"
        val foreignKeyViolations = queryInt(db, "PRAGMA foreign_key_check", emptyArray())
        return JSONObject()
            .put("quick_check", quickCheck)
            .put("foreign_key_violations", foreignKeyViolations)
            .put("journal_mode", queryString(db, "PRAGMA journal_mode", emptyArray()))
    }

    private fun queryString(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): String? {
        return db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun queryInt(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): Int {
        return db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun queryLong(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): Long? {
        return db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun deleteDatabaseFiles(context: Context): Boolean {
        val deletedDatabase = context.deleteDatabase(DATABASE_NAME)
        val deleteJournal = File(context.applicationInfo.dataDir, "databases/$DATABASE_NAME-wal")
            .delete()
        val deleteSharedMemory = File(
            context.applicationInfo.dataDir,
            "databases/$DATABASE_NAME-shm",
        ).delete()
        return deletedDatabase || deleteJournal || deleteSharedMemory
    }

    companion object {
        const val ACTION = "com.local.wxbotprobe.DEBUG_MEMORY_V2_LIFECYCLE"
        const val EXTRA_PHASE = "phase"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_VERIFY = "verify"
        const val PHASE_CLEANUP = "cleanup"
        private const val DATABASE_NAME = "memory-v2-lifecycle.db"
        private const val OUTPUT_PATH = "debug/memory-v2-lifecycle.json"
        private const val CONTACT_ID = "lifecycle-contact"
        private const val FIRST_CLIENT_ID = "lifecycle-message-1"
        private const val SECOND_CLIENT_ID = "lifecycle-message-2"
        private const val JOB_ID = "lifecycle-job-1"
        private const val JOB_OWNER = "lifecycle-owner-before-kill"
        private const val RECOVERY_OWNER = "lifecycle-owner-after-restart"
        private val TEST_SCOPE = ResolvedScopeInput(
            key = MemoryScopeKey(
                ownerId = "lifecycle-owner",
                contactId = CONTACT_ID,
                agentId = "lifecycle-agent",
                personaId = "lifecycle-persona",
                realmId = "real",
            ),
            personaRevision = 1,
        )
    }
}
