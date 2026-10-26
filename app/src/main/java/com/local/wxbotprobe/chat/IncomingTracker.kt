package com.local.wxbotprobe.chat

import java.security.MessageDigest

class IncomingTracker(
    private val maxSeen: Int = 120,
    private val maxSent: Int = 40,
    private val fuzzyMinChars: Int = 20,
    private val fuzzyRatio: Double = 0.85,
    private val maxReappearUpwardShiftPx: Int = 180,
) {
    private val seen = ArrayDeque<Pair<String, Int>>()
    private val sent = ArrayDeque<String>()
    private var lastVisible = emptySet<Pair<String, Int>>()
    private val lastKnownY = mutableMapOf<Pair<String, Int>, Int>()

    fun noteSent(text: String) {
        sent.addLast(text)
        while (sent.size > maxSent) {
            sent.removeFirst()
        }
    }

    fun prime(items: List<ScreenItem>) {
        val incoming = incomingIds(items)
        for ((identity, _) in incoming) {
            remember(identity)
        }
        lastVisible = incoming.mapTo(mutableSetOf()) { it.first }
        incoming.forEach { (identity, item) -> lastKnownY[identity] = item.y }
    }

    fun collectFresh(items: List<ScreenItem>): List<ScreenItem> {
        val incoming = incomingIds(items)
        val fresh = mutableListOf<ScreenItem>()
        for ((identity, item) in incoming) {
            if (seenBefore(identity) &&
                !reappearedShortText(
                    identity = identity,
                    item = item,
                    previousY = lastKnownY[identity],
                )
            ) {
                continue
            }
            remember(identity)
            if (item.image == null && looksSelfAuthored(item.text)) {
                continue
            }
            fresh += item
        }
        lastVisible = incoming.mapTo(mutableSetOf()) { it.first }
        incoming.forEach { (identity, item) -> lastKnownY[identity] = item.y }
        return fresh
    }

    private fun reappearedShortText(
        identity: Pair<String, Int>,
        item: ScreenItem,
        previousY: Int?,
    ): Boolean {
        return item.image == null &&
            identity.first.length < fuzzyMinChars &&
            identity in seen &&
            identity !in lastVisible &&
            previousY != null &&
            item.y + maxReappearUpwardShiftPx >= previousY
    }

    private fun incomingIds(items: List<ScreenItem>): List<Pair<Pair<String, Int>, ScreenItem>> {
        val counts = mutableMapOf<String, Int>()
        return items.mapNotNull { item ->
            if (item.kind != BubbleKind.IN) {
                return@mapNotNull null
            }
            val key = item.image?.let { "图片:" + sha1Prefix(it) }
                ?: TextRules.normalise(item.text)
            val occurrence = (counts[key] ?: 0) + 1
            counts[key] = occurrence
            (key to occurrence) to item
        }
    }

    private fun remember(identity: Pair<String, Int>) {
        seen.addLast(identity)
        while (seen.size > maxSeen) {
            seen.removeFirst()
        }
    }

    private fun seenBefore(identity: Pair<String, Int>): Boolean {
        if (identity in seen) {
            return true
        }
        val (normalised, occurrence) = identity
        if (occurrence > 1 || normalised.length < fuzzyMinChars) {
            return false
        }
        for ((other, _) in seen) {
            if (other.length < fuzzyMinChars) {
                continue
            }
            if (TextRules.sequenceRatio(normalised, other) >= fuzzyRatio) {
                return true
            }
            val shorter = if (normalised.length <= other.length) normalised else other
            val longer = if (normalised.length <= other.length) other else normalised
            if (shorter.length >= fuzzyMinChars && shorter in longer) {
                return true
            }
        }
        return false
    }

    private fun looksSelfAuthored(text: String): Boolean {
        val target = TextRules.normalise(text)
        if (target.isEmpty()) {
            return false
        }
        return sent.any { candidate ->
            val normalised = TextRules.normalise(candidate)
            normalised.isNotEmpty() &&
                (target in normalised || TextRules.sequenceRatio(target, normalised) >= 0.8)
        }
    }

    private fun sha1Prefix(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
