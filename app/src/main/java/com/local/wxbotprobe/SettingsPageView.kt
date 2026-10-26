package com.local.wxbotprobe

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.local.wxbotprobe.api.ModelSettings
import com.local.wxbotprobe.api.ModelProvider
import com.local.wxbotprobe.bot.PersonaProfileCodec
import com.local.wxbotprobe.bot.PersonaProfileStore
import com.local.wxbotprobe.bot.PersonaIdentityMode
import com.local.wxbotprobe.chat.BitmapPixelSource
import com.local.wxbotprobe.device.DeviceCalibrationStore
import com.local.wxbotprobe.device.DeviceIdentityResolver
import com.local.wxbotprobe.device.DeviceProfileMode
import com.local.wxbotprobe.device.DeviceProfileResolver
import com.local.wxbotprobe.device.ScreenCalibrationAnalyzer
import com.local.wxbotprobe.memory.v2.MemoryV2AdminStore
import com.local.wxbotprobe.memory.v2.MemoryBudgetSettings
import com.local.wxbotprobe.memory.v2.MemoryCleanupSettings
import com.local.wxbotprobe.memory.v2.MemoryHourlyBudgetTier
import com.local.wxbotprobe.memory.v2.MemoryMessageBodyRetention
import com.local.wxbotprobe.memory.v2.ReplyContextMode
import com.local.wxbotprobe.memory.v2.ReplyContextSettings
import com.local.wxbotprobe.maintenance.RuntimeDataCleaner
import com.local.wxbotprobe.maintenance.RuntimeDataPolicy
import com.local.wxbotprobe.maintenance.RuntimeDataSettings
import com.local.wxbotprobe.send.SendPointPolicy
import java.io.File
import java.util.Locale

