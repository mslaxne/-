package com.local.wxbotprobe

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.local.wxbotprobe.bot.EffectiveRoleplayPolicy
import com.local.wxbotprobe.bot.PersonaIdentityMode
import com.local.wxbotprobe.bot.PersonaSelectionService
import com.local.wxbotprobe.bot.RoleplayConsentRecord
import com.local.wxbotprobe.bot.RoleplayPolicyService
import com.local.wxbotprobe.memory.v2.MemoryV2AdminStore
import com.local.wxbotprobe.memory.v2.MemoryV2A0ProposalRow
import com.local.wxbotprobe.memory.v2.MemoryV2ConflictRow
import com.local.wxbotprobe.memory.v2.MemoryV2ConflictService
import com.local.wxbotprobe.memory.v2.MemoryV2ContactOverview
import com.local.wxbotprobe.memory.v2.MemoryV2MemoryRow
import com.local.wxbotprobe.memory.v2.MemoryV2ManualMemoryService
import com.local.wxbotprobe.memory.v2.MemoryV2MigrationPreview
import com.local.wxbotprobe.memory.v2.MemoryV2PersonaMigrationService
import com.local.wxbotprobe.memory.v2.MemoryV2PumpState
import com.local.wxbotprobe.memory.v2.MemoryV2PumpStatus
import com.local.wxbotprobe.memory.v2.MemoryV2PersonaScopeService
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextSnapshot
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextSnapshotBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryPageView(
    private val activity: Activity,
    private val onRefreshRequested: () -> Unit,
) {
    val view: LinearLayout

    private lateinit var chatsContainer: LinearLayout
    private lateinit var contactsValue: TextView
    private lateinit var extractedValue: TextView
    private lateinit var pendingExtractionValue: TextView
    private lateinit var memoriesValue: TextView
    private lateinit var eventsValue: TextView
    private lateinit var extractionStatusDetail: TextView

    init {
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)

            addView(MainUiComponents.sectionLabel(activity, "Memory 2.0 概览"))
            val metrics = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = AppUi.rounded(activity, AppUi.surface, 8f, AppUi.border)
                setPadding(dp(8), dp(14), dp(8), dp(14))
            }
            contactsValue = metricValue("0")
            extractedValue = metricValue("0")
            pendingExtractionValue = metricValue("0")
            memoriesValue = metricValue("0")
            eventsValue = metricValue("0")
            addMetricCell(metrics, "联系人", contactsValue, false)
            addMetricCell(metrics, "已提取", extractedValue, true)
            addMetricCell(metrics, "未提取", pendingExtractionValue, true)
            addMetricCell(metrics, "长期记忆", memoriesValue, true)
            addMetricCell(metrics, "待办", eventsValue, true)
            addView(metrics, MainUiComponents.fullWidth())

            extractionStatusDetail = AppUi.textView(
                activity,
                "",
                13f,
                AppUi.textSecondary,
            ).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = AppUi.rounded(
                    activity,
                    AppUi.surface,
                    8f,
                    AppUi.border,
                )
            }
            addView(
                extractionStatusDetail,
                MainUiComponents.fullWidth().apply { topMargin = dp(10) },
            )

            addView(
                AppUi.textView(
                    activity,
                    "当前读取：Memory 2.0\n原始消息、会话和长期记忆都保存在 v2 数据库。",
                    13f,
                    AppUi.textSecondary,
                ).apply {
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = AppUi.rounded(
                        activity,
                        AppUi.surface,
                        8f,
                        AppUi.border,
                    )
                    setLineSpacing(dp(3).toFloat(), 1f)
                },
                MainUiComponents.fullWidth().apply { topMargin = dp(10) },
            )

            addView(
                MainUiComponents.sectionLabel(activity, "联系人记忆").apply {
                    MainUiComponents.topPadding(this, activity, 22)
                },
            )
            chatsContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(chatsContainer, MainUiComponents.fullWidth())

            addView(
                MainUiComponents.groupViews(
                    activity,
                    listOf(
                        MainUiComponents.actionRow(
                            activity,
                            R.drawable.ic_trash,
                            "清空全部 Memory 2.0",
                            "删除联系人、消息、长期记忆和待办",
                            danger = true,
                            onClick = ::confirmClearAll,
                        ).view,
                    ),
                ),
                MainUiComponents.fullWidth().apply { topMargin = dp(22) },
            )
        }
    }

    fun refresh() {
        if (!::contactsValue.isInitialized) {
            return
        }
        val overview = admin().overview()
        contactsValue.text = String.format(Locale.CHINA, "%d", overview.contacts.size)
        extractedValue.text = String.format(
            Locale.CHINA,
            "%d",
            overview.extractedMessageCount,
        )
        pendingExtractionValue.text = String.format(
            Locale.CHINA,
            "%d",
            overview.pendingExtractionCount,
        )
        memoriesValue.text = String.format(Locale.CHINA, "%d", overview.memoryCount)
        eventsValue.text = String.format(Locale.CHINA, "%d", overview.pendingCommitments)
        val issueText = if (overview.extractionIssueCount > 0) {
            String.format(
                Locale.CHINA,
                " · 异常 %d",
                overview.extractionIssueCount,
            )
        } else {
            ""
        }
        extractionStatusDetail.text = String.format(
            Locale.CHINA,
            "%s · 摘要完成 %d · 缺口 %d · 待处理 %d · 待审核 %d%s",
            when (MemoryV2PumpStatus.state()) {
                MemoryV2PumpState.RUNNING -> "后台提取中"
                MemoryV2PumpState.PAUSED_HOURLY ->
                    "小时额度已用完 · 下个整点继续"
                MemoryV2PumpState.PAUSED_DAILY ->
                    "今日 API 费用已达上限 · 明日继续"
                MemoryV2PumpState.IDLE -> "等待触发"
            },
            overview.summaryDoneCount,
            overview.summaryGapCount,
            overview.pendingSummaryCount,
            overview.pendingConflicts,
            issueText,
        )

        chatsContainer.removeAllViews()
        if (overview.contacts.isEmpty()) {
            chatsContainer.addView(emptyState(), MainUiComponents.fullWidth())
            return
        }
        val rows = overview.contacts.map { contact ->
            val persona = PersonaSelectionService(activity)
                .selectedForContact(contact.contactId)
            MainUiComponents.contactRow(
                activity,
                contact.displayName.ifBlank { "未命名联系人" },
                "${persona.name} · 已提取 ${contact.extractedMessageCount} · " +
                    "未提取 ${contact.pendingExtractionCount} · " +
                    "${contact.memoryCount} 记忆 · " +
                    "${contact.pendingCommitments} 待办 · " +
                    "${contact.pendingConflicts} 待审核",
            ) {
                showContact(contact)
            }
        }
        chatsContainer.addView(
            MainUiComponents.groupViews(activity, rows),
            MainUiComponents.fullWidth(),
        )
    }

    private fun emptyState(): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(28), dp(24), dp(28))
            background = AppUi.rounded(activity, AppUi.surface, 8f, AppUi.border)
            addView(AppUi.icon(activity, R.drawable.ic_chat, AppUi.textMuted, 28))
            addView(
                AppUi.textView(
                    activity,
                    "还没有 Memory 2.0 数据",
                    16f,
                    AppUi.text,
                    medium = true,
                ).apply { gravity = Gravity.CENTER },
                MainUiComponents.wrapContent().apply { topMargin = dp(10) },
            )
            addView(
                AppUi.textView(
                    activity,
                    "代理识别到新消息后会在这里建立",
                    13f,
                    AppUi.textSecondary,
                ).apply { gravity = Gravity.CENTER },
                MainUiComponents.wrapContent().apply { topMargin = dp(5) },
            )
        }
    }

    private fun showContact(requestedContact: MemoryV2ContactOverview) {
        val contact = admin().overview().contacts.firstOrNull {
            it.scopeId == requestedContact.scopeId
        } ?: requestedContact
        val memories = admin().memories(contact.scopeId)
        val manual = MemoryV2ManualMemoryService(activity)
        val a0Proposals = admin().pendingA0Proposals(contact.scopeId)
        val conflicts = admin().conflicts(contact.scopeId)
        val persona = PersonaSelectionService(activity)
            .selectedForContact(contact.contactId)
        val roleplay = RoleplayPolicyService(activity)
        val roleplayPolicy = roleplay.effectivePolicy(
            contactId = contact.contactId,
            personaId = persona.personaId,
            persona = persona.persona,
        )
        val roleplayConsent = roleplay.consent(contact.contactId)
        lateinit var dialog: AlertDialog
        val refreshAfterMutation: () -> Unit = {
            dialog.dismiss()
            onRefreshRequested()
            activity.window.decorView.post {
                showContact(contact)
            }
            Unit
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        content.addView(
            AppUi.textView(
                activity,
                "已提取 ${contact.extractedMessageCount} 条 · " +
                    "未提取 ${contact.pendingExtractionCount} 条 · " +
                    "${contact.memoryCount} 条长期记忆\n" +
                    "当前人格 ${persona.name} · scope ${contact.scopeId.takeLast(8)}\n" +
                    "沉浸策略 ${roleplayPolicy.detailLabel()}\n" +
                    "摘要完成 ${contact.summaryDoneCount} · " +
                    "缺口 ${contact.summaryGapCount} · " +
                    "待处理 ${contact.pendingSummaryCount}",
                13f,
                AppUi.textSecondary,
                medium = true,
            ),
            MainUiComponents.fullWidth(),
        )
        content.addView(
            AppUi.textView(activity, "长期记忆", 15f, AppUi.textSecondary, true),
            MainUiComponents.fullWidth().apply { topMargin = dp(18) },
        )
        if (memories.isEmpty()) {
            content.addView(
                AppUi.textView(activity, "暂无长期记忆", 14f, AppUi.textMuted).apply {
                    setPadding(dp(16), dp(15), dp(16), dp(15))
                },
                MainUiComponents.fullWidth(),
            )
        } else {
            listOf(
                Triple("A0", "核心事实", memories.filter { it.tier == "A0" }),
                Triple("A1", "长期事实", memories.filter { it.tier == "A1" }),
                Triple("A2", "近期事件", memories.filter { it.tier == "A2" }),
            ).forEach { (tier, label, items) ->
                if (items.isEmpty()) {
                    return@forEach
                }
                content.addView(
                    AppUi.textView(
                        activity,
                        "$tier · $label",
                        14f,
                        AppUi.textSecondary,
                        true,
                    ),
                    MainUiComponents.fullWidth().apply { topMargin = dp(12) },
                )
                content.addView(
                    MainUiComponents.groupViews(
                        activity,
                        items.map { memory ->
                            memoryRow(contact, memory, manual, refreshAfterMutation)
                        },
                    ),
                    MainUiComponents.fullWidth().apply { topMargin = dp(6) },
                )
            }
        }

        if (a0Proposals.isNotEmpty()) {
            content.addView(
                AppUi.textView(
                    activity,
                    "待审核 A0 提案",
                    15f,
                    AppUi.textSecondary,
                    true,
                ),
                MainUiComponents.fullWidth().apply { topMargin = dp(18) },
            )
            content.addView(
                MainUiComponents.groupViews(
                    activity,
                    a0Proposals.map { proposal ->
                        a0ProposalRow(proposal) {
                            showA0ProposalReview(proposal) {
                                dialog.dismiss()
                                onRefreshRequested()
                            }
                        }
                    },
                ),
                MainUiComponents.fullWidth().apply { topMargin = dp(8) },
            )
        }

        if (conflicts.isNotEmpty()) {
            content.addView(
                AppUi.textView(
                    activity,
                    "待审核记忆变更",
                    15f,
                    AppUi.textSecondary,
                    true,
                ),
                MainUiComponents.fullWidth().apply { topMargin = dp(18) },
            )
            content.addView(
                MainUiComponents.groupViews(
                    activity,
                    conflicts.map { conflict ->
                        conflictRow(conflict) {
                            showConflictResolution(conflict) {
                                dialog.dismiss()
                                onRefreshRequested()
                            }
                        }
                    },
                ),
                MainUiComponents.fullWidth().apply { topMargin = dp(8) },
            )
        }

        content.addView(
            MainUiComponents.groupViews(
                activity,
                listOf(
                    MainUiComponents.actionRow(
                        activity,
                        R.drawable.ic_person,
                        "切换联系人的人格",
                        "当前 ${persona.name} · 切换前检查目标 scope 记忆",
                        onClick = {
                            showPersonaSwitch(contact) {
                                dialog.dismiss()
                                onRefreshRequested()
                                activity.window.decorView.post {
                                    admin().contacts()
                                        .firstOrNull {
                                            it.contactId == contact.contactId
                                        }
                                        ?.let(::showContact)
                                }
                            }
                        },
                    ).view,
                    MainUiComponents.actionRow(
                        activity,
                        R.drawable.ic_shield_check,
                        "沉浸角色知情确认",
                        roleplayPolicy.consentDetail(roleplayConsent),
                        onClick = {
                            showRoleplayConsent(
                                contact = contact,
                                personaId = persona.personaId,
                                persona = persona.persona,
                                onChanged = refreshAfterMutation,
                            )
                        },
                    ).view,
                    MainUiComponents.actionRow(
                        activity,
                        R.drawable.ic_message_circle,
                        "新增一条记忆",
                        "手动写入事实；错误内容可能造成模型混乱",
                        onClick = {
                            promptAddMemory(contact, manual, refreshAfterMutation)
                        },
                    ).view,
                    MainUiComponents.actionRow(
                        activity,
                        R.drawable.ic_trash,
                        "清空 A2 近期事件",
                        "保留 A0、A1、消息和证据",
                        danger = true,
                        onClick = {
                            confirmClearA2(contact, manual, refreshAfterMutation)
                        },
                    ).view,
                    MainUiComponents.actionRow(
                        activity,
                        R.drawable.ic_chat,
                        "预览回复上下文",
                        "A0/A1 · A3 · 近 7 天 A2 · 当前会话",
                        onClick = {
                            showReplyContextPreview(contact)
                        },
                    ).view,
                    MainUiComponents.actionRow(
                        activity,
                        R.drawable.ic_trash,
                        "删除这个联系人的 Memory",
                        "删除 ${contact.displayName.ifBlank { "未命名联系人" }} 的全部 v2 数据",
                        danger = true,
                        onClick = {
                            dialog.dismiss()
                            confirmDeleteContact(contact)
                        },
                    ).view,
                ),
            ),
            MainUiComponents.fullWidth().apply { topMargin = dp(18) },
        )
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(content)
        }
        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle(contact.displayName.ifBlank { "未命名联系人" })
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create()
        dialog.show()
    }

    private fun EffectiveRoleplayPolicy.detailLabel(): String {
        return when (this) {
            EffectiveRoleplayPolicy.NOT_APPLICABLE -> "非沉浸人格 · 透明"
            EffectiveRoleplayPolicy.TRANSPARENT -> "未确认或已撤销 · 透明"
            EffectiveRoleplayPolicy.CONFIRMED_NONDISCLOSURE ->
                "已确认 · 联系人级不披露"
        }
    }

    private fun EffectiveRoleplayPolicy.consentDetail(
        record: RoleplayConsentRecord?,
    ): String {
        return when (this) {
            EffectiveRoleplayPolicy.NOT_APPLICABLE ->
                "仅沉浸角色人格可启用 · 当前身份询问透明"
            EffectiveRoleplayPolicy.CONFIRMED_NONDISCLOSURE ->
                "已确认当前人格修订 · 可随时撤销并恢复透明"
            EffectiveRoleplayPolicy.TRANSPARENT -> when {
                record?.revokedAt != null -> "已撤销 · 当前透明"
                record != null -> "当前人格与确认记录不一致 · 透明"
                else -> "未确认 · 当前透明"
            }
        }
    }

    private fun showRoleplayConsent(
        contact: MemoryV2ContactOverview,
        personaId: String,
        persona: com.local.wxbotprobe.bot.PersonaData,
        onChanged: () -> Unit,
    ) {
        val service = RoleplayPolicyService(activity)
        if (persona.identityMode != PersonaIdentityMode.IMMERSIVE_COMPANION) {
            MainUiComponents.dialogBuilder(activity)
                .setTitle("沉浸角色知情确认")
                .setMessage(
                    "当前人格不是沉浸角色，不能启用身份不披露。" +
                        "直接询问身份时会使用透明策略。",
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val record = service.consent(contact.contactId)
        val effective = service.effectivePolicy(
            contactId = contact.contactId,
            personaId = personaId,
            persona = persona,
        )
        if (effective == EffectiveRoleplayPolicy.CONFIRMED_NONDISCLOSURE) {
            MainUiComponents.showDestructiveDialog(
                context = activity,
                title = "撤销沉浸角色知情确认",
                message = "撤销后该联系人立即回到透明策略。" +
                    "直接身份询问必须如实说明 AI，且始终不得冒充现实真人。" +
                    "撤销不会修改人格或记忆。",
                positiveLabel = "撤销并恢复透明",
            ) {
                val revoked = service.revoke(contact.contactId)
                ProbeStatus.write(
                    activity,
                    if (revoked != null) {
                        "沉浸角色知情确认已撤销，立即恢复透明策略。"
                    } else {
                        "没有找到可撤销的知情确认。"
                    },
                )
                onChanged()
            }
            return
        }
        if (!requireAgentStopped()) {
            return
        }
        val checkbox = CheckBox(activity).apply {
            setText(R.string.roleplay_consent_checkbox)
            textSize = 14f
            setTextColor(AppUi.text)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(8))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                AppUi.textView(
                    activity,
                    "启用后，对方直接追问身份时，模型可以不主动披露 AI。" +
                        "但不得声称自己是现实中的真人本人，不得虚构身体、位置、" +
                        "现实行动、转账、医疗或紧急承诺。\n\n" +
                        "确认绑定人格 ${personaId.takeLast(8)} 及其当前内容修订。" +
                        "人格内容变更后需重新确认；可随时撤销。",
                    13f,
                    AppUi.textSecondary,
                ).apply {
                    setLineSpacing(dp(3).toFloat(), 1f)
                },
                MainUiComponents.fullWidth(),
            )
            addView(
                checkbox,
                MainUiComponents.fullWidth().apply { topMargin = dp(12) },
            )
        }
        lateinit var dialog: AlertDialog
        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("沉浸角色知情确认")
            .setView(content)
            .setPositiveButton("确认启用", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = checkbox.isChecked
            checkbox.setOnCheckedChangeListener { _, checked ->
                positive.isEnabled = checked
            }
            positive.setOnClickListener {
                if (!checkbox.isChecked) {
                    return@setOnClickListener
                }
                runCatching {
                    service.confirm(
                        contactId = contact.contactId,
                        personaId = personaId,
                        persona = persona,
                    )
                }.onSuccess {
                    ProbeStatus.write(
                        activity,
                        "沉浸角色知情确认已保存。撤销后会立即恢复透明策略。",
                    )
                    dialog.dismiss()
                    onChanged()
                }.onFailure {
                    ProbeStatus.write(
                        activity,
                        "知情确认保存失败：${it.message}",
                    )
                }
            }
        }
        dialog.show()
    }

    private fun showPersonaSwitch(
        contact: MemoryV2ContactOverview,
        onSwitched: () -> Unit,
    ) {
        if (!requireAgentStopped()) {
            return
        }
        val selections = PersonaSelectionService(activity)
        val current = selections.selectedForContact(contact.contactId)
        val active = runCatching {
            selections.activePresets()
        }.getOrElse {
            ProbeStatus.write(activity, "人格预设读取失败：${it.message}")
            return
        }
        if (active.isEmpty()) {
            ProbeStatus.write(activity, "没有可用的人格预设。")
            return
        }
        lateinit var dialog: AlertDialog
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                AppUi.textView(
                    activity,
                    "当前人格：${current.name}\n" +
                        "指定方式：" + (if (current.fromExplicitBinding) {
                            "此联系人单独指定"
                        } else {
                            "跟随全局默认"
                        }) +
                        "\n" +
                        "当前 scope：${contact.scopeId}",
                    13f,
                    AppUi.textSecondary,
                ).apply {
                    setTextIsSelectable(true)
                    setLineSpacing(dp(3).toFloat(), 1f)
                },
                MainUiComponents.fullWidth(),
            )
            addView(
                MainUiComponents.groupViews(
                    activity,
                    listOf(
                        MainUiComponents.actionRow(
                            activity,
                            R.drawable.ic_refresh,
                            "跟随全局默认",
                            if (current.fromExplicitBinding) {
                                "取消此联系人的单独指定"
                            } else {
                                "当前已跟随全局默认"
                            },
                            onClick = {
                                dialog.dismiss()
                                if (current.fromExplicitBinding) {
                                    showFollowGlobalDefaultConfirmation(
                                        contact = contact,
                                        onSwitched = onSwitched,
                                    )
                                } else {
                                    ProbeStatus.write(
                                        activity,
                                        "当前联系人已经在跟随全局默认人格。",
                                    )
                                }
                            },
                        ).view,
                    ),
                ),
                MainUiComponents.fullWidth().apply {
                    topMargin = dp(14)
                },
            )
            addView(
                MainUiComponents.groupViews(
                    activity,
                    active.map { preset ->
                        MainUiComponents.actionRow(
                            activity,
                            if (preset.isDefault) {
                                R.drawable.ic_shield_check
                            } else {
                                R.drawable.ic_person
                            },
                            preset.name,
                            if (preset.id == current.personaId) {
                                "当前使用 · 打开后不会切换"
                            } else {
                                "检查目标 scope 后确认切换"
                            },
                            onClick = {
                                dialog.dismiss()
                                showPersonaSwitchConfirmation(
                                    contact = contact,
                                    targetPersonaId = preset.id,
                                    onSwitched = onSwitched,
                                )
                            },
                        ).view
                    },
                ),
                MainUiComponents.fullWidth().apply {
                    topMargin = dp(8)
                    bottomMargin = dp(8)
                },
            )
        }
        val scroll = ScrollView(activity).apply {
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(520),
            )
        }
        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("切换人格")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun showFollowGlobalDefaultConfirmation(
        contact: MemoryV2ContactOverview,
        onSwitched: () -> Unit,
    ) {
        val selections = PersonaSelectionService(activity)
        val target = runCatching {
            selections.globalDefault(contact.contactId)
        }.getOrElse {
            ProbeStatus.write(activity, "全局默认人格读取失败：${it.message}")
            return
        }
        val scopes = MemoryV2PersonaScopeService(activity)
        val preview = runCatching {
            scopes.preview(contact.contactId, target.personaId)
        }.getOrElse {
            ProbeStatus.write(activity, "目标 scope 检查失败：${it.message}")
            return
        }
        MainUiComponents.dialogBuilder(activity)
            .setTitle("跟随全局默认人格？")
            .setMessage(
                "目标人格：${target.name}\n" +
                    "目标 scope 记忆：${preview.activeMemoryCount} 条\n\n" +
                    "此联系人的单独指定会清除，之后全局默认变化时会同步跟随。" +
                    "旧 scope 保留，不会自动迁移任何记忆。",
            )
            .setPositiveButton("确认跟随") { _, _ ->
                runCatching {
                    scopes.followGlobalDefault(
                        contactId = contact.contactId,
                        displayName = contact.displayName.ifBlank {
                            "未命名联系人"
                        },
                    )
                }.onSuccess { result ->
                    ProbeStatus.write(
                        activity,
                        "已跟随全局默认 ${result.selection.name}，目标 scope " +
                            "现有 ${result.activeMemoryCount} 条记忆。",
                    )
                    onSwitched()
                }.onFailure {
                    ProbeStatus.write(
                        activity,
                        "切换为跟随全局默认失败：${it.message}",
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPersonaSwitchConfirmation(
        contact: MemoryV2ContactOverview,
        targetPersonaId: String,
        onSwitched: () -> Unit,
    ) {
        val selections = PersonaSelectionService(activity)
        val current = selections.selectedForContact(contact.contactId)
        if (current.personaId == targetPersonaId) {
            ProbeStatus.write(activity, "当前联系人已经使用该人格。")
            return
        }
        val scopes = MemoryV2PersonaScopeService(activity)
        val preview = runCatching {
            scopes.preview(contact.contactId, targetPersonaId)
        }.getOrElse {
            ProbeStatus.write(activity, "目标 scope 检查失败：${it.message}")
            return
        }
        val scopeDetail = preview.scopeId?.let { "已存在 · ${it.takeLast(8)}" }
            ?: "尚未创建"
        val message = buildString {
            append("目标人格：")
            append(preview.personaName)
            append("\n目标 scope：")
            append(scopeDetail)
            append("\n目标 scope 记忆：")
            append(preview.activeMemoryCount)
            append(" 条\n\n")
            append("切换后消息、记忆、任务和回复上下文都只读取目标 scope。")
            append("旧 scope 会保留，但不会自动复制或迁移任何记忆。")
        }
        MainUiComponents.dialogBuilder(activity)
            .setTitle("确认切换人格")
            .setMessage(message)
            .setPositiveButton("确认切换") { _, _ ->
                runCatching {
                    scopes.switch(
                        contactId = contact.contactId,
                        personaId = targetPersonaId,
                        displayName = contact.displayName.ifBlank {
                            "未命名联系人"
                        },
                    )
                }.onSuccess { result ->
                    ProbeStatus.write(
                        activity,
                        "已切换到 ${result.selection.name}，" +
                            "目标 scope 现有 ${result.activeMemoryCount} 条记忆。",
                    )
                    onSwitched()
                }.onFailure {
                    ProbeStatus.write(activity, "人格切换失败：${it.message}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showReplyContextPreview(contact: MemoryV2ContactOverview) {
        Thread {
            val result = runCatching {
                MemoryV2ReplyContextSnapshotBuilder(activity)
                    .buildForCurrentProvider(contact.scopeId)
            }
            activity.runOnUiThread {
                result.onSuccess(::showReplyContextSnapshot)
                    .onFailure { error ->
                        AlertDialog.Builder(activity)
                            .setTitle("上下文预览失败")
                            .setMessage(error.message ?: error.javaClass.simpleName)
                            .setPositiveButton("关闭", null)
                            .show()
                    }
            }
        }.start()
    }

    private fun showReplyContextSnapshot(snapshot: MemoryV2ReplyContextSnapshot) {
        val layers = snapshot.layers
        val providerWindow = if (snapshot.providerWindowTokens > 0) {
            String.format(
                Locale.CHINA,
                "%,d tokens",
                snapshot.providerWindowTokens,
            )
        } else {
            "未限制"
        }
        val summary = AppUi.textView(
            activity,
            String.format(
                Locale.CHINA,
                "会话 %s · 消息 %d · 已省略 %d\n" +
                    "模型 %s · 窗口 %s · 预算裁剪 %d\n" +
                    "A0 %d · A1 %d · A2 近 3 天 %d · 3-7 天 %d · " +
                    "疑似合并 %d · 旧事件 %d · 待确认冲突 %d\n" +
                    "A3 %d · UTF-8 %d 字节 · 快照 %s",
                snapshot.sessionId?.takeLast(8) ?: "无",
                layers.sessionMessages.size,
                layers.omittedMessageCount,
                snapshot.providerId.ifBlank { "未选择" },
                providerWindow,
                snapshot.budgetOmittedMessageCount,
                layers.a0.size,
                layers.a1.size,
                layers.a2Exact.size,
                layers.a2Expanded.size,
                layers.a2CollapsedCount,
                layers.olderRelated.size,
                layers.pendingConflicts.size,
                layers.summaryItemCount,
                snapshot.estimatedUtf8Bytes,
                snapshot.snapshotSha256.take(12),
            ),
            13f,
            AppUi.textSecondary,
        ).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val preview = AppUi.textView(
            activity,
            snapshot.promptText,
            11f,
            AppUi.text,
        ).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val previewScroll = ScrollView(activity).apply {
            addView(preview)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(430),
            ).apply {
                topMargin = dp(12)
            }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(summary, MainUiComponents.fullWidth())
            addView(previewScroll, MainUiComponents.fullWidth())
        }
        val dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("回复上下文预览")
            .setView(content)
            .setNeutralButton("复制", null)
            .setPositiveButton("关闭", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val clipboard = activity.getSystemService(
                ClipboardManager::class.java,
            )
            clipboard?.setPrimaryClip(
                ClipData.newPlainText("reply-context", snapshot.promptText),
            )
            ProbeStatus.write(activity, "回复上下文快照已复制。")
        }
    }

    private fun conflictRow(
        conflict: MemoryV2ConflictRow,
        onClick: () -> Unit,
    ): View {
        val existing = conflict.existingText.trim().take(80)
        val candidate = conflict.candidateText.trim().take(80)
        if (conflict.isA0Proposal) {
            return MainUiComponents.actionRow(
                activity,
                R.drawable.ic_shield_check,
                "A0 提案：${candidate.ifBlank { "（空）" }}",
                "当前：$existing · ${conflict.evidenceCount} 条证据",
                onClick = onClick,
            ).view
        }
        return MainUiComponents.actionRow(
            activity,
            R.drawable.ic_shield_check,
            "现有：$existing",
            "候选：$candidate",
            onClick = onClick,
        ).view
    }

    private fun a0ProposalRow(
        proposal: MemoryV2A0ProposalRow,
        onClick: () -> Unit,
    ): View {
        val text = proposal.text.trim().take(80).ifBlank { "（空）" }
        return MainUiComponents.actionRow(
            activity,
            R.drawable.ic_shield_check,
            "A0 提案：$text",
            "${proposal.predicate} · ${proposal.evidenceCount} 条证据",
            onClick = onClick,
        ).view
    }

    private fun showA0ProposalReview(
        proposal: MemoryV2A0ProposalRow,
        onResolved: () -> Unit,
    ) {
        lateinit var dialog: AlertDialog
        var a0Confirmed = false
        fun resolve(decision: String) {
            runCatching {
                admin().resolveA0Proposal(
                    proposal.scopeId,
                    proposal.memoryId,
                    decision,
                    a0Confirmed = a0Confirmed,
                )
            }.onSuccess {
                ProbeStatus.write(
                    activity,
                    if (decision == MemoryV2ConflictService.APPROVE_A0) {
                        "A0 核心事实提案已批准。"
                    } else {
                        "A0 核心事实提案已拒绝。"
                    },
                )
                dialog.dismiss()
                onResolved()
            }.onFailure {
                ProbeStatus.write(
                    activity,
                    "A0 提案审核失败：${it.message}",
                )
            }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val evidence = if (proposal.evidenceQuotes.isEmpty()) {
            "无证据引文"
        } else {
            proposal.evidenceQuotes.take(3).joinToString("\n") { "· $it" }
        }
        content.addView(
            AppUi.textView(
                activity,
                "这是 LLM 新提取的 A0 核心事实，批准前不会进入回复上下文。\n\n" +
                    "${proposal.predicate}\n" +
                    proposal.text.trim() + "\n\n" +
                    "依据：${proposal.epistemic} · " +
                    "${proposal.evidenceCount} 条证据\n\n" +
                    evidence,
                14f,
                AppUi.textSecondary,
            ).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            MainUiComponents.fullWidth(),
        )
        content.addView(
            AppUi.textView(
                activity,
                "A0 会进入所有后续回复的核心上下文。请核对证据与措辞。",
                13f,
                AppUi.paused,
            ).apply {
                setPadding(0, dp(12), 0, dp(4))
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            MainUiComponents.fullWidth(),
        )
        lateinit var approveRow: MainUiComponents.ActionRow
        content.addView(
            CheckBox(activity).apply {
                text = activity.getString(
                    R.string.memory_a0_review_confirm,
                )
                setTextColor(AppUi.text)
                buttonTintList =
                    android.content.res.ColorStateList.valueOf(AppUi.primary)
                setOnCheckedChangeListener { _, checked ->
                    a0Confirmed = checked
                    approveRow.view.isEnabled = checked
                    approveRow.view.alpha = if (checked) 1f else 0.45f
                }
            },
            MainUiComponents.fullWidth(),
        )
        approveRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_shield_check,
            "批准 A0 提案",
            "显式确认后进入核心记忆",
            onClick = {
                resolve(MemoryV2ConflictService.APPROVE_A0)
            },
        )
        approveRow.view.isEnabled = false
        approveRow.view.alpha = 0.45f
        content.addView(
            MainUiComponents.groupViews(
                activity,
                listOf(
                    approveRow.view,
                    MainUiComponents.actionRow(
                        activity,
                        R.drawable.ic_bubble_close,
                        "拒绝 A0 提案",
                        "保留审计记录，不进入长期上下文",
                        onClick = {
                            resolve(MemoryV2ConflictService.REJECT_A0)
                        },
                    ).view,
                ),
            ),
            MainUiComponents.fullWidth().apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            },
        )
        dialog = AlertDialog.Builder(activity)
            .setTitle("审核 A0 核心事实提案")
            .setView(content)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun showConflictResolution(
        conflict: MemoryV2ConflictRow,
        onResolved: () -> Unit,
    ) {
        lateinit var dialog: AlertDialog
        var a0Confirmed = false
        fun resolve(decision: String) {
            runCatching {
                admin().resolveConflict(
                    conflict.scopeId,
                    conflict.conflictId,
                    decision,
                    a0Confirmed = a0Confirmed,
                )
            }.onSuccess {
                ProbeStatus.write(
                    activity,
                    if (
                        conflict.isA0Proposal &&
                        decision == MemoryV2ConflictService.ACCEPT_CANDIDATE
                    ) {
                        "A0 核心事实提案已批准。"
                    } else {
                        "记忆变更审核已处理。"
                    },
                )
                dialog.dismiss()
                onResolved()
            }.onFailure {
                ProbeStatus.write(
                    activity,
                    "记忆变更审核失败：${it.message}",
                )
            }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val evidence = if (conflict.evidenceQuotes.isEmpty()) {
            "无证据引文"
        } else {
            conflict.evidenceQuotes.take(3).joinToString("\n") { "· $it" }
        }
        content.addView(
            AppUi.textView(
                activity,
                if (conflict.isA0Proposal) {
                    "这是 LLM 提出的 A0 核心事实变更，不会自动生效。\n\n" +
                        "当前 ${conflict.existingTier} · " +
                        "${conflict.existingPredicate}\n" +
                        conflict.existingText.trim() + "\n\n" +
                        "提议 ${conflict.candidateTier} · " +
                        "${conflict.candidatePredicate}\n" +
                        conflict.candidateText.trim() + "\n\n" +
                        "候选值：${conflict.candidateValue}\n" +
                        "依据：${conflict.candidateEpistemic} · " +
                        "${conflict.evidenceCount} 条证据\n\n" +
                        evidence
                } else {
                    "现有：${conflict.existingText.trim()}\n\n" +
                        "候选：${conflict.candidateText.trim()}\n\n" +
                        "候选值：${conflict.candidateValue}"
                },
                14f,
                AppUi.textSecondary,
            ).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            MainUiComponents.fullWidth(),
        )
        lateinit var acceptRow: MainUiComponents.ActionRow
        if (conflict.isA0Proposal) {
            content.addView(
                AppUi.textView(
                    activity,
                    "A0 会进入所有后续回复的核心上下文。请核对证据与措辞；" +
                        "批准后旧版本会进入历史。",
                    13f,
                    AppUi.paused,
                ).apply {
                    setPadding(0, dp(12), 0, dp(4))
                    setLineSpacing(dp(3).toFloat(), 1f)
                },
                MainUiComponents.fullWidth(),
            )
            content.addView(
                CheckBox(activity).apply {
                    text = activity.getString(
                        R.string.memory_a0_review_confirm,
                    )
                    setTextColor(AppUi.text)
                    buttonTintList =
                        android.content.res.ColorStateList.valueOf(AppUi.primary)
                    setOnCheckedChangeListener { _, checked ->
                        a0Confirmed = checked
                        acceptRow.view.isEnabled = checked
                        acceptRow.view.alpha = if (checked) 1f else 0.45f
                    }
                },
                MainUiComponents.fullWidth(),
            )
        }
        acceptRow = MainUiComponents.actionRow(
            activity,
            if (conflict.isA0Proposal) {
                R.drawable.ic_shield_check
            } else {
                R.drawable.ic_refresh
            },
            if (conflict.isA0Proposal) "批准 A0 提案" else "采用候选说法",
            if (conflict.isA0Proposal) {
                "显式确认后写入，并保留旧版本历史"
            } else {
                "版本一致时更新，旧内容写入历史"
            },
            onClick = {
                resolve(MemoryV2ConflictService.ACCEPT_CANDIDATE)
            },
        )
        acceptRow.view.isEnabled = !conflict.isA0Proposal
        acceptRow.view.alpha = if (conflict.isA0Proposal) 0.45f else 1f
        content.addView(
            MainUiComponents.groupViews(
                activity,
                listOf(
                    MainUiComponents.actionRow(
                        activity,
                        R.drawable.ic_shield_check,
                        "保留现有事实",
                        if (conflict.isA0Proposal) {
                            "拒绝候选，不修改 A0 核心事实"
                        } else {
                            "只关闭冲突，不修改长期记忆"
                        },
                        onClick = {
                            resolve(MemoryV2ConflictService.KEEP_EXISTING)
                        },
                    ).view,
                    acceptRow.view,
                    MainUiComponents.actionRow(
                        activity,
                        R.drawable.ic_bubble_close,
                        if (conflict.isA0Proposal) {
                            "忽略 A0 提案"
                        } else {
                            "忽略这次冲突"
                        },
                        if (conflict.isA0Proposal) {
                            "标记为已忽略，不修改 A0 核心事实"
                        } else {
                            "标记为已忽略，不修改长期记忆"
                        },
                        onClick = {
                            resolve(MemoryV2ConflictService.DISMISS)
                        },
                    ).view,
                ),
            ),
            MainUiComponents.fullWidth().apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            },
        )
        dialog = AlertDialog.Builder(activity)
            .setTitle(
                if (conflict.isA0Proposal) {
                    "审核 A0 核心事实提案"
                } else {
                    "审核记忆变更"
                },
            )
            .setView(content)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun memoryRow(
        contact: MemoryV2ContactOverview,
        memory: MemoryV2MemoryRow,
        manual: MemoryV2ManualMemoryService,
        onChanged: () -> Unit,
    ): View {
        val label = buildString {
            append(memory.tier)
            if (memory.commitmentState.isNotBlank()) {
                append(" · ")
                append(memory.commitmentState)
            }
            append(" · ")
            append(formatDateTime(memory.updatedAt * 1_000L))
            memory.expiresAt?.let {
                append(" · 有效至 ")
                append(formatDateTime(it * 1_000L))
            }
        }
        return AppUi.textView(
            activity,
            "${memory.text.trim()}\n$label",
            14f,
            AppUi.text,
        ).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setLineSpacing(dp(3).toFloat(), 1f)
            contentDescription = "编辑或删除这条${memory.tier}记忆"
            foreground = AppUi.rowRipple(activity)
            setOnClickListener {
                showMemoryActions(contact, memory, manual, onChanged)
            }
        }
    }

    private fun promptAddMemory(
        contact: MemoryV2ContactOverview,
        manual: MemoryV2ManualMemoryService,
        onChanged: () -> Unit,
    ) {
        if (!requireAgentStopped()) {
            return
        }
        showManualRiskWarning(
            "新增记忆",
        ) {
            val tiers = listOf("A0", "A1", "A2")
            val labels = arrayOf(
                "A0 · 核心事实（身份、关系，高影响）",
                "A1 · 长期事实（偏好、稳定信息）",
                "A2 · 近期事件（默认按月过期）",
            )
            MainUiComponents.dialogBuilder(activity)
                .setTitle("选择记忆层级")
                .setSingleChoiceItems(labels, 1) { tierDialog, which ->
                    tierDialog.dismiss()
                    showManualTextEditor(
                        title = "新增 ${tiers[which]} 记忆",
                        initial = "",
                        confirmingLabel = "保存",
                    ) { text ->
                        val result = manual.add(contact.scopeId, tiers[which], text)
                        if (result.status != "saved") {
                            error("新增失败：${result.status}")
                        }
                        ProbeStatus.write(
                            activity,
                            "已新增 ${tiers[which]} 记忆。",
                        )
                        onChanged()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showMemoryActions(
        contact: MemoryV2ContactOverview,
        memory: MemoryV2MemoryRow,
        manual: MemoryV2ManualMemoryService,
        onChanged: () -> Unit,
    ) {
        if (!requireAgentStopped()) {
            return
        }
        lateinit var dialog: AlertDialog
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                AppUi.textView(
                    activity,
                    memory.text,
                    14f,
                    AppUi.textSecondary,
                ).apply {
                    setTextIsSelectable(true)
                    setLineSpacing(dp(3).toFloat(), 1f)
                },
                MainUiComponents.fullWidth(),
            )
            addView(
                MainUiComponents.groupViews(
                    activity,
                    listOf(
                        MainUiComponents.actionRow(
                            activity,
                            R.drawable.ic_file_text,
                            "编辑文字",
                            "${memory.tier} · 版本 ${memory.version}",
                            onClick = {
                                dialog.dismiss()
                                showManualRiskWarning(
                                    if (memory.tier == "A0") {
                                        "编辑 A0 核心事实"
                                    } else {
                                        "编辑记忆"
                                    },
                                ) {
                                    showManualTextEditor(
                                        title = "编辑 ${memory.tier} 记忆",
                                        initial = memory.text,
                                        confirmingLabel = "保存",
                                    ) { text ->
                                        manual.editText(
                                            contact.scopeId,
                                            memory.memoryId,
                                            memory.version,
                                            text,
                                        )
                                        ProbeStatus.write(
                                            activity,
                                            "记忆文字已更新。",
                                        )
                                        onChanged()
                                    }
                                }
                            },
                        ).view,
                        MainUiComponents.actionRow(
                            activity,
                            R.drawable.ic_person,
                            "迁移到其他人格",
                            "仅同一联系人 · 源记忆保留 · 目标新建",
                            onClick = {
                                dialog.dismiss()
                                showMigrationTargets(
                                    contact,
                                    memory,
                                    onChanged,
                                )
                            },
                        ).view,
                        MainUiComponents.actionRow(
                            activity,
                            R.drawable.ic_clock,
                            "人格迁移记录",
                            "查看目标副本，可撤回已完成的迁移",
                            onClick = {
                                dialog.dismiss()
                                showMigrationHistory(
                                    contact,
                                    memory,
                                    onChanged,
                                )
                            },
                        ).view,
                        MainUiComponents.actionRow(
                            activity,
                            R.drawable.ic_trash,
                            "删除记忆",
                            "软撤回并保留历史与证据",
                            danger = true,
                            onClick = {
                                dialog.dismiss()
                                showManualRiskWarning(
                                    if (memory.tier == "A0") {
                                        "删除 A0 核心事实"
                                    } else {
                                        "删除记忆"
                                    },
                                ) {
                                    MainUiComponents.showDestructiveDialog(
                                        context = activity,
                                        title = "确认删除这条记忆？",
                                        message = "会自动撤回这条记录并保留历史快照。" +
                                            "如果内容仍被模型使用，后续回复可能变得混乱。",
                                        positiveLabel = "删除",
                                        onConfirm = {
                                            runCatching {
                                                manual.retract(
                                                    contact.scopeId,
                                                    memory.memoryId,
                                                    memory.version,
                                                )
                                            }.onSuccess {
                                                ProbeStatus.write(
                                                    activity,
                                                    "记忆已撤回并删除。",
                                                )
                                                onChanged()
                                            }.onFailure {
                                                ProbeStatus.write(
                                                    activity,
                                                    "删除记忆失败：${it.message}",
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        ).view,
                    ),
                ),
                MainUiComponents.fullWidth().apply {
                    topMargin = dp(14)
                    bottomMargin = dp(8)
                },
            )
        }
        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("${memory.tier} 记忆")
            .setView(content)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun showMigrationTargets(
        contact: MemoryV2ContactOverview,
        memory: MemoryV2MemoryRow,
        onChanged: () -> Unit,
    ) {
        if (!requireAgentStopped()) {
            return
        }
        val selections = PersonaSelectionService(activity)
        val current = runCatching {
            selections.selectedForContact(contact.contactId)
        }.getOrElse {
            ProbeStatus.write(activity, "当前人格读取失败：${it.message}")
            return
        }
        val targets = runCatching {
            selections.activePresets().filter { it.id != current.personaId }
        }.getOrElse {
            ProbeStatus.write(activity, "目标人格读取失败：${it.message}")
            return
        }
        if (targets.isEmpty()) {
            ProbeStatus.write(activity, "没有其他可用人格可作为迁移目标。")
            return
        }
        lateinit var dialog: AlertDialog
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                AppUi.textView(
                    activity,
                    "源人格 ${current.name} · ${memory.tier}\n" +
                        "源 scope ${contact.scopeId}\n\n" +
                        "迁移会保留源记忆，并在目标 scope 新建记录和证据。" +
                        "不会修改源 scope_id，也不会跨联系人。",
                    13f,
                    AppUi.textSecondary,
                ).apply {
                    setTextIsSelectable(true)
                    setLineSpacing(dp(3).toFloat(), 1f)
                },
                MainUiComponents.fullWidth(),
            )
            addView(
                MainUiComponents.groupViews(
                    activity,
                    targets.map { preset ->
                        MainUiComponents.actionRow(
                            activity,
                            if (preset.isDefault) {
                                R.drawable.ic_shield_check
                            } else {
                                R.drawable.ic_person
                            },
                            preset.name,
                            "目标人格 ${preset.id.takeLast(8)} · 检查 scope 与冲突",
                            onClick = {
                                dialog.dismiss()
                                previewMigration(
                                    contact = contact,
                                    memory = memory,
                                    targetPersonaId = preset.id,
                                    targetPersonaName = preset.name,
                                    onChanged = onChanged,
                                )
                            },
                        ).view
                    },
                ),
                MainUiComponents.fullWidth().apply {
                    topMargin = dp(14)
                    bottomMargin = dp(8)
                },
            )
        }
        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("选择目标人格")
            .setView(ScrollView(activity).apply {
                addView(content)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(460),
                )
            })
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun previewMigration(
        contact: MemoryV2ContactOverview,
        memory: MemoryV2MemoryRow,
        targetPersonaId: String,
        targetPersonaName: String,
        onChanged: () -> Unit,
    ) {
        val targetScope = runCatching {
            MemoryV2PersonaScopeService(activity).ensureUnboundScope(
                contactId = contact.contactId,
                personaId = targetPersonaId,
                displayName = contact.displayName.ifBlank { "未命名联系人" },
            )
        }.getOrElse {
            ProbeStatus.write(activity, "目标 scope 创建失败：${it.message}")
            return
        }
        val preview = runCatching {
            MemoryV2PersonaMigrationService(activity).preview(
                sourceScopeId = contact.scopeId,
                targetScopeId = targetScope.scopeId,
                memoryId = memory.memoryId,
            )
        }.getOrElse {
            ProbeStatus.write(activity, "迁移预览失败：${it.message}")
            return
        }
        showMigrationConfirmation(
            contact = contact,
            memory = memory,
            targetPersonaName = targetPersonaName,
            preview = preview,
            onChanged = onChanged,
        )
    }

    private fun showMigrationConfirmation(
        contact: MemoryV2ContactOverview,
        memory: MemoryV2MemoryRow,
        targetPersonaName: String,
        preview: MemoryV2MigrationPreview,
        onChanged: () -> Unit,
    ) {
        val relationLines = if (preview.relations.isEmpty()) {
            "关系影响：没有关联关系"
        } else {
            buildString {
                append("关系影响：可复制 ")
                append(preview.copyableRelationCount)
                append("，需省略 ")
                append(preview.omittedRelationCount)
                preview.relations.take(4).forEach { impact ->
                    append("\n· ")
                    append(impact.counterpartTier)
                    append(" ")
                    append(impact.direction)
                    append(" · ")
                    append(
                        if (impact.copyable) {
                            "对端已迁移，将复制关系"
                        } else {
                            "对端未迁移，关系不会复制"
                        },
                    )
                }
            }
        }
        val conflictLine = when {
            preview.blockCode == "TARGET_FACT_CONFLICT" ->
                "目标冲突：已有相同 fact_key 的不同记忆\n" +
                    preview.targetFactConflictText.orEmpty()
            preview.blockCode == "TARGET_MEMORY_CONFLICT" ->
                "目标冲突：deterministic 目标记忆内容不一致"
            preview.sourcePendingConflictCount > 0 ->
                "源冲突：${preview.sourcePendingConflictCount} 条待审核，需先处理"
            else -> "冲突检查：通过"
        }
        val body = buildString {
            append("源人格 scope：")
            append(preview.sourceScopeId)
            append("\n目标人格：")
            append(targetPersonaName)
            append("\n目标 scope：")
            append(preview.targetScopeId)
            append("\n目标 scope 当前记忆：")
            append(preview.targetActiveMemoryCount)
            append(" 条")
            append("\n证据：")
            append(preview.evidenceCount)
            append(" 条，将在目标 scope 重新绑定")
            append("\n目标 fact_key：")
            append(preview.targetFactKey.take(16))
            append("…\n")
            append(conflictLine)
            append("\n")
            append(relationLines)
            append("\n\n源记忆会保留，源 scope_id 不变。")
        }
        if (preview.blockCode != null) {
            MainUiComponents.dialogBuilder(activity)
                .setTitle("无法迁移这条记忆")
                .setMessage(body)
                .setPositiveButton("关闭", null)
                .show()
            return
        }
        val relationCheckbox = CheckBox(activity).apply {
            setText(R.string.migration_relation_omission)
            textSize = 14f
            setTextColor(AppUi.text)
            setLineSpacing(dp(3).toFloat(), 1f)
            isChecked = preview.omittedRelationCount == 0
            visibility = if (preview.omittedRelationCount > 0) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        val a0Checkbox = CheckBox(activity).apply {
            setText(R.string.migration_a0_high_risk)
            textSize = 14f
            setTextColor(AppUi.danger)
            setLineSpacing(dp(3).toFloat(), 1f)
            visibility = if (preview.requiresA0Confirmation) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                AppUi.textView(activity, body, 13f, AppUi.textSecondary).apply {
                    setTextIsSelectable(true)
                    setLineSpacing(dp(3).toFloat(), 1f)
                },
                MainUiComponents.fullWidth(),
            )
            addView(
                relationCheckbox,
                MainUiComponents.fullWidth().apply { topMargin = dp(10) },
            )
            addView(
                a0Checkbox,
                MainUiComponents.fullWidth().apply { topMargin = dp(6) },
            )
        }
        lateinit var dialog: AlertDialog
        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("确认逐条迁移")
            .setView(ScrollView(activity).apply {
                addView(content)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(540),
                )
            })
            .setPositiveButton("确认迁移", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun updateEnabled() {
                positive.isEnabled =
                    relationCheckbox.isChecked &&
                    (
                        !preview.requiresA0Confirmation ||
                            a0Checkbox.isChecked
                        )
            }
            relationCheckbox.setOnCheckedChangeListener { _, _ -> updateEnabled() }
            a0Checkbox.setOnCheckedChangeListener { _, _ -> updateEnabled() }
            updateEnabled()
            positive.setOnClickListener {
                runCatching {
                    MemoryV2PersonaMigrationService(activity).migrate(
                        sourceScopeId = contact.scopeId,
                        targetScopeId = preview.targetScopeId,
                        memoryId = memory.memoryId,
                        a0Confirmed = preview.requiresA0Confirmation &&
                            a0Checkbox.isChecked,
                        acceptRelationOmission =
                            preview.omittedRelationCount == 0 ||
                                relationCheckbox.isChecked,
                    )
                }.onSuccess { result ->
                    ProbeStatus.write(
                        activity,
                        "记忆迁移 ${result.status}：目标 ${result.targetMemoryId}，" +
                            "复制关系 ${result.copiedRelationCount} 条，" +
                            "省略 ${result.omittedRelationCount} 条。",
                    )
                    dialog.dismiss()
                    onChanged()
                }.onFailure {
                    ProbeStatus.write(activity, "记忆迁移失败：${it.message}")
                }
            }
        }
        dialog.show()
    }

    private fun showMigrationHistory(
        contact: MemoryV2ContactOverview,
        memory: MemoryV2MemoryRow,
        onChanged: () -> Unit,
    ) {
        if (!requireAgentStopped()) {
            return
        }
        val service = MemoryV2PersonaMigrationService(activity)
        val records = runCatching {
            service.migrations(contact.scopeId, memory.memoryId)
        }.getOrElse {
            ProbeStatus.write(activity, "迁移记录读取失败：${it.message}")
            return
        }
        if (records.isEmpty()) {
            MainUiComponents.dialogBuilder(activity)
                .setTitle("人格迁移记录")
                .setMessage("这条记忆还没有迁移记录。")
                .setPositiveButton("关闭", null)
                .show()
            return
        }
        val personaNames = runCatching {
            PersonaSelectionService(activity)
                .activePresets()
                .associate { it.id to it.name }
        }.getOrDefault(emptyMap())
        lateinit var dialog: AlertDialog
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                AppUi.textView(
                    activity,
                    "源记忆始终保留。撤回只软撤回目标副本，" +
                        "目标证据、历史和迁移审计不会被物理删除。",
                    13f,
                    AppUi.textSecondary,
                ).apply {
                    setLineSpacing(dp(3).toFloat(), 1f)
                },
                MainUiComponents.fullWidth(),
            )
            addView(
                MainUiComponents.groupViews(
                    activity,
                    records.map { record ->
                        val targetName = personaNames[record.targetPersonaId]
                            ?: "人格 ${record.targetPersonaId.takeLast(8)}"
                        val detail = when (record.state) {
                            "committed" ->
                                "目标 $targetName · 已迁移 · 可撤回"
                            "rolled_back" ->
                                "目标 $targetName · 已撤回 · 源记忆保留"
                            else ->
                                "目标 $targetName · ${record.state}"
                        }
                        val row = MainUiComponents.actionRow(
                            activity,
                            R.drawable.ic_refresh,
                            "迁移到 $targetName",
                            detail,
                            danger = record.state == "committed",
                            onClick = {
                                dialog.dismiss()
                                confirmRollback(
                                    contact = contact,
                                    memory = memory,
                                    targetName = targetName,
                                    targetScopeId = record.targetScopeId,
                                    onChanged = onChanged,
                                )
                            },
                        )
                        if (record.state != "committed") {
                            row.view.isEnabled = false
                        }
                        row.view
                    },
                ),
                MainUiComponents.fullWidth().apply {
                    topMargin = dp(14)
                    bottomMargin = dp(8)
                },
            )
        }
        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("人格迁移记录")
            .setView(ScrollView(activity).apply {
                addView(content)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(480),
                )
            })
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
    }

    private fun confirmRollback(
        contact: MemoryV2ContactOverview,
        memory: MemoryV2MemoryRow,
        targetName: String,
        targetScopeId: String,
        onChanged: () -> Unit,
    ) {
        if (!requireAgentStopped()) {
            return
        }
        MainUiComponents.showDestructiveDialog(
            context = activity,
            title = "撤回迁移到 $targetName 的副本",
            message = "源记忆不会删除，源 scope 不变。" +
                "目标副本会以版本化方式软撤回，目标证据和历史仍保留，" +
                "迁移审计标记为 rolled_back。",
            positiveLabel = "撤回目标副本",
            onConfirm = {
                runCatching {
                    MemoryV2PersonaMigrationService(activity).rollback(
                        sourceScopeId = contact.scopeId,
                        targetScopeId = targetScopeId,
                        memoryId = memory.memoryId,
                        confirmed = true,
                    )
                }.onSuccess { result ->
                    ProbeStatus.write(
                        activity,
                        "迁移已撤回：目标版本 ${result.targetVersion}，" +
                            "源记忆保持不变。",
                    )
                    onChanged()
                }.onFailure {
                    ProbeStatus.write(activity, "撤回迁移失败：${it.message}")
                }
            },
        )
    }

    private fun confirmClearA2(
        contact: MemoryV2ContactOverview,
        manual: MemoryV2ManualMemoryService,
        onChanged: () -> Unit,
    ) {
        if (!requireAgentStopped()) {
            return
        }
        showManualRiskWarning(
            "清空 A2 近期事件",
        ) {
            MainUiComponents.showDestructiveDialog(
                context = activity,
                title = "清空全部 A2？",
                message = "会撤回该联系人的全部 A2 近期事件，" +
                    "保留 A0、A1、原始消息和证据历史。" +
                    "清空后模型会失去近期语境，回复可能暂时变得不连贯。",
                positiveLabel = "清空 A2",
                onConfirm = {
                    runCatching {
                        manual.clearTier(contact.scopeId, "A2")
                    }.onSuccess { result ->
                        ProbeStatus.write(
                            activity,
                            "已清空 ${result.removedMemoryIds.size} 条 A2 记忆。",
                        )
                        onChanged()
                    }.onFailure {
                        ProbeStatus.write(
                            activity,
                            "清空 A2 失败：${it.message}",
                        )
                    }
                },
            )
        }
    }

    private fun showManualRiskWarning(
        action: String,
        onContinue: () -> Unit,
    ) {
        MainUiComponents.dialogBuilder(activity)
            .setTitle("$action · 风险确认")
            .setMessage(
                "手动修改记忆会直接影响后续回复。" +
                    "错误、过时或互相矛盾的内容可能造成模型混乱。" +
                    "操作会记录版本与历史，但界面上的当前事实会立即改变。",
            )
            .setPositiveButton("我明白，继续") { _, _ -> onContinue() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showManualTextEditor(
        title: String,
        initial: String,
        confirmingLabel: String,
        onSave: (String) -> Unit,
    ) {
        val input = EditText(activity).apply {
            setText(initial)
            hint = "输入记忆内容，最多 2000 字节"
            textSize = 16f
            setTextColor(AppUi.text)
            setHintTextColor(AppUi.textMuted)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 10
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = activity.getDrawable(R.drawable.ui_input_background)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, MainUiComponents.fullWidth())
        }
        lateinit var dialog: AlertDialog
        dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(confirmingLabel, null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    ProbeStatus.write(activity, "记忆内容不能为空。")
                    return@setOnClickListener
                }
                runCatching {
                    onSave(text)
                }.onSuccess {
                    dialog.dismiss()
                }.onFailure {
                    ProbeStatus.write(
                        activity,
                        "记忆保存失败：${it.message}",
                    )
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteContact(contact: MemoryV2ContactOverview) {
        if (!requireAgentStopped()) {
            return
        }
        val name = contact.displayName.ifBlank { "未命名联系人" }
        MainUiComponents.showDestructiveDialog(
            context = activity,
            title = "删除 $name 的 Memory",
            message = "会删除这个联系人的 v2 消息、会话和长期记忆。",
            positiveLabel = "删除",
            onConfirm = {
                val deleted = admin().deleteScope(contact.scopeId)
                ProbeStatus.write(
                    activity,
                    if (deleted) {
                        "已删除 $name 的 Memory 2.0 数据。"
                    } else {
                        "没有找到 $name 的 Memory 2.0 scope。"
                    },
                )
                onRefreshRequested()
            },
        )
    }

    private fun confirmClearAll() {
        if (!requireAgentStopped()) {
            return
        }
        MainUiComponents.showDestructiveDialog(
            context = activity,
            title = "清空全部 Memory 2.0",
            message = "会删除所有联系人、原始消息、会话、长期记忆和待办。此操作不可恢复。",
            positiveLabel = "清空",
            onConfirm = {
                admin().clearAll()
                File(activity.filesDir, "memory").deleteRecursively()
                ProbeStatus.write(activity, "全部 Memory 2.0 数据已清空。")
                onRefreshRequested()
            },
        )
    }

    private fun requireAgentStopped(): Boolean {
        if (BotLoopService.runState == AgentRunState.OFF) {
            return true
        }
        ProbeStatus.write(activity, "请先停止代理，再修改记忆或切换人格。")
        return false
    }

    private fun admin(): MemoryV2AdminStore {
        return MemoryV2AdminStore(activity)
    }

    private fun metricValue(value: String): TextView {
        return AppUi.textView(activity, value, 20f, AppUi.text, medium = true).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun addMetricCell(
        container: LinearLayout,
        label: String,
        valueView: TextView,
        divider: Boolean,
    ) {
        if (divider) {
            container.addView(
                View(activity).apply { setBackgroundColor(AppUi.border) },
                LinearLayout.LayoutParams(1, dp(38)),
            )
        }
        val cell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        cell.addView(valueView, MainUiComponents.wrapContent())
        cell.addView(
            AppUi.textView(activity, label, 12f, AppUi.textSecondary).apply {
                gravity = Gravity.CENTER
            },
            MainUiComponents.wrapContent().apply { topMargin = dp(4) },
        )
        container.addView(
            cell,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun formatDateTime(timestamp: Long): String {
        val value = if (timestamp > 0L) timestamp else System.currentTimeMillis()
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(value))
    }

    private fun dp(value: Int): Int = AppUi.dp(activity, value)
}
