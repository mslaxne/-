package com.local.wxbotprobe.chat

class OutgoingConfirmationTracker(
    private val timeoutMillis: Long = 8_000L,
    private val fuzzyMinChars: Int = 20,
    private val fuzzyRatio: Double = 0.85,
) {
    enum class Outcome {
        CONFIRMED,
        AMBIGUOUS,
        NOT_OBSERVED,
    }

    data class Event(
        val token: String,
        val deliveryId: String,
        val partIndex: Int,
        val outcome: Outcome,
        val latencyMillis: Long,
    )

    private data class OutgoingIdentity(
        val text: String,
        val occurrence: Int,
    )

    private data class Baseline(
        val identities: Set<OutgoingIdentity>,
        val ready: Boolean,
    )

    private data class Pending(
        val token: String,
        val deliveryId: String,
        val partIndex: Int,
        val chatFingerprint: Long,
        val expectedText: String,
        val tappedAtMillis: Long,
        val baseline: Baseline,
    )

    private val lock = Any()
    private val baselines = mutableMapOf<Long, Baseline>()
    private val claimedIdentities = mutableMapOf<Long, MutableSet<OutgoingIdentity>>()
    private val pending = mutableListOf<Pending>()

    fun expect(
        token: String,
        deliveryId: String,
        partIndex: Int,
        chatFingerprint: Long,
        text: String,
        tappedAtMillis: Long,
    ) {
        val normalised = TextRules.normalise(text)
        if (normalised.isEmpty()) {
            return
        }
        synchronized(lock) {
            pending.removeAll { it.token == token }
            pending += Pending(
                token = token,
                deliveryId = deliveryId,
                partIndex = partIndex,
                chatFingerprint = chatFingerprint,
                expectedText = normalised,
                tappedAtMillis = tappedAtMillis,
                baseline = baselines[chatFingerprint] ?: Baseline(emptySet(), false),
            )
        }
    }

    fun observe(
        chatFingerprint: Long,
        items: List<ScreenItem>,
        nowMillis: Long,
    ): List<Event> {
        synchronized(lock) {
            val current = outgoingIds(items)
            val events = mutableListOf<Event>()
            val resolved = mutableSetOf<String>()
            val claimed = claimedIdentities.getOrPut(chatFingerprint) { mutableSetOf() }
            pending
                .filter { it.chatFingerprint == chatFingerprint }
                .sortedBy { it.tappedAtMillis }
                .forEach { expectation ->
                    if (!expectation.baseline.ready) {
                        val index = pending.indexOfFirst { it.token == expectation.token }
                        if (index >= 0) {
                            pending[index] = expectation.copy(
                                baseline = Baseline(current, true),
                            )
                        }
                        return@forEach
                    }
                    val newMatch = current.firstOrNull { identity ->
                        identity !in expectation.baseline.identities &&
                            identity !in claimed &&
                            matches(expectation.expectedText, identity.text)
                    }
                    if (newMatch != null) {
                        claimed += newMatch
                        events += event(expectation, Outcome.CONFIRMED, nowMillis)
                        resolved += expectation.token
                        return@forEach
                    }
                    if (nowMillis - expectation.tappedAtMillis < timeoutMillis) {
                        return@forEach
                    }
                    val ambiguous = current.any { identity ->
                        matches(expectation.expectedText, identity.text)
                    }
                    events += event(
                        expectation,
                        if (ambiguous) Outcome.AMBIGUOUS else Outcome.NOT_OBSERVED,
                        nowMillis,
                    )
                    resolved += expectation.token
                }
            pending.removeAll { it.token in resolved }
            if (pending.none { it.chatFingerprint == chatFingerprint }) {
                claimedIdentities.remove(chatFingerprint)
            }
            baselines[chatFingerprint] = Baseline(current, true)
            return events
        }
    }

    fun reset() {
        synchronized(lock) {
            baselines.clear()
            claimedIdentities.clear()
            pending.clear()
        }
    }

    private fun event(
        expectation: Pending,
        outcome: Outcome,
        nowMillis: Long,
    ): Event {
        return Event(
            token = expectation.token,
            deliveryId = expectation.deliveryId,
            partIndex = expectation.partIndex,
            outcome = outcome,
            latencyMillis = (nowMillis - expectation.tappedAtMillis).coerceAtLeast(0L),
        )
    }

    private fun outgoingIds(items: List<ScreenItem>): Set<OutgoingIdentity> {
        val counts = mutableMapOf<String, Int>()
        return items.mapNotNull { item ->
            if (item.kind != BubbleKind.OUT) {
                return@mapNotNull null
            }
            val text = TextRules.normalise(item.text)
            if (text.isEmpty()) {
                return@mapNotNull null
            }
            val occurrence = (counts[text] ?: 0) + 1
            counts[text] = occurrence
            OutgoingIdentity(text, occurrence)
        }.toSet()
    }

    private fun matches(expected: String, observed: String): Boolean {
        if (expected == observed) {
            return true
        }
        if (expected.length < fuzzyMinChars || observed.length < fuzzyMinChars) {
            return false
        }
        if (expected in observed || observed in expected) {
            return true
        }
        return TextRules.sequenceRatio(expected, observed) >= fuzzyRatio
    }
}
