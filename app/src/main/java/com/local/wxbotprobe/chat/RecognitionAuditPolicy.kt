package com.local.wxbotprobe.chat

data class RecognitionAuditInput(
    val primed: Boolean,
    val sameChat: Boolean,
    val frameChanged: Boolean,
    val previousItemCount: Int,
    val currentItemCount: Int,
    val previousOcrRowCount: Int,
    val currentOcrRowCount: Int,
    val burstCompleted: Boolean,
    val cooldownReady: Boolean,
    val pendingAuditCandidates: Boolean,
    val freshIncomingDetected: Boolean,
    val pendingBurst: Boolean,
)

object RecognitionAuditPolicy {
    private const val MIN_CHANGED_RATIO = 0.005
    private const val MIN_MEAN_CHANNEL_DIFF = 0.5

    fun frameChanged(meanDiff: Double?, changedRatio: Double?): Boolean {
        return (changedRatio ?: 0.0) >= MIN_CHANGED_RATIO ||
            (meanDiff ?: 0.0) >= MIN_MEAN_CHANNEL_DIFF
    }

    fun shouldRecheck(input: RecognitionAuditInput): Boolean {
        if (!input.primed ||
            !input.sameChat ||
            input.burstCompleted ||
            input.pendingBurst ||
            !input.cooldownReady
        ) {
            return false
        }
        if (input.freshIncomingDetected) {
            return false
        }
        if (input.pendingAuditCandidates) {
            return true
        }
        if (input.currentItemCount < input.previousItemCount ||
            input.currentOcrRowCount < input.previousOcrRowCount
        ) {
            return true
        }
        return input.frameChanged &&
            input.currentItemCount <= input.previousItemCount
    }
}
