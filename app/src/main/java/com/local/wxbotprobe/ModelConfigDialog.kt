package com.local.wxbotprobe

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.local.wxbotprobe.api.ApiKeyStore
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.api.ModelProvider
import com.local.wxbotprobe.api.ModelSettings
import com.local.wxbotprobe.bot.ChatMessage

class ModelConfigDialog(
    private val activity: Activity,
    private val onChanged: () -> Unit,
) {
    private var dialog: AlertDialog? = null

    fun show() {
        val configuration = ModelSettings.load(activity)
        val keyConfigured = ApiKeyStore(activity)
            .load(configuration.provider.storageId) != null
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        content.addView(
            AppUi.textView(
                activity,
                if (configuration.provider.isFreeTier) {
                    "免费层使用你自己的账号密钥；额度和数据政策由服务商决定。"
                } else if (configuration.provider.requiresKey) {
                    "密钥只在设备本地加密保存，不会写入项目或日志。"
                } else {
                    "自定义接口可连接兼容 OpenAI Chat Completions 的服务。"
                },
                13f,
                AppUi.textSecondary,
            ).apply {
                setPadding(0, dp(4), 0, dp(10))
            },
        )
        val providerRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_robot,
            "模型提供方",
            configuration.provider.label,
            onClick = ::showProviderPicker,
        )
        val baseUrlRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_link,
            "接口地址",
            configuration.baseUrl.ifBlank { "未设置" },
            onClick = ::showEndpointEditor,
        )
        val modelRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_activity,
            "模型 ID",
            configuration.model.ifBlank { "未设置" },
            onClick = ::showEndpointEditor,
        )
        val visionRow = MainUiComponents.switchRow(
            context = activity,
            iconResource = R.drawable.ic_scan,
            title = "视觉输入",
            checked = configuration.supportsVision,
            onText = "当前模型接收聊天窗口截图",
            offText = "关闭 · 回复使用文字上下文",
        ) { enabled ->
            ModelSettings.setVisionInputEnabled(activity, enabled)
            onChanged()
        }
        val keyRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_key,
            "模型密钥",
            when {
                keyConfigured -> "已配置 · 点击管理"
                configuration.provider.requiresKey -> "未配置 · 当前 provider 必需"
                else -> "未配置 · 自定义接口可选"
            },
            onClick = {
                ApiKeyDialog(
                    activity = activity,
                    providerId = configuration.provider.storageId,
                    providerLabel = configuration.provider.label,
                ) {
                    onChanged()
                }.show()
            },
        )
        val testRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_shield_check,
            "测试模型连接",
            "发送一条不会进入微信的短请求",
            onClick = ::testConnection,
        )
        content.addView(
            MainUiComponents.groupViews(
                activity,
                listOf(
                    providerRow.view,
                    baseUrlRow.view,
                    modelRow.view,
                    visionRow.view,
                    keyRow.view,
                    testRow.view,
                ),
            ),
            MainUiComponents.fullWidth(),
        )
        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("模型配置")
            .setView(
                ScrollView(activity).apply {
                    addView(content)
                },
            )
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showProviderPicker() {
        val providers = ModelProvider.entries
        val current = ModelSettings.load(activity).provider
        MainUiComponents.dialogBuilder(activity)
            .setTitle("选择模型提供方")
            .setSingleChoiceItems(
                providers.map { it.label }.toTypedArray(),
                providers.indexOf(current),
            ) { selectedDialog, index ->
                ModelSettings.selectProvider(activity, providers[index])
                onChanged()
                selectedDialog.dismiss()
                reopen()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEndpointEditor() {
        val configuration = ModelSettings.load(activity)
        val baseUrlInput = EditText(activity).apply {
            setText(configuration.baseUrl)
            hint = "https://example.com/v1"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val modelInput = EditText(activity).apply {
            setText(configuration.model)
            hint = "例如：deepseek-flash 或 openrouter 当前免费模型 ID"
            setSingleLine()
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(
                AppUi.textView(activity, "Base URL", 13f, AppUi.textSecondary),
            )
            addView(baseUrlInput, MainUiComponents.fullWidth())
            addView(
                AppUi.textView(activity, "模型 ID", 13f, AppUi.textSecondary).apply {
                    setPadding(0, dp(14), 0, 0)
                },
            )
            addView(modelInput, MainUiComponents.fullWidth())
            addView(
                AppUi.textView(
                    activity,
                    "免费模型 ID 会变化，请以服务商当前模型列表为准。",
                    12f,
                    AppUi.textMuted,
                ).apply {
                    setPadding(0, dp(10), 0, 0)
                },
            )
        }
        MainUiComponents.dialogBuilder(activity)
            .setTitle("接口与模型")
            .setView(content)
            .setPositiveButton("保存") { _, _ ->
                ModelSettings.saveEndpoint(
                    activity,
                    baseUrl = baseUrlInput.text.toString(),
                    model = modelInput.text.toString(),
                )
                onChanged()
                reopen()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testConnection() {
        val client = ModelClientFactory.create(
            activity,
            timeoutMs = 20_000,
            maxRetries = 1,
        )
        if (client == null) {
            ProbeStatus.write(activity, "模型配置不完整，无法测试。")
            return
        }
        ProbeStatus.write(activity, "正在测试模型连接。")
        Thread {
            val startedAt = System.currentTimeMillis()
            val result = runCatching {
                client.chat(
                    messages = listOf(
                        ChatMessage("system", "只回复 OK"),
                        ChatMessage("user", "连接测试"),
                    ),
                    temperature = 0.0,
                    maxTokens = 8,
                )
            }
            val message = result.fold(
                onSuccess = {
                    "模型连接成功：${it.model.ifBlank { "unknown" }}，" +
                        "${System.currentTimeMillis() - startedAt}ms"
                },
                onFailure = { "模型连接失败：${it.message}" },
            )
            ProbeStatus.write(activity, message)
        }.start()
    }

    private fun reopen() {
        dialog?.dismiss()
        activity.window.decorView.post { show() }
    }

    private fun dp(value: Int): Int = AppUi.dp(activity, value)
}
