package com.local.wxbotprobe

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.local.wxbotprobe.memory.ChatMemoryStore
import com.local.wxbotprobe.memory.v2.LegacyImportResult
import com.local.wxbotprobe.memory.v2.LegacyMigrationContactPlan
import com.local.wxbotprobe.memory.v2.LegacyMigrationPlan
import com.local.wxbotprobe.memory.v2.MemoryV2LegacyImportService
import com.local.wxbotprobe.memory.v2.MemoryV2ReadPath
import com.local.wxbotprobe.memory.v2.MemoryV2ReadPathService
import java.io.File

class MemoryV2MigrationSection(
    private val activity: Activity,
    private val onChanged: () -> Unit,
) {
    private lateinit var migrateRow: MainUiComponents.ActionRow
    private lateinit var rollbackRow: MainUiComponents.ActionRow
    private lateinit var stateDetail: TextView
    private var busy = false

    fun createView(): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(
            MainUiComponents.sectionLabel(activity, "记忆格式").apply {
                MainUiComponents.topPadding(this, activity, 22)
            },
        )
        stateDetail = AppUi.textView(
            activity,
            "",
            13f,
            AppUi.textSecondary,
        ).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = AppUi.rounded(activity, AppUi.surface, 8f, AppUi.border)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        container.addView(stateDetail, MainUiComponents.fullWidth())

        migrateRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_refresh,
            "迁移旧聊天记忆",
            "预览联系人映射和可导入数量",
            onClick = ::previewMigration,
        )
        rollbackRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_back,
            "回退到旧记忆读取",
            "只切换读取入口，不删除 2.0 数据",
            onClick = ::confirmRollback,
        )
        container.addView(
            MainUiComponents.groupViews(
                activity,
                listOf(migrateRow.view, rollbackRow.view),
            ),
            MainUiComponents.fullWidth().apply { topMargin = dp(10) },
        )
        return container
    }

    fun refresh() {
        if (!::migrateRow.isInitialized) {
            return
        }
        val snapshot = MemoryV2ReadPathService(activity).snapshot()
        val usingV2 = snapshot.enabled && snapshot.path == MemoryV2ReadPath.V2
        stateDetail.text = if (usingV2) {
            "当前读取：Memory 2.0（验证）\n" +
                "旧 JSON 仅保留为回退快照；后台自动抽取调度尚未接入。"
        } else {
            "当前读取：旧 JSON 记忆\n迁移前会先生成只读预览，不删除原记忆。"
        }
        migrateRow.detail.text = if (usingV2) {
            "可重新预览当前旧格式数据"
        } else {
            "预览联系人映射和可导入数量"
        }
        rollbackRow.view.visibility = if (usingV2) View.VISIBLE else View.GONE
        setBusy(busy)
    }

    private fun previewMigration() {
        if (!requireAgentStopped()) {
            return
        }
        setBusy(true)
        Thread(
            {
                val result = runCatching {
                    val store = store()
                    val service = MemoryV2LegacyImportService(activity)
                    service to service.plan(store)
                }
                activity.runOnUiThread {
                    setBusy(false)
                    result.fold(
                        onSuccess = { (service, plan) ->
                            showPlan(service, plan)
                        },
                        onFailure = {
                            ProbeStatus.write(
                                activity,
                                "迁移预览失败：${it.message.orEmpty()}",
                            )
                            onChanged()
                        },
                    )
                }
            },
            "memory-v2-migration-preview",
        ).start()
    }

    private fun showPlan(
        service: MemoryV2LegacyImportService,
        plan: LegacyMigrationPlan,
    ) {
        val entries = store().allChats().associateBy { it.chatId }
        val contacts = plan.contacts.filter { it.items.isNotEmpty() }
        val importableContacts = contacts.filter {
            it.items.any { item -> item.state == "importable" }
        }
        val changedContacts = plan.contacts.filter {
            it.items.any { item -> item.state == "importable" }
        }
        if (changedContacts.isEmpty()) {
            MainUiComponents.dialogBuilder(activity)
                .setTitle("没有可迁移的记忆")
                .setMessage(
                    "旧数据中没有同时具备来源消息和有效时间的记忆。" +
                        "无来源摘要仍会按隔离项记录，但不会进入回答召回。",
                )
                .setPositiveButton("关闭", null)
                .show()
            return
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        content.addView(
            summaryText(
                "可导入 ${plan.importable} 项，隔离 ${plan.quarantined} 项，" +
                    "过期 ${plan.expired} 项，阻止 ${plan.blocked} 项。",
            ),
            MainUiComponents.fullWidth(),
        )
        content.addView(
            summaryText(
                "确认每个联系人仍对应同一个真实聊天。导入只接受带原始证据的旧事实；" +
                    "不会把无来源摘要升级为事实。",
            ),
            MainUiComponents.fullWidth().apply { topMargin = dp(8) },
        )
        val rows = contacts.map { contact ->
            val entry = entries[contact.contactId]
            contactRow(contact, entry?.displayName.orEmpty(), entry?.chatId.orEmpty())
        }
        content.addView(
            MainUiComponents.groupViews(activity, rows),
            MainUiComponents.fullWidth().apply { topMargin = dp(14) },
        )
        val scroll = ScrollView(activity).apply {
            addView(content)
        }
        MainUiComponents.dialogBuilder(activity)
            .setTitle("确认旧记忆映射")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始导入") { _, _ ->
                importPlan(
                    service = service,
                    plan = plan,
                    approvedContactIds = importableContacts.map {
                        it.contactId
                    }.toSet(),
                )
            }
            .show()
    }

    private fun importPlan(
        service: MemoryV2LegacyImportService,
        plan: LegacyMigrationPlan,
        approvedContactIds: Set<String>,
    ) {
        setBusy(true)
        Thread(
            {
                val result = runCatching {
                    service.import(
                        store = store(),
                        legacyRoot = File(activity.filesDir, "memory"),
                        plan = plan,
                        approvedContactIds = approvedContactIds,
                    )
                }
                activity.runOnUiThread {
                    setBusy(false)
                    result.fold(::showImportResult) {
                        ProbeStatus.write(
                            activity,
                            "旧记忆导入失败：${it.message.orEmpty()}",
                        )
                        onChanged()
                    }
                }
            },
            "memory-v2-migration-import",
        ).start()
    }

    private fun showImportResult(result: LegacyImportResult) {
        if (
            (result.status == "saved" || result.status == "unchanged") &&
            !result.runId.isNullOrBlank()
        ) {
            val text = if (result.status == "unchanged") {
                "这份旧数据已经导入过，没有重复写入。"
            } else {
                "导入 ${result.imported} 项，隔离 ${result.quarantined} 项，" +
                    "过期 ${result.expired} 项，阻止 ${result.blocked} 项。"
            }
            MainUiComponents.dialogBuilder(activity)
                .setTitle("旧记忆导入完成")
                .setMessage(
                    "$text\n\n启用后会停止从旧 JSON 召回，回答改用 Memory 2.0。" +
                        "新消息会写入 2.0 源消息，但后台自动提取与摘要调度尚未接入，" +
                        "不会自动形成新的长期记忆。旧数据不会删除，可随时回退。",
                )
                .setNegativeButton("稍后启用", null)
                .setPositiveButton("启用 2.0") { _, _ ->
                    activate(result.runId)
                }
                .show()
            return
        }
        val message = when (result.status) {
            "needs_confirmation" -> "联系人映射尚未确认，未写入任何记忆。"
            "blocked" -> "目标联系人已有删除屏障，旧记忆导入已停止。"
            else -> "导入失败：${result.errorCode.orEmpty()}"
        }
        MainUiComponents.dialogBuilder(activity)
            .setTitle("未启用 Memory 2.0")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun activate(runId: String) {
        if (!requireAgentStopped()) {
            return
        }
        val pendingLegacySummaries =
            com.local.wxbotprobe.memory.MemorySummaryWorker.pendingCount(activity)
        if (pendingLegacySummaries > 0) {
            MainUiComponents.dialogBuilder(activity)
                .setTitle("旧记忆仍在整理")
                .setMessage(
                    "还有 $pendingLegacySummaries 个旧会话等待整理。" +
                        "请等待后台整理完成后再启用 2.0 读取。",
                )
                .setPositiveButton("关闭", null)
                .show()
            return
        }
        setBusy(true)
        Thread(
            {
                val result = MemoryV2ReadPathService(activity).activate(runId)
                activity.runOnUiThread {
                    setBusy(false)
                    ProbeStatus.write(
                        activity,
                        if (result.status == "activated") {
                            "已启用 Memory 2.0 读取。"
                        } else {
                            "Memory 2.0 启用失败：${result.errorCode.orEmpty()}"
                        },
                    )
                    onChanged()
                }
            },
            "memory-v2-activate",
        ).start()
    }

    private fun confirmRollback() {
        if (!requireAgentStopped()) {
            return
        }
        MainUiComponents.showDestructiveDialog(
            context = activity,
            title = "回退到旧记忆读取",
            message = "只切换读取入口，不删除 Memory 2.0 已导入的数据。" +
                "若之前发生删除，不会恢复已删除内容。",
            positiveLabel = "回退",
            onConfirm = {
                setBusy(true)
                Thread(
                    {
                        val result = MemoryV2ReadPathService(activity).rollback()
                        activity.runOnUiThread {
                            setBusy(false)
                            ProbeStatus.write(
                                activity,
                                if (result.status == "rolled_back") {
                                    "已回退到旧记忆读取。"
                                } else {
                                    "回退失败：${result.errorCode.orEmpty()}"
                                },
                            )
                            onChanged()
                        }
                    },
                    "memory-v2-rollback",
                ).start()
            },
        )
    }

    private fun contactRow(
        contact: LegacyMigrationContactPlan,
        displayName: String,
        chatId: String,
    ): View {
        val importable = contact.items.count { it.state == "importable" }
        val quarantined = contact.items.count { it.state == "quarantined" }
        val expired = contact.items.count { it.state == "expired" }
        val blocked = contact.items.count { it.state == "blocked" }
        val suffix = chatId.take(8).ifBlank { contact.contactId.take(8) }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(
                AppUi.textView(
                    activity,
                    "${displayName.ifBlank { "未命名联系人" }} · 真实聊天",
                    15f,
                    AppUi.text,
                    medium = true,
                ),
            )
            addView(
                AppUi.textView(
                    activity,
                    "本地 ID $suffix · 可导入 $importable · 隔离 $quarantined · " +
                        "过期 $expired · 阻止 $blocked",
                    12f,
                    AppUi.textSecondary,
                ).apply {
                    setPadding(0, dp(4), 0, 0)
                },
            )
        }
    }

    private fun summaryText(value: String): TextView {
        return AppUi.textView(activity, value, 13f, AppUi.textSecondary).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
    }

    private fun requireAgentStopped(): Boolean {
        if (BotLoopService.runState == AgentRunState.OFF) {
            return true
        }
        ProbeStatus.write(activity, "请先停止代理，再切换或迁移记忆格式。")
        return false
    }

    private fun setBusy(value: Boolean) {
        busy = value
        if (!::migrateRow.isInitialized) {
            return
        }
        migrateRow.view.isEnabled = !value
        rollbackRow.view.isEnabled = !value
        val alpha = if (value) 0.5f else 1f
        migrateRow.view.alpha = alpha
        rollbackRow.view.alpha = alpha
    }

    private fun store(): ChatMemoryStore {
        return ChatMemoryStore(File(activity.filesDir, "memory"))
    }

    private fun dp(value: Int): Int = AppUi.dp(activity, value)
}
