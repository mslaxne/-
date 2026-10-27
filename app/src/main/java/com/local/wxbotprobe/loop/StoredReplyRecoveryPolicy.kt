package com.local.wxbotprobe.loop

enum class StoredReplyDisposition {
    ABSENT,
    REUSE,
    SKIP,
    MANUAL_REVIEW,
}

object StoredReplyRecoveryPolicy {
    fun decide(deliveryStatus: String?): StoredReplyDisposition {
        return when (deliveryStatus.orEmpty().trim().lowercase()) {
            "" -> StoredReplyDisposition.ABSENT
            "not_queued", "pending", "partial" -> StoredReplyDisposition.REUSE
            "sent", "duplicate_suppressed" -> StoredReplyDisposition.SKIP
            "unknown", "failed" -> StoredReplyDisposition.MANUAL_REVIEW
            else -> StoredReplyDisposition.MANUAL_REVIEW
        }
    }
}
