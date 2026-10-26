package com.local.wxbotprobe

enum class AgentSetupStep {
    NONE,
    INPUT_METHOD,
    INPUT_METHOD_SELECT,
    ACCESSIBILITY,
    NOTIFICATION,
    BATTERY,
    DEVICE_PROFILE,
    API_KEY,
    OPEN_WECHAT,
}

data class AgentStartDecision(
    val message: String,
    val step: AgentSetupStep = AgentSetupStep.NONE,
    val warnings: List<AgentReliabilityWarning> = emptyList(),
) {
    val ready: Boolean
        get() = step == AgentSetupStep.OPEN_WECHAT
}

data class AgentReliabilityWarning(
    val step: AgentSetupStep,
    val message: String,
)

object AgentStartPolicy {
    fun evaluate(
        transportReady: Boolean,
        transportMessage: String,
        routeCRequiresIme: Boolean,
        routeCImeIssue: RouteCImeIssue?,
        accessibilityConnected: Boolean,
        batteryExempt: Boolean,
        apiKeyConfigured: Boolean,
        notificationGranted: Boolean = true,
        deviceProfileVerified: Boolean = true,
    ): AgentStartDecision {
        if (routeCRequiresIme && !accessibilityConnected) {
            return AgentStartDecision(
                message = "请先在 App 中启用无障碍保活",
                step = AgentSetupStep.ACCESSIBILITY,
            )
        }
        if (!transportReady) {
            return AgentStartDecision(transportMessage)
        }
        if (routeCRequiresIme && routeCImeIssue != null) {
            return AgentStartDecision(
                message = RouteCImePolicy.startupMessage(routeCImeIssue),
                step = if (routeCImeIssue == RouteCImeIssue.NOT_ENABLED) {
                    AgentSetupStep.INPUT_METHOD
                } else {
                    AgentSetupStep.INPUT_METHOD_SELECT
                },
            )
        }
        if (!accessibilityConnected) {
            return AgentStartDecision(
                message = "请先在 App 中启用无障碍保活",
                step = AgentSetupStep.ACCESSIBILITY,
            )
        }
        if (!notificationGranted) {
            return AgentStartDecision(
                message = "请先允许通知，代理需要持续显示运行状态",
                step = AgentSetupStep.NOTIFICATION,
            )
        }
        if (!apiKeyConfigured) {
            return AgentStartDecision(
                message = "请先在 App 中完成模型配置",
                step = AgentSetupStep.API_KEY,
            )
        }
        val warnings = buildList {
            if (!batteryExempt) {
                add(
                    AgentReliabilityWarning(
                        step = AgentSetupStep.BATTERY,
                        message = "电池优化白名单未启用，后台轮询可能被系统暂停",
                    ),
                )
            }
            if (!deviceProfileVerified) {
                add(
                    AgentReliabilityWarning(
                        step = AgentSetupStep.DEVICE_PROFILE,
                        message = "当前设备使用兼容模式，发送会走动态按钮检测",
                    ),
                )
            }
        }
        return AgentStartDecision(
            message = if (warnings.isEmpty()) {
                "代理已开启"
            } else {
                "代理已开启；" + warnings.joinToString("；") { it.message }
            },
            step = AgentSetupStep.OPEN_WECHAT,
            warnings = warnings,
        )
    }
}
