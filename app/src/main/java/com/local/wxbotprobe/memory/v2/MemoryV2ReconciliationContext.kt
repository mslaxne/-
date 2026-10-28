package com.local.wxbotprobe.memory.v2

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject

data class MemoryV2ContextEvidence(
    val messageId: String,
    val sourceRevision: Int,
    val quote: String,
)

data class MemoryV2ContextRelation(
    val sourceMemoryId: String,
    val targetMemoryId: String,
    val relationType: String,
    val targetVersion: Int,
)

data class MemoryV2ReconciliationMemory(
    val memoryId: String,
    val version: Int,
    val tier: String,
    val subjectId: String,
    val viewpointId: String?,
    val predicate: String,
    val text: String,
    val value: String,
    val temporalJson: String,
    val commitmentState: String?,
    val updatedAt: Long,
    val retentionScore: Double,
    val evidence: List<MemoryV2ContextEvidence>,
    val relations: List<MemoryV2ContextRelation>,
)

class MemoryV2ReconciliationContextReader(
    context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val now: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val config = MemoryV2ConfigLoader.load(context)

    fun selectForSources(
        scopeId: String,
        sourceIds: List<String>,
    ): List<MemoryV2ReconciliationMemory> {
        if (sourceIds.isEmpty()) {
            return emptyList()
        }
        val db = database.readableDatabase
        val sourceText = sourceTexts(db, scopeId, sourceIds)
        if (sourceText.isBlank()) {
            return emptyList()
        }
        val terms = searchTerms(sourceText)
        val coreRows = queryCandidates(
            db = db,
            scopeId = scopeId,
            terms = emptyList(),
            tier = "A0",
            limit = 0,
        )
        val keywordRows = if (terms.isEmpty()) {
            emptyList()
        } else {
            queryCandidates(
                db = db,
                scopeId = scopeId,
                terms = terms,
                tier = null,
                limit = config.retrievalCandidateLimit,
            )
        }
        val stableRows = queryCandidates(
            db = db,
            scopeId = scopeId,
            terms = emptyList(),
            tier = "A1",
            limit = config.retrievalSelectedLimit,
        )
        return (coreRows + keywordRows + stableRows)
            .distinctBy { it.memoryId }
            .map { it to score(it, terms) }
            .sortedWith(
                compareByDescending<Pair<MemoryV2ReconciliationMemory, Double>> {
                    it.second
                }.thenByDescending { it.first.tier == "A1" }
                    .thenByDescending { it.first.updatedAt }
                    .thenBy { it.first.memoryId },
            )
            .let { ranked ->
                if (config.retrievalSelectedLimit > 0) {
                    val core = ranked.filter { it.first.tier == "A0" }
                    val remaining = ranked.filterNot { it.first.tier == "A0" }
                        .take(
                            (config.retrievalSelectedLimit - core.size)
                                .coerceAtLeast(0),
                        )
                    core + remaining
                } else {
                    ranked
                }
            }
            .map { it.first }
    }

    fun loadFrozen(
        scopeId: String,
        targetVersions: Map<String, Int>,
    ): List<MemoryV2ReconciliationMemory> {
        if (targetVersions.isEmpty()) {
            return emptyList()
        }
        val db = database.readableDatabase
        val ids = targetVersions.keys.toList()
        val placeholders = ids.joinToString(",") { "?" }
        val rows = db.query(
            "memories",
            MEMORY_COLUMNS,
            "scope_id=? AND memory_id IN ($placeholders) " +
                "AND lifecycle='active' AND retracted_at IS NULL " +
                "AND (expires_at IS NULL OR expires_at>?)",
            arrayOf(scopeId, *ids.toTypedArray(), now().toString()),
            null,
            null,
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(readMemory(db, cursor))
                }
            }
        }.associateBy { it.memoryId }
        for ((memoryId, version) in targetVersions) {
            val row = rows[memoryId]
                ?: throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
            if (row.version != version) {
                throw MemoryV2Exception(MemoryV2ErrorCode.STALE_VERSION)
            }
        }
        return ids.mapNotNull(rows::get)
    }

    fun targetVersions(
        memories: List<MemoryV2ReconciliationMemory>,
    ): Map<String, Int> {
        return memories.associate { it.memoryId to it.version }
    }

    fun renderJson(
        memories: List<MemoryV2ReconciliationMemory>,
    ): JSONArray {
        return JSONArray().apply {
            memories.forEach { memory ->
                put(
                    JSONObject()
                        .put("memory_id", memory.memoryId)
                        .put("version", memory.version)
                        .put("tier", memory.tier)
                        .put("subject_id", memory.subjectId)
                        .put("viewpoint_id", memory.viewpointId ?: JSONObject.NULL)
                        .put("predicate", memory.predicate)
                        .put("text", memory.text)
                        .put("value", memory.value)
                        .put("temporal", JSONObject(memory.temporalJson))
                        .put(
                            "commitment_state",
                            memory.commitmentState ?: JSONObject.NULL,
                        )
                        .put(
                            "evidence",
                            JSONArray().apply {
                                memory.evidence.forEach { evidence ->
                                    put(
                                        JSONObject()
                                            .put("message_id", evidence.messageId)
                                            .put(
                                                "source_revision",
                                                evidence.sourceRevision,
                                            )
                                            .put("quote", evidence.quote),
                                    )
                                }
                            },
                        )
                        .put(
                            "relations",
                            JSONArray().apply {
                                memory.relations.forEach { relation ->
                                    put(
                                        JSONObject()
                                            .put(
                                                "source_memory_id",
                                                relation.sourceMemoryId,
                                            )
                                            .put(
                                                "target_memory_id",
                                                relation.targetMemoryId,
                                            )
                                            .put("relation_type", relation.relationType)
                                            .put(
                                                "target_version",
                                                relation.targetVersion,
                                            ),
                                    )
                                }
                            },
                        ),
                )
            }
        }
    }

    private fun queryCandidates(
        db: SQLiteDatabase,
        scopeId: String,
        terms: List<String>,
        tier: String?,
        limit: Int,
    ): List<MemoryV2ReconciliationMemory> {
        val selection = StringBuilder(
            "scope_id=? AND lifecycle='active' AND retracted_at IS NULL " +
                "AND (expires_at IS NULL OR expires_at>?)",
        )
        val args = mutableListOf(scopeId, now().toString())
        tier?.let {
            selection.append(" AND tier=?")
            args += it
        }
        if (terms.isNotEmpty()) {
            selection.append(" AND (")
            terms.forEachIndexed { index, term ->
                if (index > 0) selection.append(" OR ")
                val like = "%${escapeLike(term)}%"
                selection.append("text LIKE ? ESCAPE '\\' OR predicate LIKE ? ESCAPE '\\'")
                args += like
                args += like
            }
            selection.append(")")
        }
        return db.query(
            "memories",
            MEMORY_COLUMNS,
            selection.toString(),
            args.toTypedArray(),
            null,
            null,
            "pinned DESC,retention_score DESC,updated_at DESC,memory_id",
            MemoryV2LimitPolicy.sqliteLimit(limit).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(readMemory(db, cursor))
                }
            }
        }
    }

    private fun readMemory(
        db: SQLiteDatabase,
        cursor: Cursor,
    ): MemoryV2ReconciliationMemory {
        val memoryId = cursor.string("memory_id")
        return MemoryV2ReconciliationMemory(
            memoryId = memoryId,
            version = cursor.getInt(cursor.getColumnIndexOrThrow("version")),
            tier = cursor.string("tier"),
            subjectId = cursor.string("subject_id"),
            viewpointId = cursor.getNullableString("viewpoint_id"),
            predicate = cursor.string("predicate"),
            text = cursor.string("text"),
            value = decodeValue(cursor.string("value_json")),
            temporalJson = cursor.string("temporal_json"),
            commitmentState = cursor.getNullableString("commitment_state"),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
            retentionScore = cursor.getDouble(
                cursor.getColumnIndexOrThrow("retention_score"),
            ),
            evidence = readEvidence(db, cursor.string("scope_id"), memoryId),
            relations = readRelations(db, cursor.string("scope_id"), memoryId),
        )
    }

    private fun readEvidence(
        db: SQLiteDatabase,
        scopeId: String,
        memoryId: String,
    ): List<MemoryV2ContextEvidence> {
        return db.query(
            "evidence",
            arrayOf("source_message_id", "source_revision", "quote"),
            "scope_id=? AND memory_id=?",
            arrayOf(scopeId, memoryId),
            null,
            null,
            "captured_at DESC,evidence_id",
            "2",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MemoryV2ContextEvidence(
                            messageId = cursor.string("source_message_id"),
                            sourceRevision = cursor.getInt(
                                cursor.getColumnIndexOrThrow("source_revision"),
                            ),
                            quote = cursor.string("quote"),
                        ),
                    )
                }
            }
        }
    }

    private fun readRelations(
        db: SQLiteDatabase,
        scopeId: String,
        memoryId: String,
    ): List<MemoryV2ContextRelation> {
        return db.query(
            "memory_relations",
            arrayOf(
                "source_memory_id",
                "target_memory_id",
                "relation_type",
                "target_version",
            ),
            "scope_id=? AND (source_memory_id=? OR target_memory_id=?)",
            arrayOf(scopeId, memoryId, memoryId),
            null,
            null,
            "created_at DESC,relation_id",
            "8",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MemoryV2ContextRelation(
                            sourceMemoryId = cursor.string("source_memory_id"),
                            targetMemoryId = cursor.string("target_memory_id"),
                            relationType = cursor.string("relation_type"),
                            targetVersion = cursor.getInt(
                                cursor.getColumnIndexOrThrow("target_version"),
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun sourceTexts(
        db: SQLiteDatabase,
        scopeId: String,
        sourceIds: List<String>,
    ): String {
        val placeholders = sourceIds.joinToString(",") { "?" }
        return db.query(
            "messages",
            arrayOf("body"),
            "scope_id=? AND message_id IN ($placeholders) AND body IS NOT NULL",
            arrayOf(scopeId, *sourceIds.toTypedArray()),
            null,
            null,
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let(::add)
                }
            }.joinToString("\n")
        }
    }

    private fun score(
        memory: MemoryV2ReconciliationMemory,
        terms: List<String>,
    ): Double {
        val haystack = "${memory.text}\n${memory.value}\n${memory.predicate}"
        val matches = terms.count { haystack.contains(it, ignoreCase = true) }
        val relevance = if (terms.isEmpty()) {
            0.35
        } else {
            (matches.toDouble() / terms.size).coerceIn(0.0, 1.0)
        }
        val ageDays = (
            (now() - memory.updatedAt).coerceAtLeast(0L) / 86_400.0
            )
        val recency = 2.0.pow(-ageDays / config.retrievalHalfLifeDays)
        val tierBoost = if (memory.tier == "A1") 0.8 else 0.4
        return relevance * 0.55 +
            memory.retentionScore * 0.20 +
            recency * 0.15 +
            tierBoost * 0.10
    }

    private fun searchTerms(text: String): List<String> {
        val normalized = text.lowercase()
        val terms = linkedSetOf<String>()
        Regex("[a-z0-9_]{2,}").findAll(normalized).forEach {
            if (it.value !in ASCII_STOP_WORDS) terms += it.value
        }
        Regex("[\\u3400-\\u9fff]+").findAll(normalized).forEach { match ->
            val run = match.value
            if (run.length <= 6 && run !in CJK_STOP_WORDS) terms += run
            for (index in 0 until run.length - 1) {
                val token = run.substring(index, index + 2)
                if (token !in CJK_STOP_WORDS) terms += token
            }
        }
        return terms.take(MAX_SEARCH_TERMS)
    }

    private fun escapeLike(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    private fun decodeValue(raw: String): String {
        return runCatching {
            JSONArray("[$raw]").getString(0)
        }.getOrDefault(raw)
    }

    private fun Cursor.string(column: String): String {
        return getString(getColumnIndexOrThrow(column)).orEmpty()
    }

    private fun Cursor.getNullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    companion object {
        private val MEMORY_COLUMNS = arrayOf(
            "scope_id",
            "memory_id",
            "version",
            "tier",
            "subject_id",
            "viewpoint_id",
            "predicate",
            "text",
            "value_json",
            "temporal_json",
            "commitment_state",
            "updated_at",
            "retention_score",
        )
        private const val MAX_SEARCH_TERMS = 16
        private val ASCII_STOP_WORDS = setOf(
            "the",
            "and",
            "for",
            "with",
            "that",
            "this",
        )
        private val CJK_STOP_WORDS = setOf(
            "我们",
            "你们",
            "他们",
            "这个",
            "那个",
            "就是",
            "然后",
            "已经",
            "今天",
            "明天",
        )
    }
}
