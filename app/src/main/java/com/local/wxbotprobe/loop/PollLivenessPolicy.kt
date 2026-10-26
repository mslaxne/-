package com.local.wxbotprobe.loop

object PollLivenessPolicy {
    const val STALL_THRESHOLD_MS = 15_000L

    fun shouldRestartWorker(
        running: Boolean,
        pollInFlight: Boolean,
        sinceLastPollMs: Long,
    ): Boolean {
        return running &&
            !pollInFlight &&
            sinceLastPollMs >= STALL_THRESHOLD_MS
    }
}
