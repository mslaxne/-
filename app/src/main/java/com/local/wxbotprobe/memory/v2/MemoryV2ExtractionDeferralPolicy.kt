package com.local.wxbotprobe.memory.v2

object MemoryV2ExtractionDeferralPolicy {
    fun shouldWaitForMoreSources(
        previousSourceIds: List<String>,
        pendingSourceIds: List<String>,
        minimumNewSources: Int,
        sessionOpen: Boolean,
    ): Boolean {
        if (!sessionOpen || minimumNewSources <= 0) {
            return false
        }
        val previous = previousSourceIds.toSet()
        val deferred = pendingSourceIds.count { it in previous }
        if (deferred == 0) {
            return false
        }
        val newSources = pendingSourceIds.count { it !in previous }
        return newSources < minimumNewSources
    }
}
