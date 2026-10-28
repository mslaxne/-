package com.local.wxbotprobe.memory.v2

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

class MemoryV2AgentToolExecutor(
    context: Context,
    private val database: MemoryV2Database,
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val retrieval = MemoryV2RetrievalService(context, database, now)

    fun execute(
        scopeId: String,
        sessionId: String,
        call: MemoryV2AgentTurn.ToolCall,
    ): JSONObject {
        return runCatching {
            when (call.name) {
                "memory.search" -> search(
                    scopeId,
                    call.arguments.optString("query"),
                    call.arguments,
                )
                "memory.list_active" -> search(scopeId, "", call.arguments)
                "session.read_recent" -> readRecent(
                    scopeId,
                    sessionId,
                    call.arguments,
                )
                "memory.pending_conflicts" -> pendingConflicts(scopeId)
                else -> failure("unknown tool ${call.name}")
            }
        }.getOrElse {
            failure(it.message ?: it.javaClass.simpleName)
        }
    }

    private fun search(
        scopeId: String,
        query: String,
        arguments: JSONObject,
    ): JSONObject {
        val limit = arguments.optInt("limit", 8).coerceIn(1, 12)
        val tiers = arguments.optJSONArray("tiers")
            ?.let(::stringSet)
            .orEmpty()
            .ifEmpty { setOf("A0", "A1", "A2") }
        val context = retrieval.buildContext(
            scopeId = scopeId,
            query = query,
            maxItems = (limit * 2).coerceAtMost(24),
        )
        val records = JSONArray()
        context.records
            .asSequence()
            .filter { it.tier in tiers }
            .take(limit)
            .forEach { record ->
                records.put(
                    JSONObject()
                        .put("memory_id", record.memoryId)
                        .put("version", record.version)
                        .put("tier", record.tier)
                        .put("predicate", record.predicate)
                        .put("text", record.text.take(1_000))
                        .put("temporal_json", record.temporalJson)
                        .put("expires_at", record.expiresAt ?: JSONObject.NULL)
                        .put("pinned", record.pinned),
                )
            }
        return success(records)
    }

    private fun readRecent(
        scopeId: String,
        sessionId: String,
        arguments: JSONObject,
    ): JSONObject {
        val limit = arguments.optInt("limit", 12).coerceIn(1, 20)
        val rows = database.readableDatabase.rawQuery(
            "SELECT message_id,seq,role,body,occurred_at,timezone " +
                "FROM messages WHERE scope_id=? AND session_id=? " +
                "AND state='live' AND body IS NOT NULL " +
                "ORDER BY seq DESC LIMIT ?",
            arrayOf(scopeId, sessionId, limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        JSONObject()
                            .put("message_id", cursor.string("message_id"))
                            .put("seq", cursor.getInt(cursor.getColumnIndexOrThrow("seq")))
                            .put("role", cursor.string("role"))
                            .put("text", cursor.string("body").take(2_000))
                            .put(
                                "occurred_at",
                                cursor.getLong(
                                    cursor.getColumnIndexOrThrow("occurred_at"),
                                ),
                            )
                            .put("timezone", cursor.string("timezone")),
                    )
                }
            }.asReversed()
        }
        return success(JSONArray(rows))
    }

    private fun pendingConflicts(scopeId: String): JSONObject {
        val rows = database.readableDatabase.rawQuery(
            "SELECT c.conflict_id,c.memory_id,c.target_version,c.operation," +
                "m.tier,m.text,m.version FROM memory_conflicts c " +
                "JOIN memories m ON m.scope_id=c.scope_id " +
                "AND m.memory_id=c.memory_id " +
                "WHERE c.scope_id=? AND c.state='pending' " +
                "AND c.target_version=m.version " +
                "ORDER BY c.created_at,c.conflict_id LIMIT 20",
            arrayOf(scopeId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        JSONObject()
                            .put("conflict_id", cursor.string("conflict_id"))
                            .put("memory_id", cursor.string("memory_id"))
                            .put(
                                "target_version",
                                cursor.getInt(
                                    cursor.getColumnIndexOrThrow("target_version"),
                                ),
                            )
                            .put("operation", cursor.string("operation"))
                            .put("tier", cursor.string("tier"))
                            .put("existing_text", cursor.string("text"))
                            .put(
                                "current_version",
                                cursor.getInt(
                                    cursor.getColumnIndexOrThrow("version"),
                                ),
                            ),
                    )
                }
            }
        }
        return success(JSONArray(rows))
    }

    private fun success(value: Any): JSONObject {
        return JSONObject().put("ok", true).put("result", value)
    }

    private fun failure(message: String): JSONObject {
        return JSONObject().put("ok", false).put("error", message)
    }

    private fun stringSet(array: JSONArray): Set<String> {
        return buildSet {
            for (index in 0 until array.length()) {
                val value = array.optString(index).uppercase()
                if (value in setOf("A0", "A1", "A2")) {
                    add(value)
                }
            }
        }
    }

    private fun android.database.Cursor.string(column: String): String {
        return getString(getColumnIndexOrThrow(column)).orEmpty()
    }
}
