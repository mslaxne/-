package com.local.wxbotprobe.memory.v2

enum class MemoryV2ConflictConfirmationDecision {
    NOT_REQUIRED,
    ALLOW_UPDATE,
    ASK_CONFIRMATION,
    KEEP_EXISTING,
    PROTECTED,
    IGNORE_A3,
}

object MemoryV2ConflictConfirmationPolicy {
    fun decide(
        existingTier: String,
        candidateOperation: String,
        subjectKind: String,
        predicate: String,
        userSourceTexts: List<String>,
    ): MemoryV2ConflictConfirmationDecision {
        if (existingTier !in setOf("A0", "A1", "A2")) {
            return MemoryV2ConflictConfirmationDecision.IGNORE_A3
        }
        if (candidateOperation != MemoryV2ReconciliationPolicy.SUPERSEDE) {
            return MemoryV2ConflictConfirmationDecision.NOT_REQUIRED
        }
        if (isProtectedPersona(subjectKind, predicate)) {
            return MemoryV2ConflictConfirmationDecision.PROTECTED
        }
        val joined = userSourceTexts.joinToString("\n")
        if (existingTier == "A0") {
            return MemoryV2ConflictConfirmationDecision.ASK_CONFIRMATION
        }
        if (EXPLICIT_CORRECTION.containsMatchIn(joined)) {
            return MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE
        }
        if (AMBIGUOUS_ACKNOWLEDGEMENT.containsMatchIn(joined)) {
            return MemoryV2ConflictConfirmationDecision.KEEP_EXISTING
        }
        if (EXPLICIT_CHANGE.containsMatchIn(joined)) {
            return MemoryV2ConflictConfirmationDecision.ALLOW_UPDATE
        }
        return MemoryV2ConflictConfirmationDecision.ASK_CONFIRMATION
    }

    fun isProtectedPersona(
        subjectKind: String,
        predicate: String,
    ): Boolean {
        return subjectKind == "agent" &&
            (
                predicate.startsWith("profile.") ||
                    predicate.startsWith("persona.") ||
                    predicate.startsWith("config.")
                )
    }

    fun isAmbiguousAcknowledgement(userSourceTexts: List<String>): Boolean {
        val joined = userSourceTexts.joinToString("\n")
        return AMBIGUOUS_ACKNOWLEDGEMENT.containsMatchIn(joined) &&
            !EXPLICIT_CORRECTION.containsMatchIn(joined)
    }

    fun isExplicitCorrectionOrChange(userSourceTexts: List<String>): Boolean {
        val joined = userSourceTexts.joinToString("\n")
        return EXPLICIT_CORRECTION.containsMatchIn(joined) ||
            EXPLICIT_CHANGE.containsMatchIn(joined)
    }

    private val EXPLICIT_CORRECTION = Regex(
        "(你|您)(确实|的确|真的|肯定|明明)?(可能|是不是|应该)?" +
            "(记错|记反|搞错|说错)" +
            "|(你|您)说的(不对|错了)" +
            "|不是(你|您)(说|记)的",
    )
    private val EXPLICIT_CHANGE = Regex(
        "(现在|如今|已经|后来|以后).{0,6}(不是|不在|不再|变|改|换)" +
            "|(现在|如今|已经|以后)是" +
            "|(现在|如今|已经|后来|早就|以后).{1,12}(了|过)" +
            "|(现在|如今|以后)更?(喜欢|偏爱|爱吃|爱喝|想要)" +
            "|(现在|如今|已经|早就|以后).{0,8}(不喜欢|不再喜欢|吃腻|喝腻|讨厌)" +
            "|(现在|如今|以后).{0,8}(住|在|做|一直)" +
            "|我(已经)?(改主意|改变想法)了" +
            "|(搬到|搬去|换到|改到).{0,12}(了|现在)" +
            "|不是.{0,12}[，,]\\s*(是|改成)" +
            "|(改成|改到|换成|变成|更新为)",
    )
    private val AMBIGUOUS_ACKNOWLEDGEMENT = Regex(
        "我记错了|好像(是|哦|吧)?|这样啊|原来如此|" +
            "可能(是)?吧|也许(是)?吧|是吗",
    )
}
