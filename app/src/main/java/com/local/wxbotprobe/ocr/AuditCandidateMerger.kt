package com.local.wxbotprobe.ocr

import com.local.wxbotprobe.chat.BubbleKind
import com.local.wxbotprobe.chat.ScreenItem
import com.local.wxbotprobe.chat.ScreenSnapshot
import com.local.wxbotprobe.chat.TextRules
import kotlin.math.abs

class AuditCandidateMerger(
    private val maxAgeMillis: Long = 30_000L,
    private val duplicateYTolerance: Int = 100,
    private val recentMergeMillis: Long = 60_000L,
) {
    data class MergeResult(
        val snapshot: ScreenSnapshot,
        val addedCount: Int,
    )

    private data class Pending(
        val confirmation: AuditCandidateTracker.Confirmation,
        val queuedAt: Long,
    )

    private data class Observed(
        val chatFingerprint: Long,
        val kind: BubbleKind,
        val text: String,
        val seenAt: Long,
    )

    private val pending = mutableListOf<Pending>()
    private val recentlyMerged = mutableMapOf<String, Long>()
    private val recentlyObserved = mutableListOf<Observed>()

    @Synchronized
    fun enqueue(
        confirmations: List<AuditCandidateTracker.Confirmation>,
        nowMillis: Long,
    ): Int {
        expire(nowMillis)
        confirmations
            .filter {
                it.source == AuditCandidateTracker.ConfirmationSource.LOCAL_AUDIT &&
                    it.chatFingerprint != 0L
            }
            .forEach { confirmation ->
                if (
                    confirmation.key !in recentlyMerged &&
                    pending.none { it.confirmation.key == confirmation.key }
                ) {
                    pending += Pending(confirmation, nowMillis)
                }
            }
        return pending.size
    }

    @Synchronized
    fun merge(
        snapshot: ScreenSnapshot,
        nowMillis: Long,
    ): MergeResult {
        expire(nowMillis)
        if (snapshot.chat.fingerprint == 0L) {
            return MergeResult(snapshot, 0)
        }
        pending.removeAll {
            it.confirmation.chatFingerprint != snapshot.chat.fingerprint
        }
        rememberObserved(snapshot, nowMillis)
        suppressRepeatedFragmentGroups(snapshot, nowMillis)

        val items = snapshot.items.toMutableList()
        var added = 0
        pending.toList().forEach { queued ->
            val confirmation = queued.confirmation
            val duplicate = items.any { item ->
                item.kind == confirmation.kind &&
                    abs(item.y - confirmation.y) <= duplicateYTolerance &&
                    LocalOcrAuditPolicy.matches(
                        TextRules.normalise(item.text),
                        confirmation.text,
                    )
            }
            if (!duplicate) {
                items += ScreenItem(
                    text = confirmation.text,
                    kind = confirmation.kind,
                    y = confirmation.y,
                    xLeft = confirmation.xLeft,
                )
                added++
            }
            recentlyMerged[confirmation.key] = nowMillis
            pending.remove(queued)
        }
        return MergeResult(
            snapshot = if (added > 0) {
                snapshot.copy(items = items.sortedBy { it.y })
            } else {
                snapshot
            },
            addedCount = added,
        )
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun clear() {
        pending.clear()
        recentlyMerged.clear()
        recentlyObserved.clear()
    }

    private fun expire(nowMillis: Long) {
        pending.removeAll { nowMillis - it.queuedAt > maxAgeMillis }
        recentlyMerged.entries.removeAll {
            nowMillis - it.value > recentMergeMillis
        }
        recentlyObserved.removeAll {
            nowMillis - it.seenAt > recentMergeMillis
        }
    }

    private fun rememberObserved(
        snapshot: ScreenSnapshot,
        nowMillis: Long,
    ) {
        snapshot.items.forEach { item ->
            val text = TextRules.normalise(item.text)
            if (text.isEmpty()) {
                return@forEach
            }
            val index = recentlyObserved.indexOfFirst {
                it.chatFingerprint == snapshot.chat.fingerprint &&
                    it.kind == item.kind &&
                    it.text == text
            }
            val observed = Observed(
                chatFingerprint = snapshot.chat.fingerprint,
                kind = item.kind,
                text = text,
                seenAt = nowMillis,
            )
            if (index >= 0) {
                recentlyObserved[index] = observed
            } else {
                recentlyObserved += observed
            }
        }
    }

    private fun suppressRepeatedFragmentGroups(
        snapshot: ScreenSnapshot,
        nowMillis: Long,
    ) {
        val candidates = pending
            .filter { it.confirmation.chatFingerprint == snapshot.chat.fingerprint }
            .sortedBy { it.confirmation.y }
        if (candidates.size < 2) {
            return
        }
        val groups = mutableListOf<MutableList<Pending>>()
        candidates.forEach { candidate ->
            val group = groups.lastOrNull()
            val previous = group?.lastOrNull()
            if (
                group != null &&
                previous != null &&
                previous.confirmation.kind == candidate.confirmation.kind &&
                kotlin.math.abs(
                    previous.confirmation.xLeft - candidate.confirmation.xLeft,
                ) <= FRAGMENT_X_TOLERANCE &&
                candidate.confirmation.y - previous.confirmation.y in
                0..FRAGMENT_Y_GAP_MAX
            ) {
                group += candidate
            } else {
                groups += mutableListOf(candidate)
            }
        }
        groups.filter { it.size >= 2 }.forEach { group ->
            val combined = TextRules.normalise(
                group.joinToString(separator = "") { it.confirmation.text },
            )
            val duplicate = recentlyObserved.any { observed ->
                observed.chatFingerprint == snapshot.chat.fingerprint &&
                    observed.kind == group.first().confirmation.kind &&
                    LocalOcrAuditPolicy.matches(observed.text, combined)
            }
            if (duplicate) {
                group.forEach { queued ->
                    recentlyMerged[queued.confirmation.key] = nowMillis
                    pending.remove(queued)
                }
            }
        }
    }

    private companion object {
        const val FRAGMENT_X_TOLERANCE = 40
        const val FRAGMENT_Y_GAP_MAX = 160
    }
}
