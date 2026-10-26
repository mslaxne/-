package com.local.wxbotprobe.memory.v2

data class MemoryV2ReplyContextBudget(
    val providerId: String,
    val contextWindowTokens: Int,
    val maxPromptUtf8Bytes: Int,
) {
    val bounded: Boolean
        get() = maxPromptUtf8Bytes > 0
}

data class MemoryV2ReplyContextMessageSelection(
    val messages: List<MemoryV2ReplyContextMessage>,
    val omittedMessageCount: Int,
)

object MemoryV2ReplyContextBudgetPolicy {
    const val NON_CONTEXT_PROMPT_RESERVE_TOKENS = 2_048

    fun resolve(
        providerId: String,
        providerWindows: Map<String, Int>,
        outputReserveTokens: Int,
        safetyMarginTokens: Int,
        framingFixedTokens: Int,
        targetInputTokens: Int,
    ): MemoryV2ReplyContextBudget {
        val window = providerWindows[providerId] ?: 0
        if (window <= 0) {
            return MemoryV2ReplyContextBudget(
                providerId = providerId,
                contextWindowTokens = 0,
                maxPromptUtf8Bytes = 0,
            )
        }
        val providerAvailable = (
            window -
                outputReserveTokens.coerceAtLeast(0) -
                safetyMarginTokens.coerceAtLeast(0) -
                framingFixedTokens.coerceAtLeast(0)
            ).coerceAtLeast(0)
        val available = if (targetInputTokens > 0) {
            minOf(targetInputTokens, providerAvailable)
        } else {
            providerAvailable
        }
        return MemoryV2ReplyContextBudget(
            providerId = providerId,
            contextWindowTokens = window,
            maxPromptUtf8Bytes = available,
        )
    }

    fun selectWithinBudget(
        messages: List<MemoryV2ReplyContextMessage>,
        coveredMessageIds: Set<String>,
        recentRawMessages: Int,
        maxPromptUtf8Bytes: Int,
        renderBytes: (List<MemoryV2ReplyContextMessage>) -> Int,
    ): MemoryV2ReplyContextMessageSelection {
        require(recentRawMessages >= 0) {
            "recentRawMessages must not be negative"
        }
        if (maxPromptUtf8Bytes <= 0 ||
            renderBytes(messages) <= maxPromptUtf8Bytes
        ) {
            return MemoryV2ReplyContextMessageSelection(messages, 0)
        }
        val protectedFrom = (messages.size - recentRawMessages)
            .coerceAtLeast(0)
        val removable = messages.indices
            .take(protectedFrom)
            .filter { index ->
                messages[index].messageId.isNotBlank() &&
                    messages[index].messageId in coveredMessageIds
            }
        if (removable.isEmpty()) {
            throw MemoryV2Exception(
                MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED,
                "reply context exceeds provider budget without covered history",
            )
        }
        var selected = messages.toList()
        var omitted = 0
        removable.forEach { index ->
            val messageId = messages[index].messageId
            selected = selected.filterNot { it.messageId == messageId }
            omitted += 1
            if (renderBytes(selected) <= maxPromptUtf8Bytes) {
                return MemoryV2ReplyContextMessageSelection(selected, omitted)
            }
        }
        throw MemoryV2Exception(
            MemoryV2ErrorCode.CONTEXT_BUDGET_EXCEEDED,
            "reply context still exceeds provider budget after covered-history compaction",
        )
    }
}