class SettingsPageView(
    private val activity: Activity,
    private val onOpenPersona: () -> Unit,
    private val onOpenDiagnostics: () -> Unit,
    private val onRefreshRequested: () -> Unit,
    private val onOpenWeChatSoon: () -> Unit,
    private val onRequestNotification: () -> Unit,
) {
    val view: LinearLayout

    private lateinit var transportSwitch: Switch
    private lateinit var autoSendSwitchDetail: TextView
    private lateinit var transportSwitchDetail: TextView
    private lateinit var modelConfigDetail: TextView
    private lateinit var whitelistDetail: TextView
    private lateinit var messageLimitDetail: TextView
    private lateinit var memoryBudgetDetail: TextView
    private lateinit var replyContextDetail: TextView
    private lateinit var memoryCleanupRetentionDetail: TextView
    private lateinit var costLimitDetail: TextView
    private lateinit var quietHoursDetail: TextView
    private lateinit var runtimeDataRetentionDetail: TextView
    private lateinit var runtimeDataSizeDetail: TextView
    private lateinit var runtimeDataClearDetail: TextView
    private lateinit var personaDetail: TextView
    private lateinit var deviceProfileDetail: TextView
    private lateinit var notificationPermissionDetail: TextView

    init {
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)

            addView(MainUiComponents.sectionLabel(activity, "人格"))
            val personaRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_person,
                "人格与对话样例",
                "",
                onClick = onOpenPersona,
            )
            personaDetail = personaRow.detail
            addView(
                MainUiComponents.groupViews(activity, listOf(personaRow.view)),
                MainUiComponents.fullWidth(),
            )

            addView(
                MainUiComponents.sectionLabel(activity, "控制").apply {
                    MainUiComponents.topPadding(this, activity, 22)
                },
            )
            val autoSendRow = MainUiComponents.switchRow(
                activity,
                R.drawable.ic_send,
                "自动发送",
                SendSettings.autoSend(activity),
            ) { checked ->
                SendSettings.setAutoSend(activity, checked)
                ProbeStatus.write(
                    activity,
                    if (checked) "自动发送已开启。" else "自动发送已关闭。",
                )
                refresh()
            }
            autoSendSwitchDetail = autoSendRow.detail

            val transportRow = MainUiComponents.switchRow(
                activity,
                R.drawable.ic_activity,
                "路线 C 无障碍传输",
                TransportSettings.mode(activity) == TransportMode.ACCESSIBILITY,
            ) { checked ->
                onTransportModeChanged(checked)
            }
            transportSwitch = transportRow.control
            transportSwitchDetail = transportRow.detail

            addView(
                MainUiComponents.groupViews(
                    activity,
                    listOf(
                        autoSendRow.view,
                        transportRow.view,
                    ),
                ),
                MainUiComponents.fullWidth(),
            )

            addView(
                MainUiComponents.sectionLabel(activity, "发送与记忆限制").apply {
                    MainUiComponents.topPadding(this, activity, 22)
                },
            )
            val whitelistRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_person,
                "联系人白名单",
                "",
                onClick = ::showWhitelistDialog,
            )
            whitelistDetail = whitelistRow.detail
            val messageLimitRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_send,
                "每日消息上限",
                "",
                onClick = ::showMessageLimitDialog,
            )
            messageLimitDetail = messageLimitRow.detail
            val memoryBudgetRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_clock,
                "每小时记忆提取额度",
                "",
                onClick = ::showMemoryBudgetDialog,
            )
            memoryBudgetDetail = memoryBudgetRow.detail
            val replyContextRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_chat,
                "回复记忆上下文",
                "",
                onClick = ::showReplyContextDialog,
            )
            replyContextDetail = replyContextRow.detail
            val memoryCleanupRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_trash,
                "已处理消息正文保留",
                "",
                onClick = ::showMemoryCleanupDialog,
            )
            memoryCleanupRetentionDetail = memoryCleanupRow.detail
            val costLimitRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_key,
                "每日 API 费用上限",
                "",
                onClick = ::showCostLimitDialog,
            )
            costLimitDetail = costLimitRow.detail
            val quietRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_clock,
                "静默时段",
                "",
                onClick = ::showQuietHoursDialog,
            )
            quietHoursDetail = quietRow.detail
            addView(
                MainUiComponents.groupViews(
                    activity,
                    listOf(
                        whitelistRow.view,
                        messageLimitRow.view,
                        memoryBudgetRow.view,
                        replyContextRow.view,
                        memoryCleanupRow.view,
                        costLimitRow.view,
                        quietRow.view,
                    ),
                ),
                MainUiComponents.fullWidth(),
            )

            addView(
                MainUiComponents.sectionLabel(activity, "运行数据").apply {
                    MainUiComponents.topPadding(this, activity, 22)
                },
            )
            val retentionRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_clock,
                "明文运行数据保留",
                "",
                onClick = ::showRuntimeRetentionDialog,
            )
            runtimeDataRetentionDetail = retentionRow.detail
            val sizeRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_activity,
                "运行数据容量上限",
                "",
                onClick = ::showRuntimeSizeDialog,
            )
            runtimeDataSizeDetail = sizeRow.detail
            val clearRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_trash,
                "立即清理运行数据",
                "",
                onClick = ::confirmClearRuntimeData,
            )
            runtimeDataClearDetail = clearRow.detail
            addView(
                MainUiComponents.groupViews(
                    activity,
                    listOf(retentionRow.view, sizeRow.view, clearRow.view),
                ),
                MainUiComponents.fullWidth(),
            )

            addView(
                MainUiComponents.sectionLabel(activity, "运行环境").apply {
                    MainUiComponents.topPadding(this, activity, 22)
                },
            )
            val runtimeRows = mutableListOf<android.view.View>()
            val notificationRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_message_circle,
                "允许通知",
                "",
                onClick = onRequestNotification,
            )
            notificationPermissionDetail = notificationRow.detail
            runtimeRows += notificationRow.view
            val modelConfigRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_robot,
                "模型配置",
                "",
                onClick = ::showModelConfigDialog,
            )
            modelConfigDetail = modelConfigRow.detail
            runtimeRows += modelConfigRow.view
            val deviceProfileRow = MainUiComponents.actionRow(
                activity,
                R.drawable.ic_activity,
                "设备适配",
                "",
                onClick = ::showDeviceProfileDialog,
            )
            deviceProfileDetail = deviceProfileRow.detail
            runtimeRows += deviceProfileRow.view
            runtimeRows += MainUiComponents.actionRow(
                activity,
                R.drawable.ic_shield_check,
                "部署检查与步骤",
                "查看完整检查结果",
                onClick = ::showDeploymentGuide,
            ).view
            if (RouteBPlatform.available) {
                runtimeRows += MainUiComponents.actionRow(
                    activity,
                    R.drawable.ic_link,
                    "连接或授权 Shizuku",
                    "连接本机 Shizuku 用户服务",
                ) { RouteBPlatform.connect(activity) }.view
                runtimeRows += MainUiComponents.actionRow(
                    activity,
                    R.drawable.ic_refresh,
                    "强制重连 Shizuku",
                    "授权异常时重新建立连接",
                ) { RouteBPlatform.forceReconnect(activity) }.view
                runtimeRows += MainUiComponents.actionRow(
                    activity,
                    R.drawable.ic_external,
                    "打开 Shizuku",
                    "管理系统授权",
                    onClick = ::openShizuku,
                ).view
            }
            runtimeRows += MainUiComponents.actionRow(
                activity,
                R.drawable.ic_battery,
                "电池优化",
                "保持后台轮询运行",
            ) {
                activity.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }.view
            runtimeRows += MainUiComponents.actionRow(
                activity,
                R.drawable.ic_person,
                "无障碍保活",
                "避免 vivo 冻结后台进程",
            ) {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.view
            runtimeRows += MainUiComponents.actionRow(
                activity,
                R.drawable.ic_keyboard,
                "输入法设置",
                "启用自建输入法",
            ) {
                activity.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }.view
            runtimeRows += MainUiComponents.actionRow(
                activity,
                R.drawable.ic_keyboard,
                "选择当前输入法",
                "打开系统输入法选择器",
            ) {
                activity.getSystemService(InputMethodManager::class.java)
                    .showInputMethodPicker()
            }.view
            addView(
                MainUiComponents.groupViews(activity, runtimeRows),
                MainUiComponents.fullWidth(),
            )

            addView(
                MainUiComponents.sectionLabel(activity, "高级").apply {
                    MainUiComponents.topPadding(this, activity, 22)
                },
            )
            addView(
                MainUiComponents.groupViews(
                    activity,
                    listOf(
                        MainUiComponents.actionRow(
                            activity,
                            R.drawable.ic_terminal,
                            "诊断工具",
                            "截图、OCR、输入与发送测试",
                            onClick = onOpenDiagnostics,
                        ).view,
                    ),
                ),
                MainUiComponents.fullWidth(),
            )
        }
    }

    fun refresh() {
        if (!::transportSwitch.isInitialized) {
            return
        }
        val whitelistCount = WhitelistContacts.current(activity).size

        if (::personaDetail.isInitialized) {
            val persona = PersonaProfileStore(activity).load()
            val identity = when (persona.identityMode) {
                PersonaIdentityMode.AI_ASSISTANT -> "AI 助手"
                PersonaIdentityMode.TRANSPARENT_COMPANION -> "透明陪伴"
                PersonaIdentityMode.IMMERSIVE_COMPANION -> "沉浸角色"
            }
            personaDetail.text = when {
                !PersonaProfileCodec.isConfigured(persona) ->
                    "当前为空 · 点击填写"
                persona.instructions.isBlank() ->
                    "已配置 · $identity · ${persona.examples.size} 组对话样例"
                persona.examples.isEmpty() ->
                    "已配置 · $identity · 正文 ${persona.instructions.length} 字"
                else ->
                    "已配置 · $identity · 正文 ${persona.instructions.length} 字 · " +
                        "${persona.examples.size} 组样例"
            }
        }

        if (::autoSendSwitchDetail.isInitialized) {
            val enabled = SendSettings.autoSend(activity)
            autoSendSwitchDetail.text = when {
                !enabled -> "已关闭"
                whitelistCount == 0 -> "已开启 · 白名单未设置，不会发送"
                else -> String.format(
                    Locale.CHINA,
                    "已开启 · %d 个联系人 · 发送期间请勿切换聊天",
                    whitelistCount,
                )
            }
            autoSendSwitchDetail.setTextColor(
                if (enabled) AppUi.danger else AppUi.textSecondary,
            )
        }
        if (::whitelistDetail.isInitialized) {
            whitelistDetail.text = if (whitelistCount == 0) {
                "未设置，自动发送全部拦截"
            } else {
                "$whitelistCount 个联系人已允许"
            }
        }
        if (::messageLimitDetail.isInitialized) {
            val limit = SendSettings.dailyMessageLimit(activity)
            messageLimitDetail.text = if (
                limit == SendSettings.UNLIMITED_MESSAGE_LIMIT
            ) {
                "无上限"
            } else {
                String.format(Locale.CHINA, "每天最多 %d 条", limit)
            }
        }
        if (::costLimitDetail.isInitialized) {
            val limit = SendSettings.dailyCostLimitUsd(activity)
            costLimitDetail.text = if (limit.isInfinite()) {
                "无上限"
            } else {
                String.format(Locale.CHINA, "每天最多 \$%.2f", limit)
            }
        }
        if (::memoryBudgetDetail.isInitialized) {
            val tier = MemoryBudgetSettings.hourlyTier(activity)
            memoryBudgetDetail.text = when (tier) {
                MemoryHourlyBudgetTier.UNLIMITED ->
                    "不限小时 · 仍受每日 API 费用上限约束"
                else -> String.format(
                    Locale.CHINA,
                    "%s · 每小时联系人 %d / 设备 %d 次",
                    tier.label,
                    tier.scopeLimit ?: 0,
                    tier.deviceLimit ?: 0,
                )
            }
        }
        if (::memoryCleanupRetentionDetail.isInitialized) {
            val retention = MemoryCleanupSettings.bodyRetention(activity)
            memoryCleanupRetentionDetail.text = String.format(
                Locale.CHINA,
                "%s后 · 仅清除正文并保留证据",
                retention.label,
            )
        }
        if (::replyContextDetail.isInitialized) {
            replyContextDetail.text =
                when (ReplyContextSettings.mode(activity)) {
                    ReplyContextMode.LAYERED ->
                        "分层快照 · A0/A1 · 近 7 天 A2 · 当前会话"
                    ReplyContextMode.LEGACY ->
                        "兼容检索 · 原实时记忆匹配"
                }
        }
        if (::quietHoursDetail.isInitialized) {
            quietHoursDetail.text = quietHoursLabel()
        }
        if (::deviceProfileDetail.isInitialized) {
            val profile = DeviceProfileResolver.current(activity)
            val sendProfile = SendPointPolicy.resolveCurrent(activity)
            val custom = DeviceCalibrationStore(activity).load()
            deviceProfileDetail.text = when {
                custom != null ->
                    "自定义校准 · ${profile.width}×${profile.height} · 快速发送"
                profile.mode == DeviceProfileMode.VERIFIED && sendProfile != null ->
                    "已验证 · ${profile.width}×${profile.height} · 快速发送"
                profile.mode == DeviceProfileMode.VERIFIED ->
                    "设备已验证 · 微信版本使用动态发送检测"
                else ->
                    "兼容模式 · ${profile.width}×${profile.height} · 动态检测"
            }
        }
        if (::notificationPermissionDetail.isInitialized) {
            val granted = activity.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            notificationPermissionDetail.text = if (granted) {
                "已允许 · 点击打开通知设置"
            } else {
                "未授权 · 点击申请通知权限"
            }
        }
        if (::modelConfigDetail.isInitialized) {
            val configuration = ModelSettings.load(activity)
            modelConfigDetail.text = when {
                configuration.provider == ModelProvider.DISABLED ->
                    "已禁用 · 当前只读"
                !ModelSettings.isConfigured(activity) ->
                    "${configuration.provider.label} · 配置不完整"
                else ->
                    configuration.displayName()
            }
        }
        if (::runtimeDataRetentionDetail.isInitialized) {
            val policy = RuntimeDataSettings.policy(activity)
            runtimeDataRetentionDetail.text =
                RuntimeDataSettings.retentionLabel(policy.retentionDays)
            runtimeDataSizeDetail.text =
                RuntimeDataSettings.sizeLabel(policy.maxBytes)
            runtimeDataClearDetail.text = runCatching {
                "当前明文运行数据 ${formatBytes(
                    RuntimeDataCleaner.forContext(activity).totalBytes(),
                )}"
            }.getOrDefault("查看并清理运行副产物")
        }
        if (::transportSwitchDetail.isInitialized) {
            val mode = TransportSettings.mode(activity)
            val botImeEnabled = ImeStrategyFactory.isBotImeEnabled(activity)
            val botImeDefault = ImeStrategyFactory.isBotImeDefault(activity)
            transportSwitchDetail.text = when {
                mode == TransportMode.SHIZUKU -> "Shizuku 用户服务 · 回退"
                !botImeEnabled -> "无障碍已选 · BotIme 未启用"
                !botImeDefault -> "无障碍已选 · BotIme 非默认"
                else -> "无障碍 + BotIme 常驻"
            }
        }
    }

    private fun showRuntimeRetentionDialog() {
        val current = RuntimeDataSettings.policy(activity)
        val options = RuntimeDataSettings.RETENTION_OPTIONS
        val labels = options.map { RuntimeDataSettings.retentionLabel(it) }.toTypedArray()
        MainUiComponents.dialogBuilder(activity)
            .setTitle("明文运行数据保留")
            .setSingleChoiceItems(
                labels,
                options.indexOf(current.retentionDays).coerceAtLeast(0),
            ) { dialog, which ->
                val updated = current.copy(retentionDays = options[which])
                RuntimeDataSettings.setPolicy(activity, updated)
                applyRuntimePolicy(updated)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRuntimeSizeDialog() {
        val current = RuntimeDataSettings.policy(activity)
        val options = RuntimeDataSettings.SIZE_OPTIONS_MB
        val labels = options.map {
            RuntimeDataSettings.sizeLabel(it.toLong() * 1024L * 1024L)
        }.toTypedArray()
        MainUiComponents.dialogBuilder(activity)
            .setTitle("运行数据容量上限")
            .setSingleChoiceItems(
                labels,
                options.indexOf(
                    (current.maxBytes / (1024L * 1024L)).toInt(),
                ).coerceAtLeast(0),
            ) { dialog, which ->
                val updated = current.copy(
                    maxBytes = options[which].toLong() * 1024L * 1024L,
                )
                RuntimeDataSettings.setPolicy(activity, updated)
                applyRuntimePolicy(updated)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyRuntimePolicy(policy: RuntimeDataPolicy) {
        Thread(
            {
                val result = runCatching {
                    RuntimeDataCleaner.forContext(activity).apply(policy)
                }
                activity.runOnUiThread {
                    result.onSuccess {
                        ProbeStatus.write(
                            activity,
                            "运行数据策略已保存；本次清理 ${it.deletedFiles} 个文件。",
                        )
                    }.onFailure {
                        ProbeStatus.write(activity, "运行数据清理失败：${it.message}")
                    }
                    refresh()
                    onRefreshRequested()
                }
            },
            "handora-runtime-policy",
        ).apply { isDaemon = true }.start()
    }

    private fun confirmClearRuntimeData() {
        if (BotLoopService.runState != AgentRunState.OFF) {
            ProbeStatus.write(activity, "请先停止代理，再清理运行数据。")
            return
        }
        MainUiComponents.showDestructiveDialog(
            context = activity,
            title = "清理运行数据",
            message = "会删除日志、截图、inbox、replies 和已发送 outbox；" +
                "不会删除聊天记忆、模型配置、设置或未完成的发送/生成任务。",
            positiveLabel = "清理",
            onConfirm = {
                Thread(
                    {
                        val result = runCatching {
                            RuntimeDataCleaner.forContext(activity).clearAll()
                        }
                        activity.runOnUiThread {
                            result.onSuccess {
                                ProbeStatus.write(
                                    activity,
                                    "已清理 ${it.deletedFiles} 个运行数据文件，" +
                                        "释放 ${formatBytes(it.deletedBytes)}。",
                                )
                            }.onFailure {
                                ProbeStatus.write(activity, "运行数据清理失败：${it.message}")
                            }
                            refresh()
                            onRefreshRequested()
                        }
                    },
                    "handora-runtime-clear",
                ).apply { isDaemon = true }.start()
            },
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) {
            return "$bytes B"
        }
        val kb = bytes / 1024.0
        if (kb < 1024.0) {
            return String.format(Locale.CHINA, "%.1f KB", kb)
        }
        return String.format(Locale.CHINA, "%.1f MB", kb / 1024.0)
    }

    private fun onTransportModeChanged(accessibilityEnabled: Boolean) {
        if (BotLoopService.runState != AgentRunState.OFF) {
            restoreTransportSwitch()
            ProbeStatus.write(activity, "请先停止代理，再切换设备传输路线。")
            return
        }
        val nextMode = if (accessibilityEnabled) {
            TransportMode.ACCESSIBILITY
        } else {
            TransportMode.SHIZUKU
        }
        if (nextMode == TransportMode.SHIZUKU && !RouteBPlatform.available) {
            restoreTransportSwitch()
            ProbeStatus.write(
                activity,
                "当前 APK 不包含路线 B；请安装 routeB 回退构建。",
            )
            return
        }
        if (nextMode == TransportMode.ACCESSIBILITY) {
            if (ProbeAccessibilityService.instance == null) {
                restoreTransportSwitch()
                ProbeStatus.write(activity, "路线 C 需要先启用无障碍服务。")
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            val imeIssue = ImeStrategyFactory.routeCStartupIssue(activity)
            if (imeIssue != null) {
                restoreTransportSwitch()
                ProbeStatus.write(activity, RouteCImePolicy.startupMessage(imeIssue))
                activity.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                return
            }
        }
        TransportSettings.setMode(activity, nextMode)
        if (nextMode == TransportMode.SHIZUKU) {
            RouteBPlatform.initialize(activity)
        }
        ProbeStatus.write(activity, "设备传输已切换为${nextMode.label}。")
        refresh()
    }

    private fun restoreTransportSwitch() {
        if (!::transportSwitch.isInitialized) {
            return
        }
        val actual = TransportSettings.mode(activity) == TransportMode.ACCESSIBILITY
        transportSwitch.setOnCheckedChangeListener(null)
        transportSwitch.isChecked = actual
        transportSwitch.setOnCheckedChangeListener { _, checked ->
            onTransportModeChanged(checked)
        }
    }

    fun showModelConfigDialog() {
        ModelConfigDialog(activity) {
            refresh()
            onRefreshRequested()
        }.show()
    }

    private fun showWhitelistDialog() {
        val entries = MemoryV2AdminStore(activity).contacts()
        if (entries.isEmpty()) {
            ProbeStatus.write(activity, "还没有联系人记忆，暂时无法设置自动发送白名单。")
            return
        }
        val current = WhitelistContacts.reconcile(
            activity,
            entries.mapTo(mutableSetOf()) { it.identityKey },
        )
        val labels = entries.map { it.displayName }.toTypedArray()
        val checked = BooleanArray(entries.size) { entries[it].identityKey in current }
        MainUiComponents.dialogBuilder(activity)
            .setTitle("自动发送联系人白名单")
            .setMultiChoiceItems(labels, checked) { _, which, value ->
                checked[which] = value
            }
            .setPositiveButton("保存") { _, _ ->
                val selected = entries.indices
                    .filter { checked[it] }
                    .map { entries[it].identityKey }
                    .toSet()
                SendSettings.setWhitelist(activity, selected)
                ProbeStatus.write(
                    activity,
                    if (selected.isEmpty()) {
                        "自动发送白名单为空，所有自动发送将被拦截。"
                    } else {
                        "自动发送白名单已设为 ${selected.size} 个联系人。"
                    },
                )
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMessageLimitDialog() {
        val options = SendSettings.MESSAGE_LIMIT_OPTIONS
        val labels = options.map {
            if (it == SendSettings.UNLIMITED_MESSAGE_LIMIT) "无上限" else "$it 条"
        }.toTypedArray()
        val current = options.indexOf(SendSettings.dailyMessageLimit(activity)).coerceAtLeast(0)
        MainUiComponents.dialogBuilder(activity)
            .setTitle("每日自动发送上限")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val value = options[which]
                SendSettings.setDailyMessageLimit(activity, value)
                ProbeStatus.write(
                    activity,
                    if (value == SendSettings.UNLIMITED_MESSAGE_LIMIT) {
                        "每日自动发送上限已设为无上限。"
                    } else {
                        "每日自动发送上限已设为 $value 条。"
                    },
                )
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCostLimitDialog() {
        val options = SendSettings.COST_LIMIT_OPTIONS
        val labels = options.map {
            if (it.isInfinite()) "无上限" else String.format(Locale.CHINA, "\$%.2f", it)
        }.toTypedArray()
        val currentValue = SendSettings.dailyCostLimitUsd(activity)
        val current = options.indexOfFirst {
            (it.isInfinite() && currentValue.isInfinite()) ||
                kotlin.math.abs(it - currentValue) < 0.001
        }
            .coerceAtLeast(0)
        MainUiComponents.dialogBuilder(activity)
            .setTitle("每日 API 费用上限")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val value = options[which].toDouble()
                SendSettings.setDailyCostLimitUsd(activity, value)
                ProbeStatus.write(
                    activity,
                    if (value.isInfinite()) {
                        "每日 API 费用上限已设为无上限。"
                    } else {
                        String.format(
                            Locale.CHINA,
                            "每日 API 费用上限已设为 \$%.2f。",
                            value,
                        )
                    },
                )
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showReplyContextDialog() {
        val options = ReplyContextMode.entries
        val labels = options.map { mode ->
            when (mode) {
                ReplyContextMode.LAYERED ->
                    "分层快照（推荐）· A0/A1 · 近 7 天 A2 · 当前会话"
                ReplyContextMode.LEGACY ->
                    "兼容检索 · 保持旧版实时记忆匹配"
            }
        }.toTypedArray()
        val current = options.indexOf(
            ReplyContextSettings.mode(activity),
        ).coerceAtLeast(0)
        MainUiComponents.dialogBuilder(activity)
            .setTitle("回复记忆上下文")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val mode = options[which]
                ReplyContextSettings.setMode(activity, mode)
                ProbeStatus.write(
                    activity,
                    when (mode) {
                        ReplyContextMode.LAYERED ->
                            "回复记忆已切换为分层快照，下一条消息生效。"
                        ReplyContextMode.LEGACY ->
                            "回复记忆已切换为兼容检索，下一条消息生效。"
                    },
                )
                dialog.dismiss()
                refresh()
                onRefreshRequested()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMemoryBudgetDialog() {
        val options = MemoryHourlyBudgetTier.entries
        val labels = options.map { tier ->
            when (tier) {
                MemoryHourlyBudgetTier.UNLIMITED -> "不限小时"
                else -> String.format(
                    Locale.CHINA,
                    "%s · 每小时联系人 %d / 设备 %d 次",
                    tier.label,
                    tier.scopeLimit ?: 0,
                    tier.deviceLimit ?: 0,
                )
            }
        }.toTypedArray()
        val current = options.indexOf(
            MemoryBudgetSettings.hourlyTier(activity),
        ).coerceAtLeast(0)
        MainUiComponents.dialogBuilder(activity)
            .setTitle("每小时记忆提取额度")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val tier = options[which]
                dialog.dismiss()
                if (tier == MemoryHourlyBudgetTier.UNLIMITED) {
                    confirmUnlimitedMemoryBudget(tier)
                } else {
                    applyMemoryBudgetTier(tier)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmUnlimitedMemoryBudget(tier: MemoryHourlyBudgetTier) {
        val dailyLimit = SendSettings.dailyCostLimitUsd(activity)
        val dailyLimitText = if (dailyLimit.isInfinite()) {
            "当前每日 API 费用上限也是无上限，可能产生不可控费用。"
        } else {
            String.format(
                Locale.CHINA,
                "每日 API 费用上限仍为 \$%.2f。",
                dailyLimit,
            )
        }
        MainUiComponents.dialogBuilder(activity)
            .setTitle("关闭每小时额度上限？")
            .setMessage(
                "Backlog 处理、失败重试或多个联系人同时活动时，" +
                    "可能在短时间内发出大量模型请求，并增加供应商限流和设备耗电。" +
                    dailyLimitText,
            )
            .setPositiveButton("仍然启用") { _, _ ->
                applyMemoryBudgetTier(tier)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyMemoryBudgetTier(tier: MemoryHourlyBudgetTier) {
        MemoryBudgetSettings.setHourlyTier(activity, tier)
        ProbeStatus.write(
            activity,
            if (tier == MemoryHourlyBudgetTier.UNLIMITED) {
                "已关闭应用内每小时记忆提取额度；每日 API 费用上限仍然生效。"
            } else {
                "记忆提取额度已设为 ${tier.label}。"
            },
        )
        refresh()
        onRefreshRequested()
    }

    private fun showMemoryCleanupDialog() {
        val options = MemoryMessageBodyRetention.entries
        val labels = options.map { retention ->
            if (retention == MemoryMessageBodyRetention.ONE_DAY) {
                "${retention.label}后（推荐）"
            } else {
                "${retention.label}后"
            }
        }.toTypedArray()
        val current = options.indexOf(
            MemoryCleanupSettings.bodyRetention(activity),
        ).coerceAtLeast(0)
        MainUiComponents.dialogBuilder(activity)
            .setTitle("已处理消息正文保留")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val retention = options[which]
                MemoryCleanupSettings.setBodyRetention(activity, retention)
                ProbeStatus.write(
                    activity,
                    "已处理消息正文将在 ${retention.label}后清除。",
                )
                dialog.dismiss()
                refresh()
                onRefreshRequested()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showQuietHoursDialog() {
        data class QuietOption(
            val label: String,
            val enabled: Boolean,
            val start: Int,
            val end: Int,
        )
        val options = listOf(
            QuietOption("关闭静默时段", false, 0, 0),
            QuietOption("22:00 - 07:00", true, 22 * 60, 7 * 60),
            QuietOption("23:00 - 07:00", true, 23 * 60, 7 * 60),
            QuietOption("00:00 - 08:00", true, 0, 8 * 60),
            QuietOption("21:00 - 09:00", true, 21 * 60, 9 * 60),
        )
        val current = if (!SendSettings.quietHoursEnabled(activity)) {
            0
        } else {
            options.indexOfFirst {
                it.enabled &&
                    it.start == SendSettings.quietStartMinute(activity) &&
                    it.end == SendSettings.quietEndMinute(activity)
            }.coerceAtLeast(2)
        }
        MainUiComponents.dialogBuilder(activity)
            .setTitle("自动发送静默时段")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), current) { dialog, which ->
                val option = options[which]
                SendSettings.setQuietHoursEnabled(activity, option.enabled)
                if (option.enabled) {
                    SendSettings.setQuietRange(activity, option.start, option.end)
                }
                ProbeStatus.write(activity, "静默时段已设为 ${option.label}。")
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun quietHoursLabel(): String {
        if (!SendSettings.quietHoursEnabled(activity)) {
            return "已关闭"
        }
        return "${formatMinute(SendSettings.quietStartMinute(activity))} - " +
            formatMinute(SendSettings.quietEndMinute(activity))
    }

    private fun formatMinute(value: Int): String {
        return String.format(Locale.CHINA, "%02d:%02d", value / 60, value % 60)
    }

    private fun showDeviceProfileDialog() {
        val profile = DeviceProfileResolver.current(activity)
        val sendProfile = SendPointPolicy.resolveCurrent(activity)
        val custom = DeviceCalibrationStore(activity).load()
        val mode = if (
            profile.mode == DeviceProfileMode.VERIFIED &&
            sendProfile != null
        ) {
            "已在本机完成布局和快速发送验证。"
        } else if (profile.mode == DeviceProfileMode.VERIFIED) {
            "本机布局已验证；微信版本变化后使用动态绿色按钮检测。"
        } else {
            "当前未收录该设备，使用比例化布局和动态按钮检测。" +
                "首次使用请先执行截图、读屏和 dry-run。"
        }
        val builder = MainUiComponents.dialogBuilder(activity)
            .setTitle("设备适配")
            .setMessage(
                "设备：${profile.width}×${profile.height} / " +
                    "${profile.densityDpi}dpi\n" +
                    "配置：${profile.profileId}\n" +
                    "快速发送：${sendProfile?.profileId ?: "动态检测"}\n\n" +
                    mode,
            )
            .setPositiveButton("知道了", null)
            .setNeutralButton("精确校准") { _, _ ->
                confirmDeviceCalibration()
            }
        if (custom != null) {
            builder.setNegativeButton("撤销校准") { _, _ ->
                DeviceCalibrationStore(activity).clear()
                ProbeStatus.write(activity, "设备精确校准已撤销，已恢复动态检测。")
                refresh()
            }
        }
        builder.show()
    }

    private fun confirmDeviceCalibration() {
        MainUiComponents.dialogBuilder(activity)
            .setTitle("精确校准当前设备")
            .setMessage(
                "请在微信文字聊天中输入一条无害草稿，让绿色“发送”按钮保持可见，" +
                    "并关闭表情面板。\n\n" +
                    "点击开始后 App 会退出前台，请在 5 秒内切到该微信聊天。" +
                    "随后 App 只截取当前屏幕做本地几何解析，不读取聊天正文，" +
                    "校验成功后保存 profile，并立即删除截图。",
            )
            .setPositiveButton("开始") { _, _ ->
                beginDeviceCalibration()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun beginDeviceCalibration() {
        val transport = DeviceTransportFactory.create(activity)
        if (!transport.isReady()) {
            ProbeStatus.write(activity, transport.unavailableMessage())
            return
        }
        val output = File(activity.filesDir, "calibration/latest.png")
        output.parentFile?.mkdirs()
        ProbeStatus.write(
            activity,
            "5 秒后抓取微信校准画面。请保持文字草稿和绿色发送按钮可见。",
        )
        onOpenWeChatSoon()
        activity.window.decorView.postDelayed({
            Thread {
                var bitmap: android.graphics.Bitmap? = null
                val message = try {
                    if (transport.foregroundPackage() != "com.tencent.mm") {
                        throw IllegalStateException("5 秒后微信不在前台，校准已取消")
                    }
                    transport.screenshot(output)
                    bitmap = BitmapFactory.decodeFile(output.absolutePath)
                        ?: throw IllegalStateException("校准截图解码失败")
                    val identity = DeviceIdentityResolver.current(activity)
                    val result = ScreenCalibrationAnalyzer.analyze(
                        pixels = BitmapPixelSource(requireNotNull(bitmap)),
                        identity = identity,
                        verifiedAt = System.currentTimeMillis(),
                    )
                    result.calibration?.let {
                        DeviceCalibrationStore(activity).save(it)
                    }
                    result.message
                } catch (error: Throwable) {
                    "校准失败：${error.message ?: error.javaClass.simpleName}"
                } finally {
                    bitmap?.recycle()
                    output.delete()
                }
                ProbeStatus.write(activity, message)
                activity.runOnUiThread {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    activity.startActivity(
                        Intent(activity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                    activity.window.decorView.postDelayed({ refresh() }, 600L)
                }
            }.start()
        }, 6_000L)
    }

    private fun showDeploymentGuide() {
        val mode = TransportSettings.mode(activity)
        val title = if (mode == TransportMode.ACCESSIBILITY) {
            "路线 C 部署步骤"
        } else {
            "路线 B 部署步骤"
        }
        val message = if (mode == TransportMode.ACCESSIBILITY) {
            "1. 允许通知。\n" +
                "2. 如果系统提供电池优化白名单，建议加入；不提供可跳过。\n" +
                "3. 在系统后台管理中允许自启动和后台活动。\n" +
                "4. 启用本 App 的无障碍服务。\n" +
                "5. 启用 BotIme，并把 BotIme 设为系统默认输入法。\n" +
                "6. 停止代理，在设置中开启“路线 C 无障碍传输”。\n" +
                "7. 在“模型配置”中绑定服务商、模型和密钥。\n" +
                "8. 打开微信文字输入聊天，确认默认输入法仍是 BotIme。\n" +
                "9. 先运行测试回复和 dry-run；路线 C 不需要 Shizuku。\n" +
                "10. 自动发送保持关闭，完成稳定性验证后再单独开启。\n\n" +
                "硬需求：通知、无障碍、设备传输、BotIme 启用与默认、模型配置。\n" +
                "可靠性建议：电池白名单、设备精确适配。缺失时警告但不阻断启动。"
        } else {
            "1. 安装并启动 Shizuku。\n" +
                "2. 打开开发者选项和无线调试，在 Shizuku 内完成配对与启动。\n" +
                "3. 回到本 App，允许通知。\n" +
                "4. 如果系统提供电池优化白名单，建议加入；不提供可跳过。\n" +
                "5. 启用本 App 无障碍服务。\n" +
                "6. 启用自建输入法；发送器会在测试后切回原输入法。\n" +
                "7. 打开 Shizuku，点“连接 / 授权 Shizuku”，选择始终允许。\n" +
                "8. 在“模型配置”中绑定服务商、模型和密钥。\n" +
                "9. 先跑测试回复和 dry-run，再考虑开启自动发送。\n\n" +
                "硬需求：通知、无障碍、设备传输、输入法、模型配置。\n" +
                "可靠性建议：电池白名单、设备精确适配。缺失时警告但不阻断启动。\n\n" +
                "APK 更新后若 Shizuku 一直停在“正在连接”，去 Shizuku 的应用管理里" +
                "关闭再打开本 App 的授权。"
        }
        MainUiComponents.dialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun openShizuku() {
        if (!RouteBPlatform.openManager(activity)) {
            ProbeStatus.write(activity, "Shizuku 未安装。")
        }
    }

    private fun dp(value: Int): Int = AppUi.dp(activity, value)
}
