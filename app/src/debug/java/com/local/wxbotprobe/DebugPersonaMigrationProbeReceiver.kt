package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import com.local.wxbotprobe.bot.PersonaData
import com.local.wxbotprobe.memory.v2.MemoryV2Database
import com.local.wxbotprobe.memory.v2.MemoryV2Exception
import com.local.wxbotprobe.memory.v2.MemoryV2IdentityStore
import com.local.wxbotprobe.memory.v2.MemoryV2ManualMemoryService
import com.local.wxbotprobe.memory.v2.MemoryV2PersonaMigrationService
import com.local.wxbotprobe.memory.v2.MemoryV2Repository
import com.local.wxbotprobe.memory.v2.MemoryV2ScopeKeyPolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

class DebugPersonaMigrationProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread {
            var database: MemoryV2Database? = null
            val report = try {
                context.deleteDatabase(TEST_DATABASE)
                database = MemoryV2Database(context, TEST_DATABASE)
                runProbe(context.applicationContext, database)
            } catch (error: Throwable) {
                JSONObject()
                    .put("status", "failed")
                    .put("error", error.stackTraceToString())
            } finally {
                database?.close()
                context.deleteDatabase(TEST_DATABASE)
            }
            val output = File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "diagnostics/persona-migration-probe.json",
            )
            output.parentFile?.mkdirs()
            val temporary = File(output.parentFile, "${output.name}.tmp")
            temporary.writeText(report.toString(2), Charsets.UTF_8)
            Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            android.util.Log.i("DebugPersonaMigration", report.toString())
            pending.finish()
        }.start()
    }

    private fun runProbe(
        context: Context,
        database: MemoryV2Database,
    ): JSONObject {
        val identities = MemoryV2IdentityStore(context)
        val sourcePersona = "11111111-aaaa-4111-8111-111111111111"
        val targetPersona = "22222222-bbbb-4222-8222-222222222222"
        val otherPersona = "33333333-cccc-4333-8333-333333333333"
        val contactId = "debug-migration-contact"
        val otherContactId = "debug-migration-other-contact"
        val repository = MemoryV2Repository(context, database)
        val source = repository.ensureScope(
            MemoryV2ScopeKeyPolicy.resolve(
                identities.ownerId,
                contactId,
                identities.agentId,
                sourcePersona,
                "real",
                PersonaData("source", emptyList()),
            ),
            "Migration Contact",
        )
        val target = repository.ensureScope(
            MemoryV2ScopeKeyPolicy.resolve(
                identities.ownerId,
                contactId,
                identities.agentId,
                targetPersona,
                "real",
                PersonaData("target", emptyList()),
            ),
            "Migration Contact",
        )
        val other = repository.ensureScope(
            MemoryV2ScopeKeyPolicy.resolve(
                identities.ownerId,
                otherContactId,
                identities.agentId,
                otherPersona,
                "real",
                PersonaData("other", emptyList()),
            ),
            "Other Contact",
        )
        val manual = MemoryV2ManualMemoryService(context, database)
        manual.add(source.scopeId, "A0", "SOURCE_A0")
        manual.add(source.scopeId, "A1", "SOURCE_A1")
        manual.add(source.scopeId, "A2", "SOURCE_A2")
        manual.add(source.scopeId, "A1", "SOURCE_CONFLICT")
        manual.add(target.scopeId, "A1", "SOURCE_CONFLICT")
        val sourceA0 = memoryId(database, source.scopeId, "SOURCE_A0")
        val sourceA1 = memoryId(database, source.scopeId, "SOURCE_A1")
        val sourceA2 = memoryId(database, source.scopeId, "SOURCE_A2")
        val sourceConflict = memoryId(database, source.scopeId, "SOURCE_CONFLICT")
        val sourceVersionA2 = memoryVersion(database, source.scopeId, sourceA2)
        database.writableDatabase.insertOrThrow(
            "memory_relations",
            null,
            ContentValues().apply {
                put("scope_id", source.scopeId)
                put("relation_id", "probe-relation")
                put("source_memory_id", sourceA2)
                put("target_memory_id", sourceA1)
                put("relation_type", "ELABORATES")
                put("target_version", 1)
                put("created_at", NOW)
            },
        )
        database.writableDatabase.insertOrThrow(
            "memory_conflicts",
            null,
            ContentValues().apply {
                put("scope_id", source.scopeId)
                put("conflict_id", "probe-conflict")
                put("memory_id", sourceConflict)
                put("target_version", 1)
                put("operation", "CONTRADICT")
                put(
                    "candidate_json",
                    JSONObject().put("text", "candidate").toString(),
                )
                put("state", "pending")
                put("created_at", NOW)
                put("updated_at", NOW)
            },
        )
        val service = MemoryV2PersonaMigrationService(context, database) { NOW }
        val sourceConflictFactKey = service.preview(
            source.scopeId,
            target.scopeId,
            sourceConflict,
        ).targetFactKey
        val targetConflictMemoryId = memoryId(
            database,
            target.scopeId,
            "SOURCE_CONFLICT",
        )
        database.writableDatabase.update(
            "memories",
            ContentValues().apply {
                put("fact_key", sourceConflictFactKey)
            },
            "scope_id=? AND memory_id=?",
            arrayOf(target.scopeId, targetConflictMemoryId),
        )
        val conflictBlocked = service.preview(
            source.scopeId,
            target.scopeId,
            sourceConflict,
        ).blockCode == "SOURCE_CONFLICT_NEEDS_REVIEW"
        database.writableDatabase.delete(
            "memory_conflicts",
            "scope_id=? AND conflict_id=?",
            arrayOf(source.scopeId, "probe-conflict"),
        )
        val targetFactConflict = service.preview(
            source.scopeId,
            target.scopeId,
            sourceConflict,
        ).blockCode == "TARGET_FACT_CONFLICT"
        val crossContactRejected = runCatching {
            service.preview(source.scopeId, other.scopeId, sourceA1)
        }.exceptionOrNull() is MemoryV2Exception
        val firstPreview = service.preview(
            source.scopeId,
            target.scopeId,
            sourceA1,
        )
        val relationOmissionRequired = runCatching {
            service.migrate(source.scopeId, target.scopeId, sourceA1)
        }.exceptionOrNull()?.message?.contains("RELATION_CLOSURE_REQUIRED") == true
        val migratedA1 = service.migrate(
            sourceScopeId = source.scopeId,
            targetScopeId = target.scopeId,
            memoryId = sourceA1,
            acceptRelationOmission = true,
        )
        val a2Preview = service.preview(
            source.scopeId,
            target.scopeId,
            sourceA2,
        )
        val migratedA2 = service.migrate(
            source.scopeId,
            target.scopeId,
            sourceA2,
        )
        val repeatedA2 = service.migrate(
            source.scopeId,
            target.scopeId,
            sourceA2,
        )
        val a0RejectedWithoutConfirmation = runCatching {
            service.migrate(source.scopeId, target.scopeId, sourceA0)
        }.exceptionOrNull()?.message?.contains("A0_CONFIRMATION_REQUIRED") == true
        val migratedA0 = service.migrate(
            sourceScopeId = source.scopeId,
            targetScopeId = target.scopeId,
            memoryId = sourceA0,
            a0Confirmed = true,
        )
        val rollback = service.rollback(
            sourceScopeId = source.scopeId,
            targetScopeId = target.scopeId,
            memoryId = sourceA2,
            confirmed = true,
        )
        val repeatedRollback = service.rollback(
            sourceScopeId = source.scopeId,
            targetScopeId = target.scopeId,
            memoryId = sourceA2,
            confirmed = true,
        )
        val targetA2Retracted = memoryRetracted(
            database,
            target.scopeId,
            migratedA2.targetMemoryId,
        )
        val targetA2EvidenceRetained = count(
            database,
            "SELECT COUNT(*) FROM evidence WHERE scope_id=? AND memory_id=?",
            target.scopeId,
            migratedA2.targetMemoryId,
        ) == 1
        val rollbackAuditState = auditState(
            database,
            source.scopeId,
            target.scopeId,
            sourceA2,
        )
        val sourceStillSame = memoryVersion(database, source.scopeId, sourceA2) ==
            sourceVersionA2 &&
            memoryScope(database, source.scopeId, sourceA2) == source.scopeId &&
            memoryScope(database, source.scopeId, sourceA1) == source.scopeId &&
            memoryScope(database, source.scopeId, sourceA0) == source.scopeId
        val targetEvidenceLegal = count(
            database,
            "SELECT COUNT(*) FROM evidence e JOIN messages m " +
                "ON m.scope_id=e.scope_id AND m.message_id=e.source_message_id " +
                "WHERE e.scope_id=?",
            target.scopeId,
        ) == count(
            database,
            "SELECT COUNT(*) FROM evidence WHERE scope_id=?",
            target.scopeId,
        )
        val auditCommitted = count(
            database,
            "SELECT COUNT(*) FROM persona_migration_audit " +
                "WHERE target_scope_id=? AND state='committed'",
            target.scopeId,
        )
        val auditRolledBack = count(
            database,
            "SELECT COUNT(*) FROM persona_migration_audit " +
                "WHERE target_scope_id=? AND state='rolled_back'",
            target.scopeId,
        )
        val targetRelationCount = count(
            database,
            "SELECT COUNT(*) FROM memory_relations WHERE scope_id=?",
            target.scopeId,
        )
        val schemaVersion = count(
            database,
            "PRAGMA user_version",
        )
        val passed = conflictBlocked &&
            targetFactConflict &&
            crossContactRejected &&
            firstPreview.omittedRelationCount == 1 &&
            relationOmissionRequired &&
            migratedA1.status == "saved" &&
            a2Preview.copyableRelationCount == 1 &&
            a2Preview.omittedRelationCount == 0 &&
            migratedA2.copiedRelationCount == 1 &&
            repeatedA2.status == "unchanged" &&
            a0RejectedWithoutConfirmation &&
            migratedA0.status == "saved" &&
            rollback.status == "rolled_back" &&
            repeatedRollback.status == "unchanged" &&
            targetA2Retracted &&
            targetA2EvidenceRetained &&
            rollbackAuditState == "rolled_back" &&
            sourceStillSame &&
            targetEvidenceLegal &&
            auditCommitted == 2 &&
            auditRolledBack == 1 &&
            targetRelationCount == 1 &&
            schemaVersion == MemoryV2Database.DATABASE_VERSION
        return JSONObject()
            .put("status", if (passed) "passed" else "failed")
            .put("schema_version", schemaVersion)
            .put("source_scope_id", source.scopeId)
            .put("target_scope_id", target.scopeId)
            .put("other_scope_id", other.scopeId)
            .put("source_unchanged", sourceStillSame)
            .put("source_pending_conflict_blocked", conflictBlocked)
            .put("target_fact_conflict_blocked", targetFactConflict)
            .put("cross_contact_rejected", crossContactRejected)
            .put("a1_relation_omission_required", relationOmissionRequired)
            .put("a1_migration_status", migratedA1.status)
            .put("a1_omitted_relations", migratedA1.omittedRelationCount)
            .put("a2_copyable_relations", a2Preview.copyableRelationCount)
            .put("a2_copied_relations", migratedA2.copiedRelationCount)
            .put("a2_repeat_status", repeatedA2.status)
            .put("a0_rejected_without_confirmation", a0RejectedWithoutConfirmation)
            .put("a0_migration_status", migratedA0.status)
            .put("rollback_status", rollback.status)
            .put("rollback_repeat_status", repeatedRollback.status)
            .put("rollback_target_retracted", targetA2Retracted)
            .put("rollback_evidence_retained", targetA2EvidenceRetained)
            .put("rollback_audit_state", rollbackAuditState)
            .put("target_evidence_legal", targetEvidenceLegal)
            .put("audit_committed", auditCommitted)
            .put("audit_rolled_back", auditRolledBack)
            .put("target_relation_count", targetRelationCount)
            .put("production_database_touched", false)
    }

    private fun memoryId(
        database: MemoryV2Database,
        scopeId: String,
        text: String,
    ): String {
        return database.readableDatabase.rawQuery(
            "SELECT memory_id FROM memories WHERE scope_id=? AND text=? " +
                "AND retracted_at IS NULL",
            arrayOf(scopeId, text),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "memory not found: $text" }
            cursor.getString(0)
        }
    }

    private fun memoryVersion(
        database: MemoryV2Database,
        scopeId: String,
        memoryId: String,
    ): Int {
        return database.readableDatabase.rawQuery(
            "SELECT version FROM memories WHERE scope_id=? AND memory_id=?",
            arrayOf(scopeId, memoryId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "memory version not found" }
            cursor.getInt(0)
        }
    }

    private fun memoryScope(
        database: MemoryV2Database,
        scopeId: String,
        memoryId: String,
    ): String {
        return database.readableDatabase.rawQuery(
            "SELECT scope_id FROM memories WHERE scope_id=? AND memory_id=?",
            arrayOf(scopeId, memoryId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "memory scope not found" }
            cursor.getString(0)
        }
    }

    private fun memoryRetracted(
        database: MemoryV2Database,
        scopeId: String,
        memoryId: String,
    ): Boolean {
        return database.readableDatabase.rawQuery(
            "SELECT retracted_at FROM memories WHERE scope_id=? AND memory_id=?",
            arrayOf(scopeId, memoryId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "memory retraction not found" }
            !cursor.isNull(0)
        }
    }

    private fun auditState(
        database: MemoryV2Database,
        sourceScopeId: String,
        targetScopeId: String,
        memoryId: String,
    ): String {
        return database.readableDatabase.rawQuery(
            "SELECT state FROM persona_migration_audit " +
                "WHERE source_scope_id=? AND target_scope_id=? " +
                "AND source_memory_id=?",
            arrayOf(sourceScopeId, targetScopeId, memoryId),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "migration audit not found" }
            cursor.getString(0)
        }
    }

    private fun count(
        database: MemoryV2Database,
        sql: String,
        vararg args: String,
    ): Int {
        return database.readableDatabase.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    companion object {
        private const val TEST_DATABASE = "persona-migration-probe.db"
        private val NOW = System.currentTimeMillis() / 1_000L
    }
}
