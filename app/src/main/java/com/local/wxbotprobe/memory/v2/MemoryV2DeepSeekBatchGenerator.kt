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
    private val enableTools: Boolean = false,
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

    override fun maxModelCalls(job: LeasedMemoryJob): Int {
        return if (enableTools && job.kind == "extract") {
            MemoryV2AgentProtocol.MAX_READ_TOOL_CALLS + 1
        } else {
            1
        }
    }

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
        return if (enableTools && job.kind == "extract") {
            generateWithAgent(job, system, input, maxTokens)
        } else {
            callWithBudget(messages, maxTokens)
        }
    }

    private fun generateWithAgent(
        job: LeasedMemoryJob,
        baseSystem: String,
        input: JSONObject,
        finalMaxTokens: Int,
    ): String {
        val executor = MemoryV2AgentToolExecutor(context, database, now)
        val messages = mutableListOf(
            ChatMessage(
                role = "system",
                content = baseSystem + "\n\n" + MemoryV2AgentProtocol.instructions,
            ),
            ChatMessage(
                role = "user",
                content = JSONObject(input.toString())
                    .put(
                        "agent_database_catalog",
                        MemoryV2AgentProtocol.databaseCatalog,
                    )
                    .toString(),
            ),
        )
        var toolCalls = 0
        while (true) {
            val raw = callWithBudget(
                messages = messages,
                maxTokens = finalMaxTokens,
            )
            when (val turn = MemoryV2AgentProtocol.parse(raw)) {
                is MemoryV2AgentTurn.FinalBatch -> return turn.json
                is MemoryV2AgentTurn.Invalid -> return raw
                is MemoryV2AgentTurn.ToolCall -> {
                    if (toolCalls >= MemoryV2AgentProtocol.MAX_READ_TOOL_CALLS) {
                        messages += ChatMessage("assistant", raw)
                        messages += ChatMessage(
                            "user",
                            MemoryV2AgentProtocol.FINAL_INSTRUCTION,
                        )
                        return callWithBudget(messages, finalMaxTokens)
                    }
                    val result = executor.execute(
                        scopeId = job.scopeId,
                        sessionId = job.sessionId,
                        call = turn,
                    )
                    toolCalls++
                    messages += ChatMessage("assistant", raw)
                    messages += ChatMessage(
                        role = "user",
                        content = JSONObject()
                            .put("memory_tool_result", turn.name)
                            .put("data_only", true)
                            .put("result", result)
                            .toString(),
                    )
                }
            }
        }
    }

    private fun callWithBudget(
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): String {
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
            val working = sessionWorkingState(db, job)
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
                    "working_summary_context",
                    JSONObject()
                        .put("reference_only", true)
                        .put("version", job.envelope.baseWorkingSummaryVersion)
                        .put("summary", working.first),
                )
                .put(
                    "recent_source_context",
                    JSONObject()
                        .put("reference_only", true)
                        .put("messages", recentSourceContext(db, job)),
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
            arrayOf("working_summary", "working_sources", "working_version"),
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
            if (
                cursor.getInt(cursor.getColumnIndexOrThrow("working_version")) !=
                job.envelope.baseWorkingSummaryVersion
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.STALE_SUMMARY)
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

    private fun recentSourceContext(
        db: SQLiteDatabase,
        job: LeasedMemoryJob,
    ): JSONArray {
        val limit = config.extractionRecentContextSources
        if (limit <= 0 || job.envelope.sources.isEmpty()) {
            return JSONArray()
        }
        val placeholders = job.envelope.sources.joinToString(",") { "?" }
        val sourceArgs = arrayOf(
            job.scopeId,
            job.sessionId,
            *job.envelope.sources.map { it.messageId }.toTypedArray(),
        )
        val firstSourceSeq = db.rawQuery(
            "SELECT MIN(seq) FROM messages WHERE scope_id=? AND session_id=? " +
                "AND message_id IN ($placeholders)",
            sourceArgs,
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                return JSONArray()
            }
            cursor.getLong(0)
        }
        val rows = db.rawQuery(
            "SELECT message_id,revision,content_sha256,role,body,occurred_at,timezone " +
                "FROM messages WHERE scope_id=? AND session_id=? AND seq<? " +
                "AND state='live' AND body IS NOT NULL ORDER BY seq DESC LIMIT ?",
            arrayOf(
                job.scopeId,
                job.sessionId,
                firstSourceSeq.toString(),
                limit.toString(),
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        JSONObject()
                            .put("message_id", cursor.getString(0).orEmpty())
                            .put("revision", cursor.getInt(1))
                            .put("sha256", cursor.getString(2).orEmpty())
                            .put("role", cursor.getString(3).orEmpty())
                            .put("text", cursor.getString(4).orEmpty())
                            .put("occurred_at", cursor.getLong(5))
                            .put("timezone", cursor.getString(6).orEmpty()),
                    )
                }
            }.asReversed()
        }
        return JSONArray(rows)
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
