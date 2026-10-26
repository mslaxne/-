package com.local.wxbotprobe.memory.v2

import android.content.Context
import java.time.OffsetDateTime
import org.json.JSONObject

data class MemoryResourceLimit(
    val maxRecords: Int,
    val maxBytes: Long,
)

data class MemoryTierLimit(
    val maxRecords: Int?,
    val maxBytes: Long?,
    val ttlSeconds: Long?,
)

data class MemoryV2Config(
    val enabled: Boolean,
    val timezone: String,
    val sourceCacheSeconds: Long,
    val historySeconds: Long,
    val historyVersions: Int,
    val rowOverheadBytes: Long,
    val maxItemUtf8Bytes: Int,
    val maxQuoteUtf8Bytes: Int,
    val maxMessageUtf8Bytes: Int,
    val resources: Map<String, MemoryResourceLimit>,
    val tiers: Map<String, MemoryTierLimit>,
    val tokenPolicy: TokenBudgetPolicyConfig,
    val retrievalCandidateLimit: Int,
    val retrievalSelectedLimit: Int,
    val replyRetrievalCandidateLimit: Int,
    val replyRetrievalSelectedLimit: Int,
    val replyContextExactDays: Int,
    val replyContextExpandedDays: Int,
    val replyContextSimilarity: Double,
    val retrievalHalfLifeDays: Double,
    val targetInputTokens: Int,
    val modelProfileMaxAgeDays: Int,
    val maxEvidencePerMemory: Int,
    val backgroundMaxSources: Int,
    val backgroundMinBatchMessages: Int,
    val backgroundMaxAttempts: Int,
    val backgroundLeaseSeconds: Long,
    val backgroundBackoffSeconds: List<Long>,
    val maxCallsPerScopeHour: Int,
    val maxCallsPerDeviceHour: Int,
    val replyContextRecentRawMessages: Int,
    val replyContextProviderWindowTokens: Map<String, Int>,
    val workingSummaryMaxUtf8Bytes: Int,
    val versions: Map<String, String>,
)

object MemoryV2ConfigLoader {
    fun load(context: Context): MemoryV2Config {
        val root = context.assets.open("memory_v2/memory_config.json")
            .bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val capacity = root.getJSONObject("capacity")
        val retention = root.getJSONObject("retention")
        val contextConfig = root.getJSONObject("context")
        val background = root.getJSONObject("background")
        val retrieval = root.getJSONObject("retrieval")
        val resourcesJson = root.getJSONObject("resources")
        val tiersJson = root.getJSONObject("tiers")
        val resources = buildMap {
            resourcesJson.keys().forEach { name ->
                val item = resourcesJson.getJSONObject(name)
                put(
                    name,
                    MemoryResourceLimit(
                        maxRecords = item.optInt("max_records", 0),
                        maxBytes = item.optLong("max_bytes", 0L),
                    ),
                )
            }
        }
        val versions = buildMap {
            val value = root.optJSONObject("versions") ?: JSONObject()
            value.keys().forEach { key ->
                put(key, value.optString(key))
            }
        }
        val tiers = buildMap {
            tiersJson.keys().forEach { name ->
                val item = tiersJson.getJSONObject(name)
                put(
                    name,
                    MemoryTierLimit(
                        maxRecords = if (item.isNull("max_records")) {
                            null
                        } else {
                            item.optInt("max_records")
                        },
                        maxBytes = if (item.isNull("max_bytes")) {
                            null
                        } else {
                            item.optLong("max_bytes")
                        },
                        ttlSeconds = item.optLong("ttl_seconds")
                            .takeIf { item.has("ttl_seconds") },
                    ),
                )
            }
        }
        val policy = TokenBudgetPolicyConfig(
            targetInputTokens = contextConfig.optInt("target_input_tokens", 0),
            outputReserve = contextConfig.optInt("output_reserve", 2_048),
            safetyMargin = contextConfig.optInt("safety_margin", 512),
            fallbackInputFraction = contextConfig.optDouble(
                "fallback_input_fraction",
                1.0,
            ),
            framingFixed = contextConfig.optInt("framing_fixed", 256),
            framingPerMessage = contextConfig.optInt("framing_per_message", 64),
            profileMaxAgeDays = contextConfig.optInt("profile_max_age_days", 30),
        )
        val providerWindows = buildMap {
            val values = contextConfig.optJSONObject(
                "reply_context_provider_window_tokens",
            ) ?: JSONObject()
            values.keys().forEach { provider ->
                put(provider, values.optInt(provider, 0))
            }
        }
        return MemoryV2Config(
            enabled = root.optBoolean("enabled", false),
            timezone = root.optString("timezone", "Asia/Shanghai"),
            sourceCacheSeconds = retention.optLong("source_cache_seconds", 604_800L),
            historySeconds = retention.optLong("history_seconds", 2_592_000L),
            historyVersions = retention.optInt("history_versions", 3),
            rowOverheadBytes = capacity.optLong("row_overhead_bytes", 64L),
            maxItemUtf8Bytes = capacity.optInt("max_item_utf8_bytes", 8_192),
            maxQuoteUtf8Bytes = capacity.optInt("max_quote_utf8_bytes", 2_048),
            maxMessageUtf8Bytes = root.getJSONObject("hot_path")
                .optInt("max_message_utf8_bytes", 65_536),
            resources = resources,
            tiers = tiers,
            tokenPolicy = policy,
            retrievalCandidateLimit = retrieval.optInt("candidate_limit", 0),
            retrievalSelectedLimit = retrieval.optInt("selected_limit", 0),
            replyRetrievalCandidateLimit = retrieval.optInt(
                "reply_candidate_limit",
                0,
            ),
            replyRetrievalSelectedLimit = retrieval.optInt(
                "reply_selected_limit",
                0,
            ),
            replyContextExactDays = retrieval.optInt(
                "reply_context_exact_days",
                3,
            ).coerceAtLeast(1),
            replyContextExpandedDays = retrieval.optInt(
                "reply_context_expanded_days",
                7,
            ).coerceAtLeast(1),
            replyContextSimilarity = retrieval.optDouble(
                "reply_context_similarity",
                0.65,
            ).coerceIn(0.1, 1.0),
            retrievalHalfLifeDays = retrieval.optDouble(
                "recency_half_life_days",
                30.0,
            ).coerceAtLeast(1.0),
            targetInputTokens = policy.targetInputTokens,
            modelProfileMaxAgeDays = policy.profileMaxAgeDays,
            maxEvidencePerMemory = capacity.optInt("max_evidence_per_memory", 8),
            backgroundMaxSources = background.optInt("max_sources", 0),
            backgroundMinBatchMessages = background.optInt(
                "min_batch_messages",
                8,
            ).coerceAtLeast(1),
            backgroundMaxAttempts = background.optInt("max_attempts", 3),
            backgroundLeaseSeconds = background.optLong("lease_seconds", 60L),
            backgroundBackoffSeconds = buildList {
                val values = background.optJSONArray("backoff_seconds")
                if (values == null) {
                    addAll(listOf(1L, 5L, 30L))
                } else {
                    for (index in 0 until values.length()) {
                        add(values.optLong(index))
                    }
                }
            },
            maxCallsPerScopeHour = background.optInt(
                "max_llm_calls_per_scope_hour",
                12,
            ),
            maxCallsPerDeviceHour = background.optInt(
                "max_llm_calls_per_device_hour",
                60,
            ),
            replyContextRecentRawMessages = contextConfig.optInt(
                "reply_context_recent_raw_messages",
                8,
            ).coerceAtLeast(2),
            replyContextProviderWindowTokens = providerWindows,
            workingSummaryMaxUtf8Bytes = contextConfig.optInt(
                "working_summary_max_utf8_bytes",
                8_192,
            ).coerceAtLeast(1_024),
            versions = versions,
        )
    }

