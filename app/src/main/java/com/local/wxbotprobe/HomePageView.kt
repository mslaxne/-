package com.local.wxbotprobe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.local.wxbotprobe.api.ApiUsageStore
import com.local.wxbotprobe.api.ModelSettings
import com.local.wxbotprobe.bot.ReplyRetryStatus
import com.local.wxbotprobe.bot.ReplyRetryStore
import com.local.wxbotprobe.device.DeviceProfileMode
import com.local.wxbotprobe.device.DeviceProfileResolver
import com.local.wxbotprobe.memory.v2.MemoryV2AdminStore
import com.local.wxbotprobe.send.DeliveryStore
import com.local.wxbotprobe.send.AutoSendUsageStore
import java.io.File
import java.util.Locale

class HomePageView(
    private val context: Context,
    onToggleAgent: () -> Unit,
    onStopAgent: () -> Unit,
    private val onToggleBubble: (Boolean) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val view: LinearLayout
    val statusText: TextView
    val deploymentText: TextView

    private val statePanel: LinearLayout
    private val stateBadge: TextView
    private val stateTitle: TextView
    private val stateDetail: TextView
    private val deploymentIcon: ImageView
    private val agentButton: Button
    private val stopAgentButton: Button
    private lateinit var bubbleSwitch: Switch
    private lateinit var bubbleDetail: TextView

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        root.addView(MainUiComponents.sectionLabel(context, "代理"))
        statePanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppUi.rounded(context, AppUi.surfaceSubtle, 8f)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        stateBadge = AppUi.textView(
            context,
            "已停止",
            13f,
            AppUi.textSecondary,
            medium = true,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(5), dp(11), dp(5))
            background = AppUi.rounded(context, Color.WHITE, 100f)
        }
        statePanel.addView(stateBadge, MainUiComponents.wrapContent())
        stateTitle = AppUi.textView(
            context,
            "代理当前未运行",
            22f,
            AppUi.text,
            medium = true,
        )
        statePanel.addView(
            stateTitle,
            MainUiComponents.fullWidth().apply { topMargin = dp(14) },
        )
        stateDetail = AppUi.textView(
            context,
            "开启后开始读取微信聊天",
            14f,
            AppUi.textSecondary,
        )
        statePanel.addView(
            stateDetail,
            MainUiComponents.fullWidth().apply { topMargin = dp(7) },
        )

        agentButton = MainUiComponents.primaryButton(
            context,
            "开启代理",
            R.drawable.ic_play,
            onToggleAgent,
        )
        statePanel.addView(
            agentButton,
            MainUiComponents.fullWidth().apply { topMargin = dp(18) },
        )
        stopAgentButton = MainUiComponents.secondaryDangerButton(
            context,
            "停止代理并整理记忆",
            onStopAgent,
        )
        statePanel.addView(
            stopAgentButton,
            MainUiComponents.fullWidth().apply { topMargin = dp(10) },
        )
        root.addView(statePanel, MainUiComponents.fullWidth())

        val bubbleRow = MainUiComponents.switchRow(
            context,
            R.drawable.ic_message_circle,
            "悬浮气泡",
            BubbleSettings.enabled(context),
            onText = "已开启",
            offText = "已关闭",
            onChecked = onToggleBubble,
        )
        bubbleSwitch = bubbleRow.control
        bubbleDetail = bubbleRow.detail
        root.addView(
            MainUiComponents.groupViews(context, listOf(bubbleRow.view)),
            MainUiComponents.fullWidth().apply {
                topMargin = dp(10)
            },
        )

        val runtimeLabel = MainUiComponents.sectionLabel(context, "运行环境")
        MainUiComponents.topPadding(runtimeLabel, context, 22)
        root.addView(runtimeLabel)
        deploymentIcon = AppUi.icon(
            context,
            R.drawable.ic_shield_check,
            AppUi.primary,
            22,
        )
        deploymentText = AppUi.textView(
            context,
            "",
            14f,
            AppUi.textSecondary,
        ).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val healthRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        healthRow.addView(
            deploymentIcon,
            LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                topMargin = dp(2)
                marginEnd = dp(12)
            },
        )
        healthRow.addView(
            deploymentText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(MainUiComponents.groupViews(context, listOf(healthRow)), MainUiComponents.fullWidth())

        val statusLabel = MainUiComponents.sectionLabel(context, "最近状态")
        MainUiComponents.topPadding(statusLabel, context, 22)
        root.addView(statusLabel)
        statusText = AppUi.textView(
            context,
            "",
            14f,
            AppUi.textSecondary,
        ).apply {
            setPadding(dp(16), dp(15), dp(16), dp(15))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        root.addView(
            MainUiComponents.statusSurface(context, statusText),
            MainUiComponents.fullWidth(),
        )

        val quickLabel = MainUiComponents.sectionLabel(context, "快捷入口")
        MainUiComponents.topPadding(quickLabel, context, 22)
        root.addView(quickLabel)
        root.addView(
            MainUiComponents.groupViews(
                context,
                listOf(
                    MainUiComponents.actionRow(
                        context,
                        R.drawable.ic_chat,
                        "聊天记忆",
                        "联系人、总结与待办",
                        onClick = onOpenMemory,
                    ).view,
                    MainUiComponents.actionRow(
                        context,
                        R.drawable.ic_shield_check,
                        "设置与运行检查",
                        "控制、权限和输入法",
                        onClick = onOpenSettings,
                    ).view,
                ),
            ),
            MainUiComponents.fullWidth(),
        )
        view = root
    }

    fun renderStatus(value: String) {
        statusText.text = value
    }

    fun renderDeployment(
        value: String,
        healthy: Boolean,
        hasReliabilityWarnings: Boolean = false,
    ) {
        deploymentText.text = value
        val color = if (healthy && !hasReliabilityWarnings) {
            AppUi.textSecondary
        } else {
            AppUi.paused
        }
        deploymentText.setTextColor(color)
        deploymentIcon.setColorFilter(
            if (healthy && !hasReliabilityWarnings) AppUi.running else AppUi.paused,
        )
    }

    fun refreshDeployment() {
        val notification = context.checkSelfPermission(
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val battery = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
        val accessibility = accessibilityEnabled()
        val ime = inputMethodState()
        val mode = TransportSettings.mode(context)
        val transportReady = DeviceTransportFactory.create(context).isReady()
        val botImeEnabled = ImeStrategyFactory.isBotImeEnabled(context)
        val botImeDefault = ImeStrategyFactory.isBotImeDefault(context)
        val apiKey = if (ModelSettings.isConfigured(context)) "已配置" else "未配置"
        refreshBubbleSwitch()
        val bubble = when {
            !BubbleSettings.enabled(context) -> "已关闭"
            !Settings.canDrawOverlays(context) -> "未授权"
            else -> "已显示"
        }
        val deliveries = DeliveryStore(
            File(context.getExternalFilesDir(null) ?: context.filesDir, "outbox"),
        ).all()
        val queuedDeliveries = deliveries.count { it.waiting }
        val reviewDeliveries = deliveries.count {
            it.status == com.local.wxbotprobe.send.DeliveryStatus.UNKNOWN
        }
        val retryTasks = ReplyRetryStore(
            File(context.filesDir, "reply-retries"),
        ).all()
        val waitingRetries = retryTasks.count { it.waiting }
        val failedRetries = retryTasks.count { it.status == ReplyRetryStatus.FAILED }
        val autoSendUsage = AutoSendUsageStore(context)
        val apiUsage = ApiUsageStore(context)
        val sentToday = autoSendUsage.sentToday()
        val spentToday = apiUsage.spentToday()
        val memory = MemoryV2AdminStore(context).overview()
        val messageLimit = SendSettings.dailyMessageLimit(context)
        val costLimit = SendSettings.dailyCostLimitUsd(context)
        val messageLimitText = if (
            messageLimit == SendSettings.UNLIMITED_MESSAGE_LIMIT
        ) {
            "无上限"
        } else {
            "$messageLimit 条"
        }
        val costLimitText = if (costLimit.isInfinite()) {
            "无上限"
        } else {
            String.format(Locale.CHINA, "\$%.2f", costLimit)
        }
        val hardChecks = buildList {
            add("通知权限" to notification)
            add("无障碍服务" to accessibility)
            add("设备传输" to transportReady)
            if (mode == TransportMode.ACCESSIBILITY) {
                add("BotIme 已启用" to botImeEnabled)
                add("BotIme 默认" to botImeDefault)
            } else {
                add("输入法" to (ime != "未启用"))
            }
            add("模型配置" to (apiKey == "已配置"))
        }
        val softChecks = buildList {
            add("电池优化白名单" to battery)
            if (DeviceProfileResolver.current(context).mode != DeviceProfileMode.VERIFIED) {
                add("设备精确适配" to false)
            }
            if (BubbleSettings.enabled(context) && !Settings.canDrawOverlays(context)) {
                add("悬浮窗权限" to false)
            }
        }
        val hardReady = hardChecks.count { it.second }
        val hardMissing = hardChecks.filterNot { it.second }.joinToString("、") { it.first }
        val softMissing = softChecks.filterNot { it.second }.joinToString("、") { it.first }

        val deployment = buildString {
            appendLine("运行必需  $hardReady/${hardChecks.size}")
            if (hardMissing.isNotEmpty()) {
                appendLine("缺少硬需求：$hardMissing")
            }
            if (softMissing.isNotEmpty()) {
                appendLine("可靠性建议：$softMissing")
            }
            if (hardMissing.isEmpty() && softMissing.isEmpty()) {
                appendLine("硬需求与可靠性建议均已就绪")
            }
            appendLine("${mode.label} · 输入法$ime")
            appendLine("气泡$bubble · 启动即授权当前私聊 · 待发送 $queuedDeliveries")
            if (reviewDeliveries > 0) {
                appendLine("发送结果需人工确认 $reviewDeliveries")
            }
            append(
                "今日 $sentToday/" +
                    "$messageLimitText · API " +
                    String.format(Locale.CHINA, "\$%.2f", spentToday) +
                    "/$costLimitText",
            )
            if (SendSettings.quietHoursEnabled(context)) {
                append(
                    " · 静默 ${formatMinute(SendSettings.quietStartMinute(context))}-" +
                        formatMinute(SendSettings.quietEndMinute(context)),
                )
            }
            appendLine()
            append("回复重试 $waitingRetries")
            if (failedRetries > 0) {
                append(" · 生成失败 $failedRetries")
            }
            appendLine()
            append(
                "记忆 ${memory.contacts.size} 个联系人 / " +
                    "${memory.memoryCount} 条长期记忆 · " +
                    "${memory.messageCount} 条消息",
            )
        }
        renderDeployment(
            deployment,
            healthy = hardMissing.isEmpty(),
            hasReliabilityWarnings = softMissing.isNotEmpty(),
        )
    }

    private fun refreshBubbleSwitch() {
        val enabled = BubbleSettings.enabled(context)
        if (bubbleSwitch.isChecked != enabled) {
            bubbleSwitch.setOnCheckedChangeListener(null)
            bubbleSwitch.isChecked = enabled
            bindBubbleSwitch()
        }
        bubbleDetail.text = when {
            !enabled -> "已关闭 · 微信上方不显示"
            !Settings.canDrawOverlays(context) -> "已开启 · 等待悬浮窗权限"
            else -> "已开启 · 微信上方可控制代理"
        }
    }

    private fun bindBubbleSwitch() {
        bubbleSwitch.setOnCheckedChangeListener { _, checked ->
            bubbleDetail.text = if (checked) "正在开启" else "正在关闭"
            onToggleBubble(checked)
        }
    }

    fun renderRunState(state: AgentRunState) {
        when (state) {
            AgentRunState.RUNNING -> {
                renderState(
                    badge = "运行中",
                    badgeColor = AppUi.running,
                    panelColor = AppUi.runningSoft,
                    title = "正在监听微信",
                    detail = "悬浮气泡可以随时暂停",
                )
                stylePrimaryButton(
                    label = "暂停代理",
                    iconResource = R.drawable.ic_pause,
                    fill = AppUi.paused,
                    enabled = true,
                )
                stopAgentButton.visibility = View.VISIBLE
            }
            AgentRunState.PAUSED -> {
                renderState(
                    badge = "已暂停",
                    badgeColor = AppUi.paused,
                    panelColor = AppUi.pausedSoft,
                    title = "代理已暂停",
                    detail = "当前会话仍保留，继续后恢复",
                )
                stylePrimaryButton(
                    label = "继续代理",
                    iconResource = R.drawable.ic_play,
                    fill = AppUi.primary,
                    enabled = true,
                )
                stopAgentButton.visibility = View.VISIBLE
            }
            AgentRunState.STOPPING -> {
                renderState(
                    badge = "整理中",
                    badgeColor = AppUi.info,
                    panelColor = AppUi.infoSoft,
                    title = "正在整理聊天记忆",
                    detail = "完成后代理会自动关闭",
                )
                stylePrimaryButton(
                    label = "正在停止",
                    iconResource = R.drawable.ic_pause,
                    fill = AppUi.surfaceSubtle,
                    enabled = false,
                )
                stopAgentButton.visibility = View.GONE
            }
            AgentRunState.OFF -> {
                renderState(
                    badge = "已停止",
                    badgeColor = AppUi.textSecondary,
                    panelColor = AppUi.surfaceSubtle,
                    title = "代理当前未运行",
                    detail = "开启后开始读取微信聊天",
                )
                stylePrimaryButton(
                    label = "开启代理",
                    iconResource = R.drawable.ic_play,
                    fill = AppUi.primary,
                    enabled = true,
                )
                stopAgentButton.visibility = View.GONE
            }
        }
    }

    private fun renderState(
        badge: String,
        badgeColor: Int,
        panelColor: Int,
        title: String,
        detail: String,
    ) {
        stateBadge.text = badge
        stateBadge.setTextColor(badgeColor)
        statePanel.background = AppUi.rounded(context, panelColor, 8f)
        stateTitle.text = title
        stateDetail.text = detail
    }

    private fun stylePrimaryButton(
        label: String,
        iconResource: Int,
        fill: Int,
        enabled: Boolean,
    ) {
        agentButton.text = label
        agentButton.isEnabled = enabled
        agentButton.alpha = if (enabled) 1f else 0.65f
        agentButton.setCompoundDrawablesWithIntrinsicBounds(iconResource, 0, 0, 0)
        agentButton.compoundDrawableTintList = ColorStateList.valueOf(
            if (enabled) Color.WHITE else AppUi.textMuted,
        )
        agentButton.setTextColor(if (enabled) Color.WHITE else AppUi.textMuted)
        agentButton.background = AppUi.filledRipple(context, fill, 8f)
    }

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any {
            it.equals("${context.packageName}/.ProbeAccessibilityService", ignoreCase = true) ||
                it.equals(
                    "${context.packageName}/${ProbeAccessibilityService::class.java.name}",
                    ignoreCase = true,
                )
        }
    }

    private fun inputMethodState(): String {
        val enabled = ImeStrategyFactory.isBotImeEnabled(context)
        if (TransportSettings.mode(context) == TransportMode.ACCESSIBILITY) {
            return when {
                !enabled -> "BotIme 未启用"
                !ImeStrategyFactory.isBotImeDefault(context) -> "BotIme 已启用，非默认"
                ImeBridge.isTargetPackage(RouteCImePolicy.WECHAT_PACKAGE) ->
                    "BotIme 当前默认 · 微信已连接"
                else -> "BotIme 当前默认"
            }
        }
        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ).orEmpty()
        return when {
            current == "${context.packageName}/.BotImeService" -> "BotIme 当前使用"
            enabled -> "BotIme 已启用，未选中"
            else -> "未启用"
        }
    }

    private fun formatMinute(value: Int): String {
        return String.format(Locale.CHINA, "%02d:%02d", value / 60, value % 60)
    }

    private fun dp(value: Int): Int = AppUi.dp(context, value)
}
