package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import com.local.wxbotprobe.chat.ChatIdentity
import com.local.wxbotprobe.loop.MemoryCoordinator
import com.local.wxbotprobe.memory.v2.MemoryScopeKey
import com.local.wxbotprobe.memory.v2.MemoryV2Database
import com.local.wxbotprobe.memory.v2.MemoryV2ErrorCode
import com.local.wxbotprobe.memory.v2.MemoryV2Exception
import com.local.wxbotprobe.memory.v2.MemoryV2JobService
import com.local.wxbotprobe.memory.v2.TrustedScopeContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONObject

class DebugMemoryV2CoordinatorLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val phase = intent?.getStringExtra(EXTRA_PHASE).orEmpty()
        Thread(
            {
                val result = runCatching {
                    when (phase) {
                        PHASE_PREPARE -> prepare(context.applicationContext)
                        PHASE_VERIFY -> verify(context.applicationContext)
                        PHASE_STORAGE_PRESSURE -> storagePressure(context.applicationContext)
                        PHASE_PHYSICAL_DISK_PRESSURE ->
                            physicalDiskPressure(context.applicationContext)
                        PHASE_RESTORE_FENCE -> restoreFence(context.applicationContext)
                        PHASE_CLEANUP -> cleanup(context.applicationContext)
                        else -> error("unknown coordinator lifecycle phase: $phase")
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
                android.util.Log.i("MemoryV2CoordinatorLifecycle", output.readText())
                pending.finish()
            },
            "memory-v2-coordinator-lifecycle",
        ).start()
    }

    private fun prepare(context: Context): JSONObject {
        deleteDatabaseFiles(context)
        val logs = mutableListOf<String>()
        val nowMillis = System.currentTimeMillis()
        val coordinator = coordinator(context, logs)
        coordinator.open(IDENTITY, nowMillis)
        coordinator.recordIncoming("incoming before coordinator kill", nowMillis + 1_000L)
        coordinator.recordDeliveredParts(
            listOf("assistant before coordinator kill"),
            nowMillis + 2_000L,
        )
        val database = MemoryV2Database(context, DATABASE_NAME)
        return try {
            val session = sessionState(database)
            if (session.getString("state") != "open") {
                error("prepare did not leave an open coordinator session")
            }
            JSONObject()
                .put("status", "prepared")
                .put("phase", PHASE_PREPARE)
                .put("database", DATABASE_NAME)
                .put("chat_id", coordinator.chatId)
                .put("session_id", session.getString("session_id"))
                .put("session", session)
                .put("message_count", messageCount(database))
                .put("job_count", jobCount(database))
                .put("integrity", integrity(database))
                .put("logs", logs)
        } finally {
            database.close()
        }
    }

    private fun verify(context: Context): JSONObject {
        val logs = mutableListOf<String>()
        val database = MemoryV2Database(context, DATABASE_NAME)
        val beforeOpen = sessionState(database)
        if (beforeOpen.getString("state") != "open") {
            error("killed coordinator session was not left open for recovery")
        }
        val expectedSessionId = beforeOpen.getString("session_id")
        val coordinator = coordinator(context, logs)
        val nowMillis = System.currentTimeMillis()
        coordinator.open(IDENTITY, nowMillis)
        val afterOpen = sessionState(database)
        if (afterOpen.getString("session_id") != expectedSessionId) {
            error("coordinator recovery bound a different session")
        }
        if (logs.none { it.contains("memory v2 session recovered") }) {
            error("coordinator did not report open-session recovery")
        }
        if (afterOpen.getInt("next_seq") != 3) {
            error("recovered coordinator next_seq was not 3")
        }

        coordinator.recordIncoming("incoming after coordinator restart", nowMillis + 1_000L)
        coordinator.closeAndRecover(nowMillis + 2_000L)
        val finalSession = sessionState(database)
        if (finalSession.getString("session_id") != expectedSessionId) {
            error("coordinator close moved to a different session")
        }
        if (finalSession.getString("state") != "closed") {
            error("coordinator did not close the recovered session")
        }
        if (finalSession.getInt("next_seq") != 4) {
            error("coordinator did not continue sequence after restart")
        }
        if (sessionCount(database) != 1) {
            error("coordinator recovery created a duplicate session")
        }
        if (messageCount(database) != 3) {
            error("coordinator message count did not continue after restart")
        }
        val finalIntegrity = integrity(database)
        if (finalIntegrity.getString("quick_check") != "ok") {
            error("coordinator database quick_check failed")
        }
        if (finalIntegrity.getInt("foreign_key_violations") != 0) {
            error("coordinator database foreign key check failed")
        }
        coordinator.close()
        return JSONObject()
            .put("status", "passed")
            .put("phase", PHASE_VERIFY)
            .put("chat_id", coordinator.chatId)
            .put("recovered_session_id", expectedSessionId)
            .put("before_open", beforeOpen)
            .put("after_open", afterOpen)
            .put("final_session", finalSession)
            .put("session_count", sessionCount(database))
            .put("message_count", messageCount(database))
            .put("job_count", jobCount(database))
            .put("integrity", finalIntegrity)
            .put("logs", logs.takeLast(8))
    }

    private fun cleanup(context: Context): JSONObject {
        val deleted = deleteDatabaseFiles(context)
        File(context.filesDir, OUTPUT_PATH).delete()
        return JSONObject()
            .put("status", "cleaned")
            .put("phase", PHASE_CLEANUP)
            .put("deleted", deleted)
    }

    private fun restoreFence(context: Context): JSONObject {
        deleteDatabaseFiles(context)
        val logs = mutableListOf<String>()
        val nowMillis = System.currentTimeMillis()
        val database = MemoryV2Database(context, DATABASE_NAME)
        val coordinator = coordinator(context, logs, database)
        coordinator.open(IDENTITY, nowMillis)
        coordinator.recordIncoming("message before restore fence", nowMillis + 1_000L)
        val before = sessionState(database)
        val persistedGuard = queryString(
            database.readableDatabase,
            "SELECT restore_guard_id FROM scopes WHERE scope_id=?",
            arrayOf(before.getString("scope_id")),
        ) ?: error("persisted restore guard is missing")
        coordinator.close()
        database.writableDatabase.execSQL(
            "UPDATE scopes SET restore_guard_id=? WHERE scope_id=?",
            arrayOf(RESTORE_FENCE_FOREIGN_GUARD, before.getString("scope_id")),
        )
        database.close()

        val restoredDatabase = MemoryV2Database(context, DATABASE_NAME)
        val restored = coordinator(context, logs, restoredDatabase)
        restored.open(IDENTITY, nowMillis + 2_000L)
        restored.recordIncoming("must not append after restore fence", nowMillis + 3_000L)
        val after = sessionState(restoredDatabase)
        val afterMessageCount = messageCount(restoredDatabase)
        val afterSessionCount = sessionCount(restoredDatabase)
        val finalIntegrity = integrity(restoredDatabase)
        val boundChatId = restored.chatId
        restored.close()
        restoredDatabase.close()
        val deleted = deleteDatabaseFiles(context)
        if (boundChatId != null) {
            error("restore fence allowed the coordinator to bind a foreign scope")
        }
        if (logs.none { it.contains(MemoryV2ErrorCode.RESTORE_FENCE_MISMATCH.name) }) {
            error("restore fence rejection was not logged")
        }
        if (afterMessageCount != 1 || afterSessionCount != 1) {
            error("restore fence allowed a message or session to be created")
        }
        if (after.getInt("next_seq") != before.getInt("next_seq")) {
            error("restore fence changed the session sequence")
        }
        if (finalIntegrity.getString("quick_check") != "ok" ||
            finalIntegrity.getInt("foreign_key_violations") != 0) {
            error("restore fence database integrity check failed")
        }
        return JSONObject()
            .put("status", "passed")
            .put("phase", PHASE_RESTORE_FENCE)
            .put("persisted_guard_present", persistedGuard.isNotBlank())
            .put("foreign_guard_applied", true)
            .put("bound_chat_id", boundChatId ?: JSONObject.NULL)
            .put("before", before)
            .put("after", after)
            .put("message_count", afterMessageCount)
            .put("session_count", afterSessionCount)
            .put("integrity", finalIntegrity)
            .put("database_deleted", deleted)
            .put("logs", logs.takeLast(8))
    }

    private fun physicalDiskPressure(context: Context): JSONObject {
        deleteDatabaseFiles(context)
        val logs = mutableListOf<String>()
        val nowMillis = System.currentTimeMillis()
        val freeBefore = StatFs(context.filesDir.absolutePath).availableBytes
        val database = MemoryV2Database(context, DATABASE_NAME)
        val coordinator = coordinator(context, logs, database)
        coordinator.open(IDENTITY, nowMillis)
        coordinator.recordIncoming("message before physical disk pressure", nowMillis + 1_000L)
        val db = database.writableDatabase
        val before = sessionState(database)
        val scope = scopeContext(db, before.getString("scope_id"))
        val sourceId = queryString(
            db,
            "SELECT message_id FROM messages WHERE scope_id=? AND session_id=? " +
                "ORDER BY seq LIMIT 1",
            arrayOf(scope.scopeId, before.getString("session_id")),
        ) ?: error("physical-pressure source message is missing")
        MemoryV2JobService(context, database).enqueue(
            scope = scope,
            sessionId = before.getString("session_id"),
            kind = "extract",
            sourceIds = listOf(sourceId),
            jobId = PHYSICAL_PRESSURE_JOB_ID,
        )
        val jobsBefore = jobState(database, scope.scopeId, PHYSICAL_PRESSURE_JOB_ID)
        val pressureFill = File(
            context.filesDir,
            "pressure/physical-final.bin",
        )
        pressureFill.parentFile?.mkdirs()
        var onDeviceFillBytes = 0L
        var onDeviceFillError = ""
        try {
            FileOutputStream(pressureFill).use { output ->
                val chunk = ByteArray(1024 * 1024)
                val stat = StatFs(context.filesDir.absolutePath)
                while (stat.availableBytes > PHYSICAL_FINAL_RESERVE_BYTES) {
                    output.write(chunk)
                    output.flush()
                    onDeviceFillBytes += chunk.size
                    stat.restat(context.filesDir.absolutePath)
                }
            }
        } catch (error: IOException) {
            onDeviceFillError = error.message.orEmpty()
        }
        var successfulWrites = 0
        var sessionBeforeFailure = before
        var failureError = ""
        var failureCode = ""
        val pressureMessage = "d".repeat(PRESSURE_MESSAGE_BYTES)
        while (successfulWrites < MAX_PRESSURE_MESSAGES) {
            val failure = runCatching {
                coordinator.recordIncoming(
                    pressureMessage,
                    nowMillis + 2_000L + successfulWrites,
                )
            }.exceptionOrNull()
            if (failure == null) {
                successfulWrites++
                sessionBeforeFailure = sessionState(database)
                continue
            }
            failureError = failure.message.orEmpty()
            failureCode = (failure as? MemoryV2Exception)?.code?.name.orEmpty()
            break
        }
        val afterFailure = JSONObject()
            .put("session", sessionState(database))
            .put("message_count", messageCount(database))
            .put("jobs", jobState(database, scope.scopeId, PHYSICAL_PRESSURE_JOB_ID))
            .put("integrity", integrity(database))
        val finalIntegrity = afterFailure.getJSONObject("integrity")
        val finalMessageCount = afterFailure.getInt("message_count")
        val freeAtPeak = StatFs(context.filesDir.absolutePath).availableBytes
        val fillDeleted = pressureFill.delete()
        coordinator.close()
        database.close()
        val freeAfter = StatFs(context.filesDir.absolutePath).availableBytes
        val deleted = deleteDatabaseFiles(context)
        if (failureCode != MemoryV2ErrorCode.STORAGE_UNAVAILABLE.name) {
            error(
                "physical disk pressure did not reach STORAGE_UNAVAILABLE " +
                    "writes=$successfulWrites code=$failureCode error=$failureError " +
                    "fillBytes=$onDeviceFillBytes fillError=$onDeviceFillError " +
                    "freeBefore=$freeBefore freeAtPeak=$freeAtPeak",
            )
        }
        if (finalMessageCount != successfulWrites + 1 ||
            afterFailure.getJSONObject("session").getInt("next_seq") !=
            sessionBeforeFailure.getInt("next_seq")) {
            error("physical disk pressure was not rolled back cleanly")
        }
        if (jobsBefore.getString("state") != "pending" ||
            afterFailure.getJSONObject("jobs").getString("state") != "pending") {
            error("physical disk pressure lost or changed the pending job")
        }
        if (finalIntegrity.getString("quick_check") != "ok" ||
            finalIntegrity.getInt("foreign_key_violations") != 0) {
            error("physical disk pressure database integrity check failed")
        }
        return JSONObject()
            .put("status", "passed")
            .put("phase", PHASE_PHYSICAL_DISK_PRESSURE)
            .put("free_bytes_before", freeBefore)
            .put("free_bytes_after", freeAfter)
            .put("on_device_fill_bytes", onDeviceFillBytes)
            .put("on_device_fill_error", onDeviceFillError)
            .put("fill_deleted", fillDeleted)
            .put("successful_pressure_messages", successfulWrites)
            .put("pressure_message_bytes", PRESSURE_MESSAGE_BYTES)
            .put("session_before_failure", sessionBeforeFailure)
            .put("failure_code", failureCode)
            .put("failure_error", failureError)
            .put("after_failure", afterFailure)
            .put("message_count", finalMessageCount)
            .put("integrity", finalIntegrity)
            .put("database_deleted", deleted)
            .put("logs", logs.takeLast(8))
    }

    private fun storagePressure(context: Context): JSONObject {
        deleteDatabaseFiles(context)
        val logs = mutableListOf<String>()
        val nowMillis = System.currentTimeMillis()
        val database = MemoryV2Database(context, DATABASE_NAME)
        val coordinator = coordinator(context, logs, database)
        coordinator.open(IDENTITY, nowMillis)
        coordinator.recordIncoming("message before storage pressure", nowMillis + 1_000L)
        val db = database.writableDatabase
        val before = sessionState(database)
        val scope = scopeContext(db, before.getString("scope_id"))
        val sourceId = queryString(
            db,
            "SELECT message_id FROM messages WHERE scope_id=? AND session_id=? " +
                "ORDER BY seq LIMIT 1",
            arrayOf(scope.scopeId, before.getString("session_id")),
        ) ?: error("storage-pressure source message is missing")
        MemoryV2JobService(context, database).enqueue(
            scope = scope,
            sessionId = before.getString("session_id"),
            kind = "extract",
            sourceIds = listOf(sourceId),
            jobId = STORAGE_PRESSURE_JOB_ID,
        )
        val jobsBefore = jobState(database, scope.scopeId, STORAGE_PRESSURE_JOB_ID)
        val freeBefore = StatFs(context.filesDir.absolutePath).availableBytes
        var successfulWrites = 0
        var sessionBeforeFailure = before
        var appendError = ""
        var appendCode = ""
        val pressureMessage = "p".repeat(PRESSURE_MESSAGE_BYTES)
        while (successfulWrites < MAX_PRESSURE_MESSAGES) {
            val failure = runCatching {
                coordinator.recordIncoming(
                    pressureMessage,
                    nowMillis + 2_000L + successfulWrites,
                )
            }.exceptionOrNull()
            if (failure == null) {
                successfulWrites++
                sessionBeforeFailure = sessionState(database)
                continue
            }
            appendError = failure.message.orEmpty()
            appendCode = (failure as? MemoryV2Exception)?.code?.name.orEmpty()
            break
        }
        if (appendCode != MemoryV2ErrorCode.RESOURCE_CAPACITY_EXHAUSTED.name) {
            error(
                "capacity pressure did not exhaust message storage " +
                    "writes=$successfulWrites code=$appendCode error=$appendError",
            )
        }
        val afterFailure = JSONObject()
            .put("session", sessionState(database))
            .put("message_count", messageCount(database))
            .put("jobs", jobState(database, scope.scopeId, STORAGE_PRESSURE_JOB_ID))
            .put("message_ledger_bytes", queryLong(
                db,
                "SELECT COALESCE(SUM(used_bytes),0) FROM capacity_ledger " +
                    "WHERE scope_id=? AND resource='messages'",
                arrayOf(scope.scopeId),
            ) ?: 0L)
            .put("integrity", integrity(database))
        val finalIntegrity = afterFailure.getJSONObject("integrity")
        val finalMessageCount = afterFailure.getInt("message_count")
        coordinator.close()
        database.close()
        val freeAfter = StatFs(context.filesDir.absolutePath).availableBytes
        val deleted = deleteDatabaseFiles(context)
        if (finalIntegrity.getString("quick_check") != "ok") {
            error("storage-pressure database quick_check failed")
        }
        if (finalIntegrity.getInt("foreign_key_violations") != 0) {
            error("storage-pressure foreign key check failed")
        }
        if (successfulWrites < 100 ||
            finalMessageCount != successfulWrites + 1 ||
            afterFailure.getJSONObject("session").getInt("next_seq") !=
            sessionBeforeFailure.getInt("next_seq")) {
            error("capacity-pressure append was not rolled back cleanly")
        }
        if (jobsBefore.getString("state") != "pending" ||
            afterFailure.getJSONObject("jobs").getString("state") != "pending") {
            error("storage-pressure pending job was not preserved")
        }
        return JSONObject()
            .put("status", "passed")
            .put("phase", PHASE_STORAGE_PRESSURE)
            .put("session_before", before)
            .put("job_before", jobsBefore)
            .put("successful_pressure_messages", successfulWrites)
            .put("pressure_message_bytes", PRESSURE_MESSAGE_BYTES)
            .put("session_before_failure", sessionBeforeFailure)
            .put("append_error_code", appendCode)
            .put("append_error", appendError)
            .put("after_failure", afterFailure)
            .put("message_count", finalMessageCount)
            .put("integrity", finalIntegrity)
            .put("device_free_bytes_before", freeBefore)
            .put("device_free_bytes_after", freeAfter)
            .put("database_deleted", deleted)
            .put("logs", logs.takeLast(8))
    }

    private fun coordinator(
        context: Context,
        logs: MutableList<String>,
        database: MemoryV2Database? = null,
    ): MemoryCoordinator {
        return MemoryCoordinator(
            context = context,
            onLog = { logs += it },
            onStatus = { logs += "status=$it" },
            databaseName = DATABASE_NAME,
            databaseOverride = database,
            scheduleBackgroundWork = false,
        )
    }

    private fun sessionState(database: MemoryV2Database): JSONObject {
        return database.readableDatabase.query(
            "sessions",
            arrayOf("scope_id", "session_id", "state", "next_seq", "updated_at"),
            null,
            null,
            null,
            null,
            "updated_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                error("coordinator lifecycle session is missing")
            }
            JSONObject()
                .put("scope_id", cursor.getString(cursor.getColumnIndexOrThrow("scope_id")))
                .put("session_id", cursor.getString(cursor.getColumnIndexOrThrow("session_id")))
                .put("state", cursor.getString(cursor.getColumnIndexOrThrow("state")))
                .put("next_seq", cursor.getInt(cursor.getColumnIndexOrThrow("next_seq")))
                .put("updated_at", cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
        }
    }

    private fun scopeContext(
        db: SQLiteDatabase,
        scopeId: String,
    ): TrustedScopeContext {
        return db.query(
            "scopes",
            arrayOf(
                "scope_id",
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
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                error("storage-pressure scope is missing")
            }
            TrustedScopeContext(
                scopeId = cursor.getString(cursor.getColumnIndexOrThrow("scope_id")),
                key = MemoryScopeKey(
                    ownerId = cursor.getString(cursor.getColumnIndexOrThrow("owner_id")),
                    contactId = cursor.getString(cursor.getColumnIndexOrThrow("contact_id")),
                    agentId = cursor.getString(cursor.getColumnIndexOrThrow("agent_id")),
                    personaId = cursor.getString(cursor.getColumnIndexOrThrow("persona_id")),
                    realmId = cursor.getString(cursor.getColumnIndexOrThrow("realm_id")),
                ),
                personaRevision = cursor.getInt(
                    cursor.getColumnIndexOrThrow("persona_revision"),
                ),
                revision = cursor.getInt(cursor.getColumnIndexOrThrow("revision")),
                deletionEpoch = cursor.getInt(cursor.getColumnIndexOrThrow("deletion_epoch")),
                importGeneration = cursor.getInt(
                    cursor.getColumnIndexOrThrow("import_generation"),
                ),
                retentionPolicy = cursor.getString(
                    cursor.getColumnIndexOrThrow("retention_policy"),
                ),
                state = cursor.getString(cursor.getColumnIndexOrThrow("state")),
                restoreGuardId = cursor.getString(
                    cursor.getColumnIndexOrThrow("restore_guard_id"),
                ),
            )
        }
    }

    private fun jobState(
        database: MemoryV2Database,
        scopeId: String,
        jobId: String,
    ): JSONObject {
        return database.readableDatabase.query(
            "jobs",
            arrayOf("state", "attempts", "lease_owner", "lease_until"),
            "scope_id=? AND job_id=?",
            arrayOf(scopeId, jobId),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                error("storage-pressure job is missing")
            }
            JSONObject()
                .put("state", cursor.getString(cursor.getColumnIndexOrThrow("state")))
                .put("attempts", cursor.getInt(cursor.getColumnIndexOrThrow("attempts")))
        }
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

    private fun queryLong(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): Long? {
        return db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }

    private fun sessionCount(database: MemoryV2Database): Int {
        return count(database.readableDatabase, "SELECT COUNT(*) FROM sessions", emptyArray())
    }

    private fun messageCount(database: MemoryV2Database): Int {
        return count(database.readableDatabase, "SELECT COUNT(*) FROM messages", emptyArray())
    }

    private fun jobCount(database: MemoryV2Database): Int {
        return count(database.readableDatabase, "SELECT COUNT(*) FROM jobs", emptyArray())
    }

    private fun count(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
    ): Int {
        return db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun integrity(database: MemoryV2Database): JSONObject {
        val db = database.readableDatabase
        val quickCheck = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "no_result"
        }
        val foreignKeyViolations = count(
            db,
            "SELECT COUNT(*) FROM pragma_foreign_key_check",
            emptyArray(),
        )
        val journalMode = db.rawQuery("PRAGMA journal_mode", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
        }
        return JSONObject()
            .put("quick_check", quickCheck)
            .put("foreign_key_violations", foreignKeyViolations)
            .put("journal_mode", journalMode)
    }

    private fun deleteDatabaseFiles(context: Context): Boolean {
        val deletedDatabase = context.deleteDatabase(DATABASE_NAME)
        val deleteJournal = File(
            context.applicationInfo.dataDir,
            "databases/$DATABASE_NAME-wal",
        ).delete()
        val deleteSharedMemory = File(
            context.applicationInfo.dataDir,
            "databases/$DATABASE_NAME-shm",
        ).delete()
        return deletedDatabase || deleteJournal || deleteSharedMemory
    }

    companion object {
        const val ACTION = "com.local.wxbotprobe.DEBUG_MEMORY_V2_COORDINATOR_LIFECYCLE"
        const val EXTRA_PHASE = "phase"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_VERIFY = "verify"
        const val PHASE_STORAGE_PRESSURE = "storage_pressure"
        const val PHASE_PHYSICAL_DISK_PRESSURE = "physical_disk_pressure"
        const val PHASE_RESTORE_FENCE = "restore_fence"
        const val PHASE_CLEANUP = "cleanup"
        private const val DATABASE_NAME = "memory-v2-coordinator-lifecycle.db"
        private const val OUTPUT_PATH = "debug/memory-v2-coordinator-lifecycle.json"
        private const val STORAGE_PRESSURE_JOB_ID = "storage-pressure-job"
        private const val PHYSICAL_PRESSURE_JOB_ID = "physical-pressure-job"
        private const val PHYSICAL_FINAL_RESERVE_BYTES = 4L * 1024L * 1024L
        private const val RESTORE_FENCE_FOREIGN_GUARD = "foreign-restore-guard"
        private const val PRESSURE_MESSAGE_BYTES = 60_000
        private const val MAX_PRESSURE_MESSAGES = 256
        private val IDENTITY = ChatIdentity(
            title = "文件传输助手",
            fingerprint = 0x740074L,
        )
    }
}