    fun loadModelProfile(
        context: Context,
        profileId: String,
    ): MemoryModelProfile {
        val root = context.assets.open("memory_v2/model_profiles.json")
            .bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val profiles = root.getJSONArray("profiles")
        for (index in 0 until profiles.length()) {
            val item = profiles.optJSONObject(index) ?: continue
            if (item.optString("profile_id") != profileId) {
                continue
            }
            val verifiedAt = item.optString("verified_at")
                .takeIf { it.isNotBlank() }
                ?.let {
                    runCatching {
                        OffsetDateTime.parse(it).toEpochSecond()
                    }.getOrNull()
                }
            return MemoryModelProfile(
                profileId = profileId,
                provider = item.optString("provider"),
                model = item.optString("model"),
                modelRevision = item.optString("model_revision"),
                contextWindowTokens = item.optInt("context_window_tokens")
                    .takeIf { item.has("context_window_tokens") && !item.isNull("context_window_tokens") },
                outputMaxTokens = item.optInt("output_max_tokens")
                    .takeIf { item.has("output_max_tokens") && !item.isNull("output_max_tokens") },
                verifiedAtEpochSeconds = verifiedAt,
                textOnly = item.optBoolean("text_only", false),
                thinkingEnabled = item.optBoolean("thinking_enabled", false),
            )
        }
        throw MemoryV2Exception(
            MemoryV2ErrorCode.MODEL_PROFILE_MISMATCH,
            "unknown profile $profileId",
        )
    }
}

object MemoryV2Settings {
    private const val PREFS = "memory_v2_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_READ_PATH = "read_path"
    private const val KEY_ACTIVE_RUN = "active_run_id"

    fun enabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, MemoryV2ConfigLoader.load(context).enabled)
    }

    fun readPathSnapshot(context: Context): MemoryV2ReadPathSnapshot {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = when (preferences.getString(KEY_READ_PATH, null)) {
            MemoryV2ReadPath.V2.name -> MemoryV2ReadPath.V2
            else -> MemoryV2ReadPath.LEGACY
        }
        return MemoryV2ReadPathSnapshot(
            path = path,
            enabled = preferences.getBoolean(
                KEY_ENABLED,
                MemoryV2ConfigLoader.load(context).enabled,
            ),
            activeRunId = preferences.getString(KEY_ACTIVE_RUN, null),
        )
    }

    fun readPath(context: Context): MemoryV2ReadPath {
        return readPathSnapshot(context).path
    }

    internal fun activateForRead(context: Context, runId: String): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_READ_PATH, MemoryV2ReadPath.V2.name)
            .putString(KEY_ACTIVE_RUN, runId)
            .commit()
    }

    internal fun rollbackReadPath(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, false)
            .putString(KEY_READ_PATH, MemoryV2ReadPath.LEGACY.name)
            .remove(KEY_ACTIVE_RUN)
            .commit()
    }

    internal fun setEnabledForTesting(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    internal fun setReadPathForTesting(
        context: Context,
        path: MemoryV2ReadPath,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_READ_PATH, path.name)
            .apply()
    }
}
