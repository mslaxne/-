package com.local.wxbotprobe.memory.v2

import android.content.Context
import com.local.wxbotprobe.api.ModelSettings

enum class ReplyContextMode(val storedValue: String) {
    LAYERED("layered"),
    LEGACY("legacy"),
    ;

    companion object {
        fun fromStored(value: String?): ReplyContextMode {
            return entries.firstOrNull { it.storedValue == value } ?: LAYERED
        }
    }
}

object ReplyContextSettings {
    private const val PREFS = "reply_context_settings"
    private const val KEY_MODE = "mode"

    fun mode(context: Context): ReplyContextMode {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, ReplyContextMode.LAYERED.storedValue)
        return ReplyContextMode.fromStored(value)
    }

    fun setMode(context: Context, mode: ReplyContextMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.storedValue)
            .apply()
    }
}

data class ReplyContextPayload(
    val memoryText: String = "",
    val conversationMessages: List<MemoryV2ReplyContextMessage> = emptyList(),
    val mode: ReplyContextMode = ReplyContextMode.LEGACY,
    val snapshotSha256: String? = null,
    val providerId: String = "",
    val providerWindowTokens: Int = 0,
    val budgetOmittedMessageCount: Int = 0,
) {
    val includesConversation: Boolean
        get() = conversationMessages.isNotEmpty()
}

object ReplyContextFallbackPolicy {
    data class Result(
        val text: String,
        val source: ReplyContextMode,
        val error: String? = null,
    )

    fun build(
        primary: () -> String,
        fallback: () -> String,
    ): Result {
        val primaryText = runCatching(primary)
        if (primaryText.isSuccess && primaryText.getOrNull()?.isNotBlank() == true) {
            return Result(
                text = primaryText.getOrThrow(),
                source = ReplyContextMode.LAYERED,
            )
        }
        val primaryError = primaryText.exceptionOrNull()?.message
            ?: "layered context was blank"
        val fallbackText = runCatching(fallback)
        return Result(
            text = fallbackText.getOrDefault(""),
            source = ReplyContextMode.LEGACY,
            error = listOfNotNull(
                primaryError,
                fallbackText.exceptionOrNull()?.message,
            ).joinToString("; "),
        )
    }
}

class MemoryV2ReplyContextPayloadBuilder(
    context: Context,
    private val database: MemoryV2Database = MemoryV2Database(context),
    private val mode: () -> ReplyContextMode = {
        ReplyContextSettings.mode(context)
    },
    private val onLog: (String) -> Unit = {},
) {
    private val config = MemoryV2ConfigLoader.load(context)
    private val retrieval = MemoryV2RetrievalService(context, database)
    private val snapshots = MemoryV2ReplyContextSnapshotBuilder(context, database)
    private val providerId: () -> String = {
        ModelSettings.load(context).provider.storageId
    }

    fun build(
        scopeId: String,
        query: String,
    ): ReplyContextPayload {
        if (mode() == ReplyContextMode.LEGACY) {
            return ReplyContextPayload(
                memoryText = runCatching {
                    retrieval.buildContext(scopeId, query).text
                }.onFailure {
                    onLog("legacy reply context failed: ${it.stackTraceToString()}")
                }.getOrDefault(""),
                mode = ReplyContextMode.LEGACY,
            )
        }
        var layeredText = ""
        var layeredMessages = emptyList<MemoryV2ReplyContextMessage>()
        var layeredHash: String? = null
        var layeredBudgetOmitted = 0
        val result = ReplyContextFallbackPolicy.build(
            primary = {
                val budget = currentBudget()
                val snapshot = snapshots.build(
                    scopeId = scopeId,
                    maxPromptUtf8Bytes = budget.maxPromptUtf8Bytes,
                    recentRawMessages = config.replyContextRecentRawMessages,
                    providerId = budget.providerId,
                    providerWindowTokens = budget.contextWindowTokens,
                )
                layeredMessages = snapshot.layers.sessionMessages
                layeredHash = snapshot.snapshotSha256
                layeredBudgetOmitted = snapshot.budgetOmittedMessageCount
                layeredText = MemoryV2ReplyContextPolicy.render(
                    layers = snapshot.layers,
                    options = MemoryV2ReplyContextOptions(
                        exactRecentDays = config.replyContextExactDays,
                        expandedRecentDays = config.replyContextExpandedDays,
                        similarityThreshold = config.replyContextSimilarity,
                        timezone = config.timezone,
                    ),
                    timezone = config.timezone,
                    includeSessionMessages = false,
                )
                layeredText
            },
            fallback = {
                retrieval.buildContext(scopeId, query).text
            },
        )
        result.error?.let {
            onLog("layered reply context fallback: $it")
        }
        return ReplyContextPayload(
            memoryText = if (result.source == ReplyContextMode.LAYERED) {
                layeredText
            } else {
                result.text
            },
            conversationMessages = if (result.source == ReplyContextMode.LAYERED) {
                layeredMessages
            } else {
                emptyList()
            },
            mode = result.source,
            snapshotSha256 = if (result.source == ReplyContextMode.LAYERED) {
                layeredHash
            } else {
                null
            },
            providerId = providerId(),
            providerWindowTokens = currentBudget().contextWindowTokens,
            budgetOmittedMessageCount = if (
                result.source == ReplyContextMode.LAYERED
            ) {
                layeredBudgetOmitted
            } else {
                0
            },
        )
    }

    private fun currentBudget(): MemoryV2ReplyContextBudget {
        return MemoryV2ReplyContextBudgetPolicy.resolve(
            providerId = providerId(),
            providerWindows = config.replyContextProviderWindowTokens,
            outputReserveTokens = REPLY_OUTPUT_RESERVE_TOKENS,
            safetyMarginTokens = config.tokenPolicy.safetyMargin,
            framingFixedTokens = config.tokenPolicy.framingFixed +
                MemoryV2ReplyContextBudgetPolicy
                    .NON_CONTEXT_PROMPT_RESERVE_TOKENS,
            targetInputTokens = config.targetInputTokens,
        )
    }

    companion object {
        private const val REPLY_OUTPUT_RESERVE_TOKENS = 500
    }
}
