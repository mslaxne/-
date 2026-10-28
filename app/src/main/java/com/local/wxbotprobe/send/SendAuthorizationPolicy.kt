package com.local.wxbotprobe.send

import com.local.wxbotprobe.chat.ChatIdentityPolicy
import com.local.wxbotprobe.chat.GroupTitleWarningPolicy
import com.local.wxbotprobe.chat.TextRules

data class AuthorizedSendChat(
    val chatFingerprint: Long,
    val normalizedTitle: String,
)

enum class SendAuthorizationIssue {
    UNRECOGNIZED_CHAT,
    GROUP_CHAT,
    CHAT_CHANGED,
}

data class SendAuthorizationResult(
    val binding: AuthorizedSendChat? = null,
    val issue: SendAuthorizationIssue? = null,
)

object SendAuthorizationPolicy {
    fun bind(
        chatFingerprint: Long,
        chatTitle: String,
        isGroup: Boolean,
    ): SendAuthorizationResult {
        val normalizedTitle = TextRules.normalise(chatTitle)
        if (
            chatFingerprint == 0L ||
            !ChatIdentityPolicy.isUsableTitle(normalizedTitle)
        ) {
            return SendAuthorizationResult(issue = SendAuthorizationIssue.UNRECOGNIZED_CHAT)
        }
        if (isGroup || GroupTitleWarningPolicy.isLikelyGroupTitle(chatTitle)) {
            return SendAuthorizationResult(issue = SendAuthorizationIssue.GROUP_CHAT)
        }
        return SendAuthorizationResult(
            binding = AuthorizedSendChat(
                chatFingerprint = chatFingerprint,
                normalizedTitle = normalizedTitle,
            ),
        )
    }

    fun check(
        binding: AuthorizedSendChat,
        chatFingerprint: Long,
        chatTitle: String,
    ): SendAuthorizationIssue? {
        return if (
            binding.chatFingerprint != chatFingerprint ||
            binding.normalizedTitle != TextRules.normalise(chatTitle)
        ) {
            SendAuthorizationIssue.CHAT_CHANGED
        } else {
            null
        }
    }

    fun message(issue: SendAuthorizationIssue): String {
        return when (issue) {
            SendAuthorizationIssue.UNRECOGNIZED_CHAT ->
                "当前聊天无法识别，代理已暂停，避免发送到错误对象。"
            SendAuthorizationIssue.GROUP_CHAT ->
                "当前是群聊，已禁止自动发送。请切换到私聊后重新启动代理。"
            SendAuthorizationIssue.CHAT_CHANGED ->
                "聊天已变化，自动发送已暂停。请确认当前私聊后重新启动代理。"
        }
    }
}
