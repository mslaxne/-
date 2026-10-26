package com.local.wxbotprobe

enum class RouteCImeIssue {
    NOT_ENABLED,
    NOT_DEFAULT,
    TARGET_NOT_CONNECTED,
    TARGET_NOT_WECHAT,
}

data class RouteCImeReadiness(
    val enabled: Boolean,
    val isDefault: Boolean,
) {
    val ready: Boolean
        get() = enabled && isDefault
}

object RouteCImePolicy {
    const val BOT_IME = "com.local.wxbotprobe/.BotImeService"
    const val WECHAT_PACKAGE = "com.tencent.mm"

    fun isBotImeComponent(component: String?): Boolean {
        return component == BOT_IME ||
            component == "com.local.wxbotprobe/com.local.wxbotprobe.BotImeService"
    }

    fun readiness(
        enabled: Boolean,
        isDefault: Boolean,
    ): RouteCImeReadiness = RouteCImeReadiness(enabled, isDefault)

    fun startupIssue(
        enabled: Boolean,
        isDefault: Boolean,
    ): RouteCImeIssue? {
        return when {
            !enabled -> RouteCImeIssue.NOT_ENABLED
            !isDefault -> RouteCImeIssue.NOT_DEFAULT
            else -> null
        }
    }

    fun writeIssue(
        enabled: Boolean,
        isDefault: Boolean,
        targetPackage: String?,
    ): RouteCImeIssue? {
        startupIssue(enabled, isDefault)?.let { return it }
        if (targetPackage.isNullOrBlank()) {
            return RouteCImeIssue.TARGET_NOT_CONNECTED
        }
        if (targetPackage != WECHAT_PACKAGE) {
            return RouteCImeIssue.TARGET_NOT_WECHAT
        }
        return null
    }

    fun startupMessage(issue: RouteCImeIssue): String {
        return when (issue) {
            RouteCImeIssue.NOT_ENABLED ->
                "BotIme 未启用。路线 C 已拒绝启动。"
            RouteCImeIssue.NOT_DEFAULT ->
                "BotIme 已启用，但不是系统默认输入法。路线 C 已拒绝启动。"
            RouteCImeIssue.TARGET_NOT_CONNECTED ->
                "微信输入框尚未连接到 BotIme。路线 C 已拒绝启动。"
            RouteCImeIssue.TARGET_NOT_WECHAT ->
                "当前输入目标不是微信。路线 C 已拒绝启动。"
        }
    }

    fun writeMessage(
        issue: RouteCImeIssue,
        targetPackage: String? = null,
    ): String {
        return when (issue) {
            RouteCImeIssue.NOT_ENABLED ->
                "BotIme 未启用。路线 C 已拒绝写入。"
            RouteCImeIssue.NOT_DEFAULT ->
                "BotIme 已启用，但不是系统默认输入法。路线 C 已拒绝写入。"
            RouteCImeIssue.TARGET_NOT_CONNECTED ->
                "微信输入框尚未连接到 BotIme。路线 C 已拒绝写入。"
            RouteCImeIssue.TARGET_NOT_WECHAT ->
                "当前输入目标是 ${targetPackage ?: "<unknown>"}，不是微信。" +
                    "路线 C 已拒绝写入。"
        }
    }
}

class ImeSendSessionState {
    var imeFlowEntered: Boolean = false
        private set

    var inputOpened: Boolean = false
        private set

    fun enterFlow() {
        imeFlowEntered = true
    }

    fun markInputOpened() {
        require(imeFlowEntered) {
            "Cannot mark an input as opened before entering the IME flow."
        }
        inputOpened = true
    }

    fun shouldDismissKeyboard(cleanupAllowed: Boolean): Boolean {
        return imeFlowEntered && inputOpened && cleanupAllowed
    }
}
