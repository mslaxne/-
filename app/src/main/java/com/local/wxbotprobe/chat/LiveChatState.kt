package com.local.wxbotprobe.chat

data class CompletedBurst(
    val items: List<ScreenItem>,
    val isGroup: Boolean,
)

class LiveChatState(
    burstWaitMillis: Long = 4_000L,
    burstMaxAgeMillis: Long = 12_000L,
    private val baselineObservations: Int = 2,
) {
    private var tracker = IncomingTracker()
    private val burst = BurstCollector(burstWaitMillis, burstMaxAgeMillis)
    private var primed = false
    private var baselineCandidateCount = 0
    private var baselineCandidateFingerprint: Long? = null
    private var baselineCandidateTitle: String? = null
    private var activeChatFingerprint: Long? = null
    private var activeChatTitle: String? = null
    private var lastObservedIsGroup = false

    val isPrimed: Boolean
        get() = primed

    var lastFreshCount: Int = 0
        private set

    val hasPendingBurst: Boolean
        get() = burst.size > 0

    val burstDueAtMillis: Long?
        get() = burst.readyAt

    fun observe(
        items: List<ScreenItem>,
        isGroup: Boolean,
        nowMillis: Long,
    ): CompletedBurst? {
        return observe(items, isGroup, nowMillis, 0L, UNSPECIFIED_CHAT_TITLE)
    }

    fun observe(
        items: List<ScreenItem>,
        isGroup: Boolean,
        nowMillis: Long,
        chatFingerprint: Long,
    ): CompletedBurst? {
        return observe(
            items,
            isGroup,
            nowMillis,
            chatFingerprint,
            UNSPECIFIED_CHAT_TITLE,
        )
    }

    fun observe(
        items: List<ScreenItem>,
        isGroup: Boolean,
        nowMillis: Long,
        chatFingerprint: Long,
        chatTitle: String,
    ): CompletedBurst? {
        if (!ChatIdentityPolicy.isUsableTitle(chatTitle)) {
            lastFreshCount = 0
            return null
        }
        val normalizedTitle = TextRules.normalise(chatTitle)
        if (
            primed &&
            (
                activeChatFingerprint != chatFingerprint ||
                    activeChatTitle != normalizedTitle
                )
        ) {
            resetForNewBaseline()
        }
        if (!primed) {
            lastFreshCount = 0
            val sameBaselineCandidate =
                baselineCandidateFingerprint == chatFingerprint &&
                    baselineCandidateTitle == normalizedTitle
            if (!sameBaselineCandidate) {
                resetBaselineCandidate()
                tracker = IncomingTracker()
                burst.clear()
            }
            baselineCandidateCount++
            baselineCandidateFingerprint = chatFingerprint
            baselineCandidateTitle = normalizedTitle
            tracker.prime(items)
            activeChatFingerprint = chatFingerprint
            activeChatTitle = normalizedTitle
            lastObservedIsGroup = isGroup
            primed = baselineCandidateCount >= baselineObservations.coerceAtLeast(1)
            return null
        }

        lastObservedIsGroup = isGroup
        val fresh = tracker.collectFresh(items)
        lastFreshCount = fresh.size
        if (fresh.isNotEmpty()) {
            burst.add(fresh, nowMillis)
        }
        if (!burst.isReady(nowMillis)) {
            return null
        }
        return CompletedBurst(burst.drain(), isGroup)
    }

    fun drainBurstIfReady(nowMillis: Long): CompletedBurst? {
        if (!burst.isReady(nowMillis)) {
            return null
        }
        return CompletedBurst(burst.drain(), lastObservedIsGroup)
    }

    fun resetForNewBaseline() {
        tracker = IncomingTracker()
        burst.clear()
        primed = false
        resetBaselineCandidate()
        activeChatFingerprint = null
        activeChatTitle = null
        lastFreshCount = 0
        lastObservedIsGroup = false
    }

    private fun resetBaselineCandidate() {
        baselineCandidateCount = 0
        baselineCandidateFingerprint = null
        baselineCandidateTitle = null
    }

    private companion object {
        const val UNSPECIFIED_CHAT_TITLE = "__untitled_chat__"
    }
}
