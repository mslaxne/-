package com.local.wxbotprobe.chat

import java.security.MessageDigest

data class StartupConversationMessage(
    val role: String,
    val text: String,
)

data class StartupConversationContext(
    val snapshotId: String,
    val messages: List<StartupConversationMessage>,
    val isGroup: Boolean,
    val lastMessageKind: BubbleKind,
    val lastMessageText: String,
) {
    val taskId: String
        get() = "$TASK_PREFIX$snapshotId"

    val shouldRespond: Boolean
        get() = lastMessageKind == BubbleKind.IN && lastMessageText.isNotBlank()

    val transcript: String
        get() = messages.joinToString("\n") {
            "${if (it.role == "user") "对方" else "我"}: ${it.text}"
        }

    private companion object {
        const val TASK_PREFIX = "startup-"
    }
}

object StartupConversationContextBuilder {
    fun build(
        snapshot: ScreenSnapshot,
        scopeKey: String,
    ): StartupConversationContext? {
        val ordered = snapshot.items.sortedBy { it.y }
        val last = ordered.lastOrNull() ?: return null
        val visible = ordered.mapNotNull(::toMessage)
        if (visible.isEmpty()) {
            return null
        }
        val lastMessage = toMessage(last)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identityText(scopeKey, snapshot, visible).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return StartupConversationContext(
            snapshotId = digest,
            messages = visible,
            isGroup = snapshot.isGroup,
            lastMessageKind = last.kind,
            lastMessageText = lastMessage?.text.orEmpty(),
        )
    }

    private fun toMessage(item: ScreenItem): StartupConversationMessage? {
        val role = when (item.kind) {
            BubbleKind.IN -> "user"
            BubbleKind.OUT -> "assistant"
            BubbleKind.UNKNOWN -> return null
        }
        val text = item.text.trim().ifBlank {
            if (item.image != null) IMAGE_PLACEHOLDER else ""
        }
        if (text.isBlank()) {
            return null
        }
        return StartupConversationMessage(role = role, text = text)
    }

    private fun identityText(
        scopeKey: String,
        snapshot: ScreenSnapshot,
        messages: List<StartupConversationMessage>,
    ): String {
        return buildString {
            append("v1\n")
            append(scopeKey.trim()).append('\n')
            append(snapshot.isGroup).append('\n')
            messages.forEach {
                append(it.role).append('\u0000')
                append(TextRules.normalise(it.text)).append('\n')
            }
        }
    }
}
