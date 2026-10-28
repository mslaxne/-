package com.local.wxbotprobe.memory.v2

object MemoryV2CandidateTierPolicy {
    val candidateTiers = setOf("A0", "A1", "A2")

    fun resolve(
        explicitTier: String?,
        promptVersion: String,
        reason: String,
        requireRecentEvent: Boolean,
    ): String? {
        val normalized = explicitTier
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotBlank() }
        if (normalized != null) {
            if (normalized !in candidateTiers) {
                return null
            }
            if (requireRecentEvent && normalized != "A2") {
                return null
            }
            return normalized
        }
        val legacyPrompt = promptVersion.startsWith("extract-2.0.") ||
            promptVersion.startsWith("extract-2.1.") ||
            promptVersion.startsWith("summary-2.0.") ||
            promptVersion.startsWith("summary-2.1.")
        if (!legacyPrompt) {
            return null
        }
        return if (requireRecentEvent || reason == "recent_event") "A2" else "A1"
    }
}
