package com.local.wxbotprobe

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.local.wxbotprobe.bot.ReplyRetryStatus
import com.local.wxbotprobe.bot.ReplyRetryStore
import com.local.wxbotprobe.bot.ReplyRetryTask
import com.local.wxbotprobe.memory.writeTextAtomically
import com.local.wxbotprobe.send.AutoSendUsageStore
import com.local.wxbotprobe.send.Delivery
import com.local.wxbotprobe.send.DeliveryStatus
import com.local.wxbotprobe.send.DeliveryStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class QueueAdminDialog(
    private val activity: Activity,
    private val onChanged: () -> Unit,
) {
    private val deliveryStore = DeliveryStore(
        File(activity.getExternalFilesDir(null) ?: activity.filesDir, "outbox"),
    )
    private val retryStore = ReplyRetryStore(File(activity.filesDir, "reply-retries"))
    private var dialog: AlertDialog? = null

    fun show() {
        val deliveries = deliveryStore.all()
            .filter { it.status != DeliveryStatus.SENT }
        val retries = retryStore.all()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }

        if (deliveries.isEmpty() && retries.isEmpty()) {
            content.addView(
                TextView(activity).apply {
                    text = "没有待处理或失败的发送、生成任务。"
                    textSize = 15f
                    setTextColor(AppUi.textSecondary)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(28), 0, dp(28))
                },
            )
        } else {
            if (deliveries.isNotEmpty()) {
                content.addView(sectionTitle("发送队列 ${deliveries.size}"))
                deliveries.forEach { delivery ->
                    content.addView(deliveryRow(delivery))
                }
            }
            if (retries.isNotEmpty()) {
                content.addView(sectionTitle("生成重试 ${retries.size}"))
                retries.forEach { task ->
                    content.addView(retryRow(task))
                }
            }
        }

        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("发送与生成队列")
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun deliveryRow(delivery: Delivery): View {
        return taskCard(
            heading = "#${delivery.id.take(8)} · ${statusLabel(delivery.status)}",
            detail = buildString {
                append("创建 ${formatTime(delivery.createdAt)}")
                append(" · 更新 ${formatTime(delivery.updatedAt)}")
                appendLine()
                append("会话 ${delivery.conversationKey}")
                appendLine()
                append(
                    "尝试 ${delivery.attempts}/${Delivery.MAX_ATTEMPTS} · " +
                        "进度 ${delivery.nextPartIndex}/${delivery.parts.size}",
                )
                append(" · ${nextAttempt(delivery.nextAttemptAt)}")
                if (delivery.lastError.isNotBlank()) {
                    appendLine()
                    append("错误：${delivery.lastError}")
                }
            },
            preview = delivery.parts.joinToString("\n").take(260),
            confirmSent = if (delivery.status == DeliveryStatus.UNKNOWN) {
                {
                    confirmUnknownSent(delivery)
                }
            } else {
                null
            },
            retry = {
                if (delivery.status == DeliveryStatus.UNKNOWN) {
                    confirmUnknownRetry {
                        resetDelivery(delivery)
                    }
                } else {
                    resetDelivery(delivery)
                }
            },
            remove = {
                confirmRemove("移除发送任务") {
                    guardedEdit {
                        deliveryStore.remove(delivery.id)
                        markJson(
                            delivery.sourceReplyFile,
                            "deliveryStatus" to "cancelled_by_user",
                            "lastError" to "removed by operator",
                        )
                        ProbeStatus.write(activity, "发送任务已移除。")
                        onChanged()
                    }
                }
            },
        )
    }

    private fun retryRow(task: ReplyRetryTask): View {
        return taskCard(
            heading = "#${task.id.take(8)} · ${statusLabel(task.status)} · ${task.chatTitle}",
            detail = buildString {
                append("创建 ${formatTime(task.createdAt)}")
                append(" · 更新 ${formatTime(task.updatedAt)}")
                appendLine()
                append("尝试 ${task.attempts}/${ReplyRetryTask.MAX_ATTEMPTS}")
                append(" · ${nextAttempt(task.nextAttemptAt)}")
                if (task.lastError.isNotBlank()) {
                    appendLine()
                    append("错误：${task.lastError}")
                }
            },
            preview = task.text.take(260),
            retry = {
                guardedEdit {
                    retryStore.manualRetry(task.id)
                    ProbeStatus.write(activity, "生成任务已重置，重新启动代理后会处理。")
                    onChanged()
                }
            },
            remove = {
                confirmRemove("移除生成任务") {
                    guardedEdit {
                        retryStore.remove(task.id)
                        markJson(
                            task.sourceFile,
                            "replyRetryStatus" to "cancelled_by_user",
                            "replyRetryLastError" to "removed by operator",
                        )
                        ProbeStatus.write(activity, "生成任务已移除。")
                        onChanged()
                    }
                }
            },
        )
    }

    private fun taskCard(
        heading: String,
        detail: String,
        preview: String,
        confirmSent: (() -> Unit)? = null,
        retry: () -> Unit,
        remove: () -> Unit,
    ): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            addView(
                TextView(activity).apply {
                    text = heading
                    textSize = 16f
                    setTextColor(AppUi.text)
                },
            )
            addView(
                TextView(activity).apply {
                    text = detail
                    textSize = 13f
                    setTextColor(AppUi.textSecondary)
                    setPadding(0, dp(5), 0, 0)
                },
            )
            addView(
                TextView(activity).apply {
                    text = preview
                    textSize = 14f
                    setTextColor(AppUi.text)
                    setPadding(0, dp(8), 0, dp(8))
                },
            )
            confirmSent?.let { action ->
                addView(
                    commandButton("确认本条已发送", action),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(44),
                    ).apply { bottomMargin = dp(8) },
                )
            }
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        commandButton("立即重试", retry),
                        LinearLayout.LayoutParams(0, dp(44), 1f),
                    )
                    addView(
                        commandButton("移除", remove),
                        LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                            marginStart = dp(10)
                        },
                    )
                },
            )
            addView(
                View(activity).apply {
                    setBackgroundColor(Color.argb(24, 120, 132, 144))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1),
                ).apply { topMargin = dp(12) },
            )
        }
    }

    private fun commandButton(label: String, action: () -> Unit): Button {
        return Button(activity).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = 18f
            setTextColor(AppUi.text)
            setPadding(0, dp(18), 0, dp(4))
        }
    }

    private fun guardedEdit(block: () -> Unit) {
        if (BotLoopService.runState != AgentRunState.OFF) {
            ProbeStatus.write(activity, "请先停止代理，再修改发送或生成任务。")
            return
        }
        runCatching(block).onFailure {
            ProbeStatus.write(activity, "队列操作失败：${it.message}")
        }
        onChanged()
        dialog?.dismiss()
        show()
    }

    private fun resetDelivery(delivery: Delivery) {
        guardedEdit {
            deliveryStore.manualRetry(
                delivery.id,
                acknowledgeUnknownResult =
                    delivery.status == DeliveryStatus.UNKNOWN,
            ) ?: throw IllegalStateException("发送任务状态已变化，请重新打开队列")
            ProbeStatus.write(activity, "发送任务已重置，重新启动代理后会处理。")
            onChanged()
        }
    }

    private fun confirmUnknownRetry(action: () -> Unit) {
        if (BotLoopService.runState != AgentRunState.OFF) {
            ProbeStatus.write(activity, "请先停止代理，再修改发送或生成任务。")
            return
        }
        MainUiComponents.showDestructiveDialog(
            context = activity,
            title = "确认发送结果",
            message = "该任务在点击发送后中断，微信可能已经发出。请先检查原聊天；" +
                "只有确认未发出时才重试，否则可能产生重复消息。",
            positiveLabel = "已检查，重试",
            onConfirm = action,
        )
    }

    private fun confirmUnknownSent(delivery: Delivery) {
        if (BotLoopService.runState != AgentRunState.OFF) {
            ProbeStatus.write(activity, "请先停止代理，再修改发送或生成任务。")
            return
        }
        MainUiComponents.showDestructiveDialog(
            context = activity,
            title = "确认本条已发送",
            message = "仅当已检查原聊天并看到这一条消息时继续。确认后会推进一个分段、" +
                "计入今日发送数；剩余分段仍需要代理运行后继续。",
            positiveLabel = "确认已发送",
            onConfirm = {
                guardedEdit {
                    val now = System.currentTimeMillis()
                    val updated = delivery.confirmUnknownPartSent(now)
                    deliveryStore.save(updated)
                    AutoSendUsageStore(activity).increment(now)
                    markJson(
                        updated.sourceReplyFile,
                        "sent" to (updated.status == DeliveryStatus.SENT),
                        "deliveryStatus" to updated.status.name.lowercase(),
                        "sentParts" to updated.nextPartIndex,
                        "totalParts" to updated.parts.size,
                        "lastError" to "",
                    )
                    ProbeStatus.write(
                        activity,
                        "已确认发送 ${updated.nextPartIndex}/${updated.parts.size} 条。",
                    )
                    onChanged()
                }
            },
        )
    }

    private fun confirmRemove(title: String, action: () -> Unit) {
        if (BotLoopService.runState != AgentRunState.OFF) {
            ProbeStatus.write(activity, "请先停止代理，再修改发送或生成任务。")
            return
        }
        MainUiComponents.showDestructiveDialog(
            context = activity,
            title = title,
            message = "该任务会从持久队列中删除，未发送或未生成的内容不会继续处理。",
            positiveLabel = "移除",
            onConfirm = action,
        )
    }

    private fun markJson(path: String, vararg values: Pair<String, Any>) {
        val file = File(path)
        if (!file.isFile) {
            return
        }
        runCatching {
            val root = JSONObject(file.readText())
            values.forEach { (key, value) -> root.put(key, value) }
            writeTextAtomically(file, root.toString(2))
        }
    }

    private fun statusLabel(status: DeliveryStatus): String {
        return when (status) {
            DeliveryStatus.PENDING -> "待发送"
            DeliveryStatus.PARTIAL -> "部分已发送"
            DeliveryStatus.UNKNOWN -> "发送结果未知"
            DeliveryStatus.FAILED -> "发送失败"
            DeliveryStatus.SENT -> "已发送"
        }
    }

    private fun statusLabel(status: ReplyRetryStatus): String {
        return when (status) {
            ReplyRetryStatus.PENDING -> "等待生成"
            ReplyRetryStatus.FAILED -> "生成失败"
        }
    }

    private fun nextAttempt(epochMillis: Long): String {
        if (epochMillis <= System.currentTimeMillis()) {
            return "可立即处理"
        }
        return "下次 ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(epochMillis))}"
    }

    private fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0L) {
            return "时间未知"
        }
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(epochMillis))
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        fun summary(activity: Activity): String {
            val deliveries = DeliveryStore(
                File(activity.getExternalFilesDir(null) ?: activity.filesDir, "outbox"),
            ).all().filter { it.status != DeliveryStatus.SENT }
            val retries = ReplyRetryStore(File(activity.filesDir, "reply-retries")).all()
            val failed = deliveries.count { it.status == DeliveryStatus.FAILED } +
                retries.count { it.status == ReplyRetryStatus.FAILED }
            val review = deliveries.count { it.status == DeliveryStatus.UNKNOWN }
            if (deliveries.isEmpty() && retries.isEmpty()) {
                return "没有待处理任务"
            }
            return buildString {
                append("待发送 ${deliveries.count { it.waiting }}")
                append(" · 等待生成 ${retries.count { it.waiting }}")
                if (review > 0) {
                    append(" · 需确认 $review")
                }
                if (failed > 0) {
                    append(" · 失败 $failed")
                }
            }
        }
    }
}
