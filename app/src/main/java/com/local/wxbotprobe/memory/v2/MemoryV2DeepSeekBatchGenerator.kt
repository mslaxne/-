package com.local.wxbotprobe.memory.v2

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.local.wxbotprobe.bot.ChatClient
import com.local.wxbotprobe.bot.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

class MemoryV2DeepSeekBatchGenerator(
    private val context: Context,
    private val database: MemoryV2Database,
    private val client: ChatClient,
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) : MemoryV2BatchGenerator {
    private val config = MemoryV2ConfigLoader.load(context)
    private val profile = MemoryV2ConfigLoader.loadModelProfile(
        context,
        "deepseek-flash-text-floor-2026-10-23",
    )
    private val predicates = context.assets.open("memory_v2/predicates.json")
        .bufferedReader(Charsets.UTF_8)
        .use { JSONObject(it.readText()) }
    private val reconciliationContext = MemoryV2ReconciliationContextReader(
        context,
        database,
        now,
    )

    override fun generate(job: LeasedMemoryJob): String {
        val promptAsset = if (job.kind == "summary") {
            "memory_v2/summary.txt"
        } else {
            "memory_v2/extract.txt"
        }
        val system = context.assets.open(promptAsset)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .trim()
        val input = buildInput(job)
        val messages = listOf(
            ChatMessage("system", system),
            ChatMessage("user", input.toString()),
        )
        val maxTokens = if (job.kind == "summary") {
            MemoryV2BatchOutputPolicy.summaryMaxTokens(profile.outputMaxTokens)
        } else {
            MemoryV2BatchOutputPolicy.extractionMaxTokens(profile.outputMaxTokens)
        }
        MemoryV2TokenBudgetPolicy.estimate(
            request = TokenBudgetRequest(
                model = profile.model,
                messages = messages.map {
                    MemoryTokenMessage(it.role, it.content)
                },
                maxTokens = maxTokens,
                thinkingDisabled = true,
            ),
            profile = profile,
            nowEpochSeconds = now(),
            config = config.tokenPolicy,
        )
        return client.chat(
            messages = messages,
            temperature = 0.1,
            maxTokens = maxTokens,
        ).text
    }

    private fun buildInput(job: LeasedMemoryJob): JSONObject {
        val db = database.readableDatabase
        val sources = JSONArray()
        job.envelope.sources.forEach { source ->
            db.query(
                "messages",
                arrayOf("role", "body", "occurred_at", "timezone"),
                "scope_id=? AND message_id=?",
                arrayOf(job.scopeId, source.messageId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw MemoryV2Exception(MemoryV2ErrorCode.SOURCE_UNAVAILABLE)
                }
                sources.put(
                    JSONObject()
                        .put("message_id", source.messageId)
                        .put("revision", source.revision)
                        .put("sha256", source.sha256)
                        .put("role", cursor.string("role"))
                        .put("text", cursor.string("body"))
                        .put("occurred_at", cursor.getLong(
                            cursor.getColumnIndexOrThrow("occurred_at"),
                        ))
                        .put("timezone", cursor.string("timezone")),
                )
            }
        }
        val input = JSONObject()
            .put("envelope", job.envelope.toJsonObject())
            .put("sources", sources)
            .put("predicates", predicates)
            .put("entities", entities(db, job.scopeId))
        if (job.kind == "extract") {
            val existingMemories = reconciliationContext.loadFrozen(
                job.scopeId,
                job.envelope.targetVersions,
            )
            input
                .put(
                    "existing_memories",
                    reconciliationContext.renderJson(existingMemories),
                )
                .put(
                    "relate_target_ids",
                    JSONArray(
                        existingMemories
                            .filter { it.tier == "A1" }
                            .map { it.memoryId },
                    ),
                )
                .put(
                    "pending_conflicts",
                    pendingConflicts(db, job.scopeId),
                )
                .put(
                    "output_contract",
                    MemoryV2ExtractionOutputContract.json(),
                )
        } else {
            val session = sessionWorkingState(db, job)
            input
                .put("previous_working_summary", session.first)
                .put("previous_working_sources", session.second)
                .put(
                    "output_contract",
                    MemoryV2SummaryOutputContract.json(),
                )
        }
        return input
    }

    private fun sessionWorkingState(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
    ): Pair<JSONObject, JSONArray> {
        db.query(
            "sessions",
            arrayOf("working_summary", "working_sources"),
            "scope_id=? AND session_id=?",
            arrayOf(job.scopeId, job.sessionId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNAUTHORIZED_SCOPE)
            }
            val summary = runCatching {
                JSONObject(cursor.string("working_summary"))
            }.getOrElse { JSONObject() }
            val sources = runCatching {
                JSONArray(cursor.string("working_sources"))
            }.getOrElse { JSONArray() }
            return summary to sources
        }
    }

    private fun entities(db: SQLiteDatabase, scopeId: String): JSONArray {
        val result = JSONArray()
        db.query(
            "entities",
            arrayOf("entity_id", "kind", "display_name", "resolution"),
            "scope_id=?",
            arrayOf(scopeId),
            null,
            null,
            "entity_id",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.put(
                    JSONObject()
                        .put("entity_id", cursor.string("entity_id"))
                        .put("kind", cursor.string("kind"))
                        .put("display_name", cursor.string("display_name"))
                        .put("resolution", cursor.string("resolution")),
                )
            }
        }
        return result
    }

    private fun pendingConflicts(
        db: SQLiteDatabase,
        scopeId: String,
    ): JSONArray {
        return db.rawQuery(
            "SELECT c.memory_id,c.target_version,c.operation,c.candidate_json," +
                "m.tier,m.text,m.version FROM memory_conflicts c " +
                "JOIN memories m ON m.scope_id=c.scope_id " +
                "AND m.memory_id=c.memory_id " +
                "WHERE c.scope_id=? AND c.state='pending' " +
                "AND c.target_version=m.version " +
                "ORDER BY c.created_at,c.conflict_id LIMIT 20",
            arrayOf(scopeId),
        ).use { cursor ->
            JSONArray().apply {
                while (cursor.moveToNext()) {
                    val candidate = runCatching {
                        JSONObject(cursor.getString(3).orEmpty())
                    }.getOrElse { JSONObject() }
                    put(
                        JSONObject()
                            .put("memory_id", cursor.getString(0).orEmpty())
                            .put(
                                "target_version",
                                cursor.getInt(1),
                            )
                            .put("previous_operation", cursor.getString(2).orEmpty())
                            .put("existing_tier", cursor.getString(4).orEmpty())
                            .put("existing_text", cursor.getString(5).orEmpty())
                            .put(
                                "candidate_text",
                                candidate.optString("text"),
                            )
                            .put(
                                "candidate_value",
                                candidate.optString("value_json"),
                            ),
                    )
                }
            }
        }
    }

    private fun Cursor.string(column: String): String {
        return getString(getColumnIndexOrThrow(column)).orEmpty()
    }
}
