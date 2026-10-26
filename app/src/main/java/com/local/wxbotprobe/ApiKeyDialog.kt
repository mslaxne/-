package com.local.wxbotprobe

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.local.wxbotprobe.api.ApiKeyStore
import com.local.wxbotprobe.api.ApiKeySummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApiKeyDialog(
    private val activity: Activity,
    private val providerId: String = "deepseek",
    private val providerLabel: String = "DeepSeek",
    private val onChanged: () -> Unit,
) {
    private val store = ApiKeyStore(activity)
    private var dialog: AlertDialog? = null

    fun show() {
        val keys = store.summaries(providerId)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }
        content.addView(
            AppUi.textView(
                activity,
                "$providerLabel 的模型密钥均加密保存；列表只显示掩码后缀。",
                13f,
                AppUi.textSecondary,
            ).apply {
                setPadding(0, 0, 0, dp(10))
            },
        )
        if (keys.isEmpty()) {
            content.addView(
                AppUi.textView(
                    activity,
                    "尚未配置模型密钥",
                    15f,
                    AppUi.textSecondary,
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(28), 0, dp(28))
                },
            )
        } else {
            keys.forEachIndexed { index, summary ->
                if (index > 0) {
                    content.addView(
                        View(activity).apply {
                            setBackgroundColor(Color.argb(24, 120, 132, 144))
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(1),
                        ),
                    )
                }
                content.addView(keyRow(index, summary))
            }
        }
        if (BotLoopService.runState != AgentRunState.OFF) {
            content.addView(
                AppUi.textView(
                    activity,
                    "代理运行中；切换密钥将在下次启动代理时生效。",
                    13f,
                    AppUi.paused,
                ).apply {
                    setPadding(0, dp(12), 0, 0)
                },
            )
        }
        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("$providerLabel 模型密钥")
            .setView(
                ScrollView(activity).apply {
                    addView(content)
                },
            )
            .setPositiveButton("添加新 Key") { _, _ ->
                showAddDialog()
            }
            .setNegativeButton("关闭", null)
            .show()
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isAllCaps = false
    }

    private fun keyRow(index: Int, summary: ApiKeySummary): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(0, dp(8), 0, dp(8))
            isClickable = !summary.active
            isFocusable = !summary.active
            if (!summary.active) {
                background = AppUi.rowRipple(activity)
                setOnClickListener {
                    if (store.select(summary.id)) {
                        ProbeStatus.write(activity, "已切换模型密钥：${summary.masked}")
                        onChanged()
                        dialog?.dismiss()
                        activity.window.decorView.post { show() }
                    }
                }
            }
            addView(
                AppUi.iconTile(
                    activity,
                    R.drawable.ic_key,
                    if (summary.active) AppUi.primary else AppUi.textSecondary,
                    if (summary.active) AppUi.primarySoft else AppUi.surfaceSubtle,
                ),
                LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    marginEnd = dp(12)
                },
            )
            val copy = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            copy.addView(
                AppUi.textView(
                    activity,
                    "Key ${index + 1}",
                    16f,
                    AppUi.text,
                    medium = true,
                ),
            )
            val detail = buildString {
                append(summary.masked)
                appendLine()
                if (summary.active) {
                    append("使用中")
                } else {
                    append("点击设为使用中")
                }
                append(" · 添加于 ${formatTime(summary.createdAt)}")
            }
            copy.addView(
                AppUi.textView(
                    activity,
                    detail,
                    13f,
                    if (summary.active) AppUi.primary else AppUi.textSecondary,
                ).apply {
                    setPadding(0, dp(3), 0, 0)
                },
            )
            addView(
                copy,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                ImageButton(activity).apply {
                    setImageResource(R.drawable.ic_trash)
                    setColorFilter(AppUi.danger)
                    contentDescription = "删除 ${summary.masked}"
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    background = AppUi.filledRipple(activity, AppUi.dangerSoft, 8f)
                    setOnClickListener {
                        confirmDelete(summary)
                    }
                },
                LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    marginStart = dp(10)
                },
            )
        }
    }

    private fun showAddDialog() {
        val input = EditText(activity).apply {
            textSize = 16f
            setTextColor(AppUi.text)
            setHintTextColor(AppUi.textMuted)
            hint = "输入模型服务密钥"
            inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            minHeight = dp(52)
            background = activity.getDrawable(R.drawable.ui_input_background)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        container.addView(input, MainUiComponents.fullWidth())
        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("添加 $providerLabel 模型密钥")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isEmpty()) {
                    ProbeStatus.write(activity, "模型密钥不能为空。")
                } else {
                    store.save(key, providerId)
                    ProbeStatus.write(activity, "模型密钥已加密保存并设为使用中。")
                    onChanged()
                }
                activity.window.decorView.post { show() }
            }
            .setNegativeButton("取消") { _, _ ->
                activity.window.decorView.post { show() }
            }
            .setOnCancelListener {
                activity.window.decorView.post { show() }
            }
            .show()
    }

    private fun confirmDelete(summary: ApiKeySummary) {
        MainUiComponents.showDestructiveDialog(
            context = activity,
            title = "删除模型密钥",
            message = "只会删除 $providerLabel 的 ${summary.masked} 这条加密密钥，" +
                "不会清除聊天记忆或任务队列。" +
                "若删除正在使用的 Key，会自动切换到下一条；没有剩余 Key 时将无法生成回复。",
            positiveLabel = "删除",
            onConfirm = {
                if (store.delete(summary.id)) {
                    ProbeStatus.write(activity, "已删除模型密钥：${summary.masked}")
                    onChanged()
                }
                dialog?.dismiss()
                activity.window.decorView.post { show() }
            },
        )
    }

    private fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0L) {
            return "时间未知"
        }
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))
    }

    private fun dp(value: Int): Int = AppUi.dp(activity, value)
}
