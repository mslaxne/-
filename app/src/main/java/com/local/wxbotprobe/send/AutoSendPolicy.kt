package com.local.wxbotprobe.send

enum class AutoSendBlockReason {
    CHAT_UNAVAILABLE,
    QUIET_HOURS,
    DAILY_MESSAGE_LIMIT,
    DAILY_COST_LIMIT,
}

data class AutoSendPolicyInput(
    val chatTitle: String,
    val sentToday: Int,
    val remainingParts: Int,
    val spentUsdToday: Double,
    val dailyMessageLimit: Int,
    val dailyCostLimitUsd: Double,
    val quietHoursEnabled: Boolean,
    val quietStartMinute: Int,
    val quietEndMinute: Int,
    val currentMinute: Int,
)

object AutoSendPolicy {
    fun check(input: AutoSendPolicyInput): AutoSendBlockReason? {
        val title = input.chatTitle.trim()
        if (title.isBlank()) {
            return AutoSendBlockReason.CHAT_UNAVAILABLE
        }
        if (input.quietHoursEnabled && inQuietHours(
                input.currentMinute,
                input.quietStartMinute,
                input.quietEndMinute,
            )
        ) {
            return AutoSendBlockReason.QUIET_HOURS
        }
        if (input.sentToday + input.remainingParts > input.dailyMessageLimit) {
            return AutoSendBlockReason.DAILY_MESSAGE_LIMIT
        }
        if (input.spentUsdToday >= input.dailyCostLimitUsd) {
            return AutoSendBlockReason.DAILY_COST_LIMIT
        }
        return null
    }

    fun message(reason: AutoSendBlockReason): String {
        return when (reason) {
            AutoSendBlockReason.CHAT_UNAVAILABLE ->
                "当前聊天标题不可用，自动发送已暂停。"
            AutoSendBlockReason.QUIET_HOURS ->
                "当前处于静默时段，消息保留在队列。"
            AutoSendBlockReason.DAILY_MESSAGE_LIMIT ->
                "已达到每日自动发送条数上限，消息保留到次日。"
            AutoSendBlockReason.DAILY_COST_LIMIT ->
                "已达到每日 API 费用上限，自动发送保留到次日。"
        }
    }

    private fun inQuietHours(
        minute: Int,
        start: Int,
        end: Int,
    ): Boolean {
        if (start == end) {
            return true
        }
        return if (start < end) {
            minute in start until end
        } else {
            minute >= start || minute < end
        }
    }
}
