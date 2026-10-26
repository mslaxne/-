package com.local.wxbotprobe.memory.v2

import kotlin.math.floor
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

data class MemoryTokenMessage(
    val role: String,
    val content: String,
)

data class MemoryModelProfile(
    val profileId: String,
    val provider: String,
    val model: String,
    val modelRevision: String,
    val contextWindowTokens: Int?,
    val outputMaxTokens: Int?,
    val verifiedAtEpochSeconds: Long?,
    val textOnly: Boolean,
    val thinkingEnabled: Boolean,
)

data class TokenBudgetRequest(
    val model: String,
    val messages: List<MemoryTokenMessage>,
    val maxTokens: Int,
    val thinkingDisabled: Boolean,
    val hasTools: Boolean = false,
)

data class TokenBudgetPolicyConfig(
    val targetInputTokens: Int = 0,
    val outputReserve: Int = 2_048,
    val safetyMargin: Int = 512,
    val fallbackInputFraction: Double = 1.0,
    val framingFixed: Int = 256,
    val framingPerMessage: Int = 64,
    val profileMaxAgeDays: Int = 30,
)

data class TokenBudgetDecision(
    val mode: String,
    val estimatedInputTokens: Int,
    val allowedInputTokens: Int,
    val outputReserve: Int,
    val contextWindowTokens: Int,
    val guaranteedExact: Boolean = false,
)

object MemoryV2TokenBudgetPolicy {
    fun estimate(
        request: TokenBudgetRequest,
        profile: MemoryModelProfile,
        nowEpochSeconds: Long,
        config: TokenBudgetPolicyConfig = TokenBudgetPolicyConfig(),
    ): TokenBudgetDecision {
        val contextWindow = profile.contextWindowTokens
            ?.takeIf { it > 0 }
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.MODEL_CONTEXT_UNKNOWN)
        val outputMax = profile.outputMaxTokens
            ?.takeIf { it > 0 }
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.MODEL_CONTEXT_UNKNOWN)
        if (request.model != profile.model || profile.modelRevision.isBlank()) {
            throw MemoryV2Exception(MemoryV2ErrorCode.MODEL_PROFILE_MISMATCH)
        }
        val verifiedAt = profile.verifiedAtEpochSeconds
            ?: throw MemoryV2Exception(MemoryV2ErrorCode.MODEL_PROFILE_STALE)
        val maximumAge = config.profileMaxAgeDays.toLong() * 86_400L
        if (nowEpochSeconds < verifiedAt || nowEpochSeconds - verifiedAt > maximumAge) {
            throw MemoryV2Exception(MemoryV2ErrorCode.MODEL_PROFILE_STALE)
        }
        if (profile.provider == "deepseek" && !request.thinkingDisabled) {
            throw MemoryV2Exception(MemoryV2ErrorCode.UNSUPPORTED_TOKEN_MODE)
        }
        if (!profile.textOnly || profile.thinkingEnabled || request.hasTools) {
            throw MemoryV2Exception(MemoryV2ErrorCode.UNSUPPORTED_TOKEN_MODE)
        }
        if (request.messages.isEmpty()) {
            throw MemoryV2Exception(MemoryV2ErrorCode.UNSUPPORTED_TOKEN_MODE)
        }
        request.messages.forEach { message ->
            if (
                message.role !in setOf("system", "user", "assistant") ||
                message.content.isBlank()
            ) {
                throw MemoryV2Exception(MemoryV2ErrorCode.UNSUPPORTED_TOKEN_MODE)
            }
        }
        if (request.maxTokens !in 1..outputMax) {
            throw MemoryV2Exception(MemoryV2ErrorCode.OUTPUT_BUDGET_INVALID)
        }
        val serialized = JSONArray().apply {
            request.messages.forEach { message ->
                put(
                    JSONObject()
                        .put("content", message.content)
                        .put("role", message.role),
                )
            }
        }.toString()
        val framing = config.framingFixed +
            config.framingPerMessage * request.messages.size
        val estimated = serialized.toByteArray(Charsets.UTF_8).size + framing
        val providerAvailable = floor(
            (contextWindow - request.maxTokens - config.safetyMargin)
                .coerceAtLeast(0) * config.fallbackInputFraction,
        ).toInt()
        val available = if (config.targetInputTokens > 0) {
            min(config.targetInputTokens, providerAvailable)
        } else {
            providerAvailable
        }
        if (available <= 0 || estimated > available) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED,
                "estimated=$estimated allowed=$available " +
                    "output=$outputMax context=$contextWindow",
            )
        }
        return TokenBudgetDecision(
            mode = "conservative_utf8_estimate",
            estimatedInputTokens = estimated,
            allowedInputTokens = available,
            outputReserve = request.maxTokens,
            contextWindowTokens = contextWindow,
        )
    }
}
