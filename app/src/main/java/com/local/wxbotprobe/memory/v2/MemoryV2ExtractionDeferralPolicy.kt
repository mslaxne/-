package com.local.wxbotprobe.memory.v2

object MemoryV2ExtractionDeferralPolicy {
    fun shouldWaitForMoreSources(
        previousSourceIds: List<String>,
        pendingSourceIds: List<String>,
        minimumNewSources: Int,
        sessionOpen: Boolean,
    ): Boolean {
        if (minimumNewSources <= 0) {
            return false
        }
        val previous = previousSourceIds.toSet()
        val pending = pendingSourceIds.toSet()
        if (previous.isNotEmpty() && pending == previous) {
            return true
        }
        if (!sessionOpen) {
            return false
        }
        val deferred = pending.count { it in previous }
        if (deferred == 0) {
            return false
        }
        val newSources = pending.count { it !in previous }
        return newSources < minimumNewSources
    }
}
