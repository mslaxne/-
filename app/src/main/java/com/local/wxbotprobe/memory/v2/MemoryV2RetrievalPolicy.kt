package com.local.wxbotprobe.memory.v2

import kotlin.math.pow

object MemoryV2RetrievalPolicy {
    fun select(
        records: List<MemoryV2RetrievedRecord>,
        query: String,
        nowEpochSeconds: Long,
        halfLifeDays: Double,
        maxItems: Int,
    ): List<MemoryV2RetrievedRecord> {
        val terms = terms(query)
        val ranked = records
            .map { record ->
                record to score(
                    record = record,
                    terms = terms,
                    nowEpochSeconds = nowEpochSeconds,
                    halfLifeDays = halfLifeDays,
                )
            }
            .sortedWith(
                compareByDescending<Pair<MemoryV2RetrievedRecord, Double>> {
                    it.second
                }.thenByDescending { it.first.pinned }
                    .thenByDescending { it.first.updatedAt }
                    .thenBy { it.first.memoryId },
            )
            .map { it.first }
        val mandatoryA0 = ranked.filter { it.tier == "A0" }
        val others = ranked.filterNot { it.tier == "A0" }
        if (maxItems <= 0) {
            return mandatoryA0 + others
        }
        val remaining = (maxItems - mandatoryA0.size).coerceAtLeast(0)
        return mandatoryA0 + others.take(remaining)
    }

    fun terms(query: String): List<String> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) {
            return emptyList()
        }
        val result = linkedSetOf<String>()
        Regex("[a-z0-9_]{2,}").findAll(normalized).forEach { result += it.value }
        Regex("[\\u3400-\\u9fff]+").findAll(normalized).forEach { match ->
            val run = match.value
            for (index in 0 until run.length - 1) {
                val token = run.substring(index, index + 2)
                if (token !in STOP_WORDS) result += token
            }
        }
        return result.toList()
    }

    private fun score(
        record: MemoryV2RetrievedRecord,
        terms: List<String>,
        nowEpochSeconds: Long,
        halfLifeDays: Double,
    ): Double {
        val haystack = "${record.text}\n${record.predicate}"
        val matches = terms.count { haystack.contains(it, ignoreCase = true) }
        val relevance = if (terms.isEmpty()) {
            0.5
        } else {
            matches.toDouble() / terms.size
        }
        val ageDays = (
            (nowEpochSeconds - record.sourceAnchor).coerceAtLeast(0L) / 86_400.0
            )
        val recency = 2.0.pow(-ageDays / halfLifeDays.coerceAtLeast(1.0))
        val tierBoost = when (record.tier) {
            "A0" -> 1.0
            "A1" -> 0.8
            else -> 0.4
        }
        return relevance * 0.55 +
            record.retentionScore * 0.20 +
            recency * 0.15 +
            tierBoost * 0.10 +
            if (record.pinned) 0.05 else 0.0
    }

    private val STOP_WORDS = setOf(
        "我们",
        "你们",
        "他们",
        "这个",
        "那个",
        "就是",
        "然后",
        "什么",
    )
}
