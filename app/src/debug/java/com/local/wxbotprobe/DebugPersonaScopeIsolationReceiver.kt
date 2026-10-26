package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import com.local.wxbotprobe.bot.PersonaData
import com.local.wxbotprobe.memory.v2.AppendMessageRequest
import com.local.wxbotprobe.memory.v2.MemoryV2AdminStore
import com.local.wxbotprobe.memory.v2.MemoryV2ConfigLoader
import com.local.wxbotprobe.memory.v2.MemoryV2Database
import com.local.wxbotprobe.memory.v2.MemoryV2IdentityStore
import com.local.wxbotprobe.memory.v2.MemoryV2JobService
import com.local.wxbotprobe.memory.v2.MemoryV2ManualMemoryService
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextPayloadBuilder
import com.local.wxbotprobe.memory.v2.MemoryV2Repository
import com.local.wxbotprobe.memory.v2.MemoryV2ScopeKeyPolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

class DebugPersonaScopeIsolationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        android.util.Log.i("DebugPersonaScopeIsolation", "probe received")
        val pending = goAsync()
        Thread {
            var database: MemoryV2Database? = null
            try {
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
                    "diagnostics/persona-scope-isolation.json",
                )
                output.parentFile?.mkdirs()
                val temporary = File(output.parentFile, "${output.name}.tmp")
                temporary.writeText(report.toString(2), Charsets.UTF_8)
                Files.move(
                    temporary.toPath(),
                    output.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                android.util.Log.i("DebugPersonaScopeIsolation", report.toString())
            } catch (error: Throwable) {
                android.util.Log.e(
                    "DebugPersonaScopeIsolation",
                    "probe write failed",
                    error,
                )
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun runProbe(
        context: Context,
        database: MemoryV2Database,
    ): JSONObject {
        val identities = MemoryV2IdentityStore(context)
        val contactId = "persona-isolation-contact"
        val personaAId = "11111111-aaaa-4111-8111-111111111111"
        val personaBId = "22222222-bbbb-4222-8222-222222222222"
        val personaA = PersonaData("SCOPE_A_PERSONA", emptyList())
        val personaB = PersonaData("SCOPE_B_PERSONA", emptyList())
        val repository = MemoryV2Repository(context, database)
        val scopeA = repository.ensureScope(
            resolved = MemoryV2ScopeKeyPolicy.resolve(
                ownerId = identities.ownerId,
                contactId = contactId,
                agentId = identities.agentId,
                personaId = personaAId,
                realmId = "real",
                persona = personaA,
            ),
            contactDisplayName = "Persona Isolation A",
        )
        val scopeB = repository.ensureScope(
            resolved = MemoryV2ScopeKeyPolicy.resolve(
                ownerId = identities.ownerId,
                contactId = contactId,
                agentId = identities.agentId,
                personaId = personaBId,
                realmId = "real",
                persona = personaB,
            ),
            contactDisplayName = "Persona Isolation A",
        )
        val manual = MemoryV2ManualMemoryService(context, database)
        listOf(scopeA, scopeB).forEach { scope ->
            val marker = if (scope == scopeA) "SCOPE_A" else "SCOPE_B"
            listOf("A0", "A1", "A2").forEach { tier ->
                val result = manual.add(scope.scopeId, tier, "${marker}_${tier}_ONLY")
                check(result.status == "saved") {
                    "${marker}_$tier manual add failed: ${result.status}"
                }
            }
        }
        val appendedA = repository.appendMessage(
            scopeA,
            AppendMessageRequest(
                clientMessageId = "isolation-a-user",
                sessionId = null,
                role = "user",
                text = "SCOPE_A_USER_ONLY",
                occurredAt = NOW,
                receivedAt = NOW,
                timezone = MemoryV2ConfigLoader.load(context).timezone,
            ),
        )
        val appendedB = repository.appendMessage(
            scopeB,
            AppendMessageRequest(
                clientMessageId = "isolation-b-user",
                sessionId = null,
                role = "user",
                text = "SCOPE_B_USER_ONLY",
                occurredAt = NOW,
                receivedAt = NOW,
                timezone = MemoryV2ConfigLoader.load(context).timezone,
            ),
        )
        updateWorkingSummary(database, scopeA.scopeId, appendedA.sessionId, "SCOPE_A_A3_ONLY")
        updateWorkingSummary(database, scopeB.scopeId, appendedB.sessionId, "SCOPE_B_A3_ONLY")
        val jobs = MemoryV2JobService(context, database)
        jobs.enqueue(
            scope = scopeA,
            sessionId = appendedA.sessionId,
            kind = "extract",
            sourceIds = listOf(appendedA.messageId),
        )
        jobs.enqueue(
            scope = scopeB,
            sessionId = appendedB.sessionId,
            kind = "extract",
            sourceIds = listOf(appendedB.messageId),
        )
        val memoryIdsA = memoryIds(database, scopeA.scopeId)
        val memoryIdsB = memoryIds(database, scopeB.scopeId)
        insertRelation(
            database,
            scopeA.scopeId,
            memoryIdsA.getValue("A2"),
            memoryIdsA.getValue("A1"),
            "SCOPE_A_RELATION",
        )
        insertRelation(
            database,
            scopeB.scopeId,
            memoryIdsB.getValue("A2"),
            memoryIdsB.getValue("A1"),
            "SCOPE_B_RELATION",
        )
        insertConflict(
            database,
            scopeA.scopeId,
            memoryIdsA.getValue("A1"),
            "SCOPE_A_CONFLICT",
        )
        insertConflict(
            database,
            scopeB.scopeId,
            memoryIdsB.getValue("A1"),
            "SCOPE_B_CONFLICT",
        )
        val tables = listOf(
            "messages",
            "sessions",
            "memories",
            "evidence",
            "jobs",
            "memory_conflicts",
            "memory_relations",
            "outbox",
        )
        val countsA = tableCounts(database, tables, scopeA.scopeId)
        val countsB = tableCounts(database, tables, scopeB.scopeId)
        tables.forEach { table ->
            check(countsA.getInt(table) > 0) { "$table missing in scope A" }
            check(countsB.getInt(table) > 0) { "$table missing in scope B" }
        }
        val tiersA = tierCounts(database, scopeA.scopeId)
        val tiersB = tierCounts(database, scopeB.scopeId)
        listOf("A0", "A1", "A2").forEach { tier ->
            check(tiersA.getInt(tier) == 1) { "$tier scope A count mismatch" }
            check(tiersB.getInt(tier) == 1) { "$tier scope B count mismatch" }
        }
        val a3A = count(
            database,
            "SELECT COUNT(*) FROM sessions WHERE scope_id=? " +
                "AND working_summary LIKE '%SCOPE_A_A3_ONLY%'",
            scopeA.scopeId,
        )
        val a3B = count(
            database,
            "SELECT COUNT(*) FROM sessions WHERE scope_id=? " +
                "AND working_summary LIKE '%SCOPE_B_A3_ONLY%'",
            scopeB.scopeId,
        )
        check(a3A == 1 && a3B == 1) { "A3 scope isolation failed" }
        val contextBuilder = MemoryV2ReplyContextPayloadBuilder(context, database)
        val replyA = contextBuilder.build(scopeA.scopeId, "SCOPE_A").memoryText
        val replyB = contextBuilder.build(scopeB.scopeId, "SCOPE_B").memoryText
        check("SCOPE_A" in replyA && "SCOPE_B" !in replyA) {
            "scope A reply context leaked"
        }
        check("SCOPE_B" in replyB && "SCOPE_A" !in replyB) {
            "scope B reply context leaked"
        }
        val adminA = MemoryV2AdminStore(context, database) { personaAId }
        val adminB = MemoryV2AdminStore(context, database) { personaBId }
        val currentA = adminA.contacts().single()
        val currentB = adminB.contacts().single()
        check(currentA.scopeId == scopeA.scopeId) { "admin A selected wrong scope" }
        check(currentB.scopeId == scopeB.scopeId) { "admin B selected wrong scope" }
        check(currentA.contactId == contactId && currentB.contactId == contactId) {
            "admin contact identity mismatch"
        }
        return JSONObject()
            .put("status", "passed")
            .put("scope_a", scopeA.scopeId)
            .put("scope_b", scopeB.scopeId)
            .put("different_scope_ids", scopeA.scopeId != scopeB.scopeId)
            .put("counts_a", countsA)
            .put("counts_b", countsB)
            .put("tiers_a", tiersA)
            .put("tiers_b", tiersB)
            .put("a3_a", a3A)
            .put("a3_b", a3B)
            .put("reply_context_a_isolated", true)
            .put("reply_context_b_isolated", true)
            .put("admin_a_scope", currentA.scopeId)
            .put("admin_b_scope", currentB.scopeId)
            .put("production_database_touched", false)
    }

    private fun updateWorkingSummary(
        database: MemoryV2Database,
        scopeId: String,
        sessionId: String,
        marker: String,
    ) {
        val summary = JSONObject()
            .put(
                "goals",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("text", marker)
                        .put("evidence", org.json.JSONArray()),
                ),
            )
            .toString()
        check(
            database.writableDatabase.update(
                "sessions",
                ContentValues().apply {
                    put("working_summary", summary)
                    put("working_version", 1)
                    put("state", "closed")
                    put("updated_at", NOW)
                },
                "scope_id=? AND session_id=?",
                arrayOf(scopeId, sessionId),
            ) == 1,
        ) { "working summary update failed" }
    }

    private fun memoryIds(
        database: MemoryV2Database,
        scopeId: String,
    ): Map<String, String> {
        return database.readableDatabase.rawQuery(
            "SELECT tier,memory_id FROM memories WHERE scope_id=?",
            arrayOf(scopeId),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), cursor.getString(1))
                }
            }
        }
    }

    private fun insertRelation(
        database: MemoryV2Database,
        scopeId: String,
        sourceId: String,
        targetId: String,
        marker: String,
    ) {
        database.writableDatabase.insertOrThrow(
            "memory_relations",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put("relation_id", marker)
                put("source_memory_id", sourceId)
                put("target_memory_id", targetId)
                put("relation_type", "ELABORATES")
                put("target_version", 1)
                put("created_at", NOW)
            },
        )
    }

    private fun insertConflict(
        database: MemoryV2Database,
        scopeId: String,
        memoryId: String,
        marker: String,
    ) {
        database.writableDatabase.insertOrThrow(
            "memory_conflicts",
            null,
            ContentValues().apply {
                put("scope_id", scopeId)
                put("conflict_id", marker)
                put("memory_id", memoryId)
                put("target_version", 1)
                put("operation", "CONTRADICT")
                put("candidate_json", JSONObject().put("marker", marker).toString())
                put("state", "pending")
                put("created_at", NOW)
                put("updated_at", NOW)
            },
        )
    }

    private fun tableCounts(
        database: MemoryV2Database,
        tables: List<String>,
        scopeId: String,
    ): JSONObject {
        return JSONObject().apply {
            tables.forEach { table ->
                put(
                    table,
                    count(
                        database,
                        "SELECT COUNT(*) FROM $table WHERE scope_id=?",
                        scopeId,
                    ),
                )
            }
        }
    }

    private fun tierCounts(
        database: MemoryV2Database,
        scopeId: String,
    ): JSONObject {
        return JSONObject().apply {
            listOf("A0", "A1", "A2").forEach { tier ->
                put(
                    tier,
                    count(
                        database,
                        "SELECT COUNT(*) FROM memories WHERE scope_id=? " +
                            "AND tier=? AND retracted_at IS NULL",
                        scopeId,
                        tier,
                    ),
                )
            }
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
        private const val TEST_DATABASE = "persona-scope-isolation.db"
        private val NOW = System.currentTimeMillis() / 1_000L
    }
}
