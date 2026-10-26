package com.local.wxbotprobe.ocr

import com.local.wxbotprobe.chat.BubbleKind
import com.local.wxbotprobe.chat.ScreenItem
import com.local.wxbotprobe.chat.TextRules
import java.security.MessageDigest
import kotlin.math.abs

data class LocalAuditCandidate(
    val text: String,
    val kind: BubbleKind,
    val y: Int,
    val xLeft: Int,
    val score: Double,
)

class AuditCandidateTracker(
    private val maxAgeMillis: Long = 30_000L,
    private val yTolerance: Int = 100,
) {
    enum class ConfirmationSource {
        FULL_FRAME,
        LOCAL_AUDIT,
    }

    data class Confirmation(
        val key: String,
        val chatFingerprint: Long,
        val text: String,
        val kind: BubbleKind,
        val y: Int,
        val xLeft: Int,
        val textLength: Int,
        val source: ConfirmationSource,
        val observations: Int,
    )

    private data class Pending(
        val key: String,
        val chatFingerprint: Long,
        val text: String,
        val displayText: String,
        val kind: BubbleKind,
        val y: Int,
        val xLeft: Int,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val observations: Int,
    )

    private val pending = mutableListOf<Pending>()

    @Synchronized
    fun observeAudit(
        candidates: List<LocalAuditCandidate>,
        chatFingerprint: Long,
        nowMillis: Long,
    ): List<Confirmation> {
        expire(nowMillis)
        if (chatFingerprint == 0L) {
            return emptyList()
        }
        val confirmations = mutableListOf<Confirmation>()
        candidates.forEach { candidate ->
            val normalised = TextRules.normalise(candidate.text)
            if (normalised.length < 2 || candidate.kind == BubbleKind.UNKNOWN) {
                return@forEach
            }
            val existing = pending.firstOrNull {
                it.chatFingerprint == chatFingerprint &&
                    it.kind == candidate.kind &&
                    abs(it.y - candidate.y) <= yTolerance &&
                    LocalOcrAuditPolicy.matches(it.text, normalised)
            }
            if (existing == null) {
                pending += Pending(
                    key = key(
                        chatFingerprint,
                        normalised,
                        candidate.kind,
                        candidate.y,
                    ),
                    chatFingerprint = chatFingerprint,
                    text = normalised,
                    displayText = candidate.text.trim(),
                    kind = candidate.kind,
                    y = candidate.y,
                    xLeft = candidate.xLeft,
                    firstSeenAt = nowMillis,
                    lastSeenAt = nowMillis,
                    observations = 1,
                )
            } else {
                pending.remove(existing)
                val observations = existing.observations + 1
                if (observations >= 2) {
                    confirmations += Confirmation(
                        key = existing.key,
                        chatFingerprint = chatFingerprint,
                        text = existing.displayText,
                        kind = candidate.kind,
                        y = candidate.y,
                        xLeft = candidate.xLeft,
                        textLength = existing.displayText.length,
                        source = ConfirmationSource.LOCAL_AUDIT,
                        observations = observations,
                    )
                } else {
                    pending += existing.copy(
                        lastSeenAt = nowMillis,
                        observations = observations,
                    )
                }
            }
        }
        return confirmations
    }

    @Synchronized
    fun observeFullFrame(
        items: List<ScreenItem>,
        chatFingerprint: Long,
        nowMillis: Long,
    ): List<Confirmation> {
        expire(nowMillis)
        if (chatFingerprint == 0L) {
            return emptyList()
        }
        val confirmations = mutableListOf<Confirmation>()
        pending.toList().forEach { candidate ->
            if (candidate.chatFingerprint != chatFingerprint) {
                return@forEach
            }
            val matched = items.any { item ->
                item.kind == candidate.kind &&
                    abs(item.y - candidate.y) <= yTolerance &&
                    LocalOcrAuditPolicy.matches(
                        candidate.text,
                        TextRules.normalise(item.text),
                    )
            }
            if (matched) {
                pending.remove(candidate)
                confirmations += Confirmation(
                    key = candidate.key,
                    chatFingerprint = candidate.chatFingerprint,
                    text = candidate.displayText,
                    kind = candidate.kind,
                    y = candidate.y,
                    xLeft = candidate.xLeft,
                    textLength = candidate.displayText.length,
                    source = ConfirmationSource.FULL_FRAME,
                    observations = candidate.observations,
                )
            }
        }
        return confirmations
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun clear() {
        pending.clear()
    }

    private fun expire(nowMillis: Long) {
        pending.removeAll { nowMillis - it.firstSeenAt > maxAgeMillis }
    }

    private fun key(
        chatFingerprint: Long,
        text: String,
        kind: BubbleKind,
        y: Int,
    ): String {
        val identity = buildString {
            append(java.lang.Long.toHexString(chatFingerprint))
            append('|')
            append(kind.name)
            append('|')
            append(y / yTolerance.coerceAtLeast(1))
            append('|')
            append(text)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }
}
