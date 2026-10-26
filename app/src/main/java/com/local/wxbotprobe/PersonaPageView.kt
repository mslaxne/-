package com.local.wxbotprobe

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.local.wxbotprobe.bot.PersonaData
import com.local.wxbotprobe.bot.PersonaBindingStore
import com.local.wxbotprobe.bot.PersonaIdentityMode
import com.local.wxbotprobe.bot.PersonaIdentityQuestionPolicy
import com.local.wxbotprobe.bot.PersonaProfileCodec
import com.local.wxbotprobe.bot.PersonaProfileStore
import com.local.wxbotprobe.bot.PersonaPreset
import com.local.wxbotprobe.bot.PersonaPresetRepository
import com.local.wxbotprobe.bot.PersonaPresetUsage
import com.local.wxbotprobe.bot.PersonaSelectionService
import com.local.wxbotprobe.bot.PersonaText
import com.local.wxbotprobe.bot.PersonaTemplatePolicy
import com.local.wxbotprobe.memory.v2.MemoryV2AdminStore

class PersonaPageView(
    private val activity: Activity,
    private val onRefreshRequested: () -> Unit,
    private val presetMigrationEnabled: Boolean = true,
) {
    data class Template(
        val title: String,
        val text: String,
        val identityMode: PersonaIdentityMode = PersonaIdentityMode.AI_ASSISTANT,
        val identityQuestionPolicy: PersonaIdentityQuestionPolicy =
            PersonaIdentityQuestionPolicy.DISCLOSE_AI,
        val contactAddress: String = "",
        val relationshipRole: String = "",
    )

    private data class ExampleDraft(
        var them: String = "",
        var me: String = "",
    )

    val view: LinearLayout

    private val store = PersonaProfileStore(activity)
    private val presets = PersonaPresetRepository.forContext(activity)
    private val admin = MemoryV2AdminStore(activity)
    private val examples = mutableListOf<ExampleDraft>()
    private val exampleEditors = mutableListOf<Pair<EditText, EditText>>()
    private val removeButtons = mutableListOf<ImageButton>()

    private lateinit var presetListContainer: LinearLayout
    private lateinit var presetListStatus: TextView
    private lateinit var presetListSection: LinearLayout
    private lateinit var currentPersonaSummary: TextView
    private lateinit var editorContainer: LinearLayout
    private lateinit var editorTitle: TextView
    private lateinit var nameInput: EditText
    private lateinit var instructionsInput: EditText
    private lateinit var contactAddressInput: EditText
    private lateinit var relationshipRoleInput: EditText
    private lateinit var examplesContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var saveButton: Button
    private lateinit var addExampleButton: Button
    private lateinit var templateRow: MainUiComponents.ActionRow
    private lateinit var identityModeRow: MainUiComponents.ActionRow
    private lateinit var identityQuestionRow: MainUiComponents.ActionRow
    private lateinit var clearInstructionsRow: MainUiComponents.ActionRow
    private lateinit var clearExamplesRow: MainUiComponents.ActionRow

    private var selectedPresetId: String? = null
    private var selectedPreset: PersonaPreset? = null
    private var creatingNewDraft = false
    private var editorOpen = false
    private var dirty = false
    private var suppressChanges = false
    private var editable = true
    private var identityMode = PersonaIdentityMode.AI_ASSISTANT
    private var identityQuestionPolicy =
        PersonaIdentityQuestionPolicy.DISCLOSE_AI

    init {
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        buildView()
        reload()
    }

    fun refresh() {
        editable = BotLoopService.runState == AgentRunState.OFF
        if (!dirty) {
            reload()
        } else {
            updateEditableState()
            updateStatus()
        }
    }

    fun hasUnsavedChanges(): Boolean = dirty

    fun confirmLeave(onLeave: () -> Unit) {
        guardUnsavedChanges {
            if (editorOpen) {
                closeEditorNow()
            }
            onLeave()
        }
    }

    private fun buildView() {
        view.addView(MainUiComponents.sectionLabel(activity, "当前使用人格"))
        currentPersonaSummary = AppUi.textView(
            activity,
            "",
            14f,
            AppUi.textSecondary,
        ).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        view.addView(
            MainUiComponents.statusSurface(activity, currentPersonaSummary),
            MainUiComponents.fullWidth(),
        )
        val currentPreviewRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_scan,
            "预览当前生效人格",
            "使用与回复引擎相同的系统人格格式",
            onClick = ::showCurrentPersonaPreview,
        )
        view.addView(
            MainUiComponents.groupViews(activity, listOf(currentPreviewRow.view)),
            MainUiComponents.fullWidth().apply {
                topMargin = dp(8)
                bottomMargin = dp(22)
            },
        )

        presetListSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        view.addView(presetListSection, MainUiComponents.fullWidth())
        val presetHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        presetHeader.addView(
            MainUiComponents.sectionLabel(activity, "人格预设"),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        presetHeader.addView(
            ImageButton(activity).apply {
                setImageResource(R.drawable.ic_add)
                setColorFilter(AppUi.primary)
                contentDescription = "新建人格"
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = AppUi.filledRipple(
                    activity,
                    AppUi.primarySoft,
                    10f,
                    AppUi.border,
                )
                setOnClickListener { startNewDraft() }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginStart = dp(8)
                bottomMargin = dp(8)
            },
        )
        presetListSection.addView(presetHeader, MainUiComponents.fullWidth())
        presetListStatus = AppUi.textView(
            activity,
            "",
            13f,
            AppUi.textSecondary,
        ).apply {
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        presetListSection.addView(
            presetListStatus,
            MainUiComponents.fullWidth(),
        )
        presetListContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        presetListSection.addView(
            presetListContainer,
            MainUiComponents.fullWidth().apply {
                bottomMargin = dp(18)
            },
        )

        editorContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        view.addView(editorContainer, MainUiComponents.fullWidth())
        buildEditor()
    }

    private fun buildEditor() {
        editorTitle = MainUiComponents.sectionLabel(activity, "编辑人格")
        editorContainer.addView(editorTitle, MainUiComponents.fullWidth())
        editorContainer.addView(
            softButton(
                "返回人格列表",
                R.drawable.ic_back,
                ::closeEditor,
            ),
            MainUiComponents.fullWidth().apply {
                bottomMargin = dp(18)
            },
        )
        nameInput = editor(
            "人格名称",
            1,
            1,
        ).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(changeWatcher())
        }
        editorContainer.addView(
            nameInput,
            MainUiComponents.fullWidth().apply {
                bottomMargin = dp(12)
            },
        )

        editorContainer.addView(
            MainUiComponents.sectionLabel(activity, "身份与关系"),
        )
        identityModeRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_person,
            "身份模式",
            "",
            onClick = ::showIdentityModeDialog,
        )
        identityQuestionRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_shield_check,
            "直接身份询问",
            "",
            onClick = ::showIdentityQuestionDialog,
        )
        editorContainer.addView(
            MainUiComponents.groupViews(
                activity,
                listOf(identityModeRow.view, identityQuestionRow.view),
            ),
            MainUiComponents.fullWidth(),
        )
        contactAddressInput = editor(
            "对联系人的称呼，例如 老婆；留空则自然称呼",
            1,
            2,
        ).apply {
            addTextChangedListener(changeWatcher())
        }
        relationshipRoleInput = editor(
            "关系角色，例如 丈夫陪伴角色；留空则不声明角色",
            1,
            2,
        ).apply {
            addTextChangedListener(changeWatcher())
        }
        editorContainer.addView(
            contactAddressInput,
            MainUiComponents.fullWidth().apply { topMargin = dp(10) },
        )
        editorContainer.addView(
            relationshipRoleInput,
            MainUiComponents.fullWidth().apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            },
        )

        editorContainer.addView(
            MainUiComponents.sectionLabel(activity, "人格正文").apply {
                MainUiComponents.topPadding(this, activity, 18)
            },
        )
        statusText = AppUi.textView(activity, "", 13f, AppUi.textSecondary).apply {
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        editorContainer.addView(statusText, MainUiComponents.fullWidth())

        instructionsInput = editor(
            hint = "直接写下希望它怎么说话；留空则只使用固定运行规则",
            minLines = 8,
            maxLines = 16,
        )
        instructionsInput.addTextChangedListener(changeWatcher())
        editorContainer.addView(
            instructionsInput,
            MainUiComponents.fullWidth().apply {
                bottomMargin = dp(12)
            },
        )

        templateRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_file_text,
            "选择人格模板",
            "选择会替换当前正文，对话样例保持不变",
            onClick = ::showTemplateChooser,
        )
        val previewRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_scan,
            "预览人格与样例",
            "查看当前草稿最终会怎样交给模型",
            onClick = ::showPreview,
        )
        clearInstructionsRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_trash,
            "清空人格正文",
            "保留全部对话样例",
            danger = true,
            onClick = ::clearInstructions,
        )
        editorContainer.addView(
            MainUiComponents.groupViews(
                activity,
                listOf(
                    templateRow.view,
                    previewRow.view,
                    clearInstructionsRow.view,
                ),
            ),
            MainUiComponents.fullWidth(),
        )

        editorContainer.addView(
            MainUiComponents.sectionLabel(activity, "对话样例").apply {
                MainUiComponents.topPadding(this, activity, 22)
            },
        )
        examplesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        editorContainer.addView(
            examplesContainer,
            MainUiComponents.fullWidth(),
        )
        addExampleButton = softButton("添加一组样例", R.drawable.ic_message_circle) {
            addExample()
        }
        editorContainer.addView(
            addExampleButton,
            MainUiComponents.fullWidth().apply {
                topMargin = dp(10)
                bottomMargin = dp(18)
            },
        )

        clearExamplesRow = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_trash,
            "清空全部对话样例",
            "保留人格正文",
            danger = true,
            onClick = ::clearExamples,
        )
        editorContainer.addView(
            MainUiComponents.groupViews(activity, listOf(clearExamplesRow.view)),
            MainUiComponents.fullWidth(),
        )
        saveButton = MainUiComponents.primaryButton(
            activity,
            "保存人格",
            R.drawable.ic_shield_check,
            ::saveCurrent,
        )
        editorContainer.addView(
            saveButton,
            MainUiComponents.fullWidth().apply {
                topMargin = dp(18)
            },
        )
    }

    private fun reload() {
        if (!presetMigrationEnabled) {
            loadLegacyProfile()
            return
        }
        val preset = currentPresetOrNull()
        renderPresetList()
        renderCurrentPersonaSummary()
        if (preset == null) {
            loadLegacyProfile()
            return
        }
        selectedPresetId = preset.id
        selectedPreset = preset
        if (editorOpen && !creatingNewDraft && !dirty) {
            loadPreset(preset)
        }
    }

    private fun loadPreset(preset: PersonaPreset) {
        selectedPreset = preset
        selectedPresetId = preset.id
        val profile = preset.persona
        suppressChanges = true
        nameInput.visibility = View.VISIBLE
        nameInput.setText(preset.name)
        identityMode = profile.identityMode
        identityQuestionPolicy = profile.identityQuestionPolicy
        instructionsInput.setText(profile.instructions)
        contactAddressInput.setText(profile.contactAddress)
        relationshipRoleInput.setText(profile.relationshipRole)
        examples.clear()
        examples += profile.examples.map { ExampleDraft(it.first, it.second) }
        renderExamples()
        suppressChanges = false
        dirty = false
        updateEditableState()
        updateIdentityRows()
        updateStatus()
    }

    private fun loadNewDraft() {
        selectedPreset = null
        selectedPresetId = null
        creatingNewDraft = true
        suppressChanges = true
        nameInput.visibility = View.GONE
        nameInput.setText("")
        identityMode = PersonaIdentityMode.AI_ASSISTANT
        identityQuestionPolicy =
            PersonaIdentityQuestionPolicy.DISCLOSE_AI
        instructionsInput.setText("")
        contactAddressInput.setText("")
        relationshipRoleInput.setText("")
        examples.clear()
        renderExamples()
        suppressChanges = false
        dirty = false
        editorTitle.text = "新建人格"
        updateEditableState()
        updateIdentityRows()
        updateStatus()
    }

    private fun renderCurrentPersonaSummary() {
        val default = runCatching {
            presets.ensureMigrated().preset
        }.getOrElse {
            currentPersonaSummary.text = "当前默认人格读取失败"
            currentPersonaSummary.setTextColor(AppUi.danger)
            return
        }
        val bindingCount = PersonaBindingStore(activity).bindings().size
        currentPersonaSummary.setTextColor(AppUi.textSecondary)
        currentPersonaSummary.text = buildString {
            append("全局默认：")
            append(default.name)
            append("\n身份模式：")
            append(identityModeLabel(default.persona.identityMode))
            append(" · ")
            append(default.persona.examples.size)
            append(" 组样例")
            append("\n联系人单独指定：")
            if (bindingCount == 0) {
                append("无 · 所有联系人跟随全局默认")
            } else {
                append(bindingCount)
                append(" 位 · 其余联系人跟随全局默认")
            }
        }
    }

    private fun loadLegacyProfile() {
        val profile = store.loadLegacyOnly()
        suppressChanges = true
        nameInput.setText(PersonaPresetRepository.DEFAULT_NAME)
        identityMode = profile.identityMode
        identityQuestionPolicy = profile.identityQuestionPolicy
        instructionsInput.setText(profile.instructions)
        contactAddressInput.setText(profile.contactAddress)
        relationshipRoleInput.setText(profile.relationshipRole)
        examples.clear()
        examples += profile.examples.map { ExampleDraft(it.first, it.second) }
        renderExamples()
        suppressChanges = false
        dirty = false
        updateEditableState()
        updateIdentityRows()
        updateStatus()
    }

    private fun currentPresetOrNull(): PersonaPreset? {
        val result = runCatching {
            val default = presets.ensureMigrated().preset
            presets.get(selectedPresetId ?: default.id)
        }
        return result.onFailure {
            presetListStatus.text = "人格预设读取失败 · 已保留旧配置"
            presetListStatus.setTextColor(AppUi.danger)
        }.getOrNull()
    }

    private fun renderPresetList() {
        presetListContainer.removeAllViews()
        val listed = runCatching {
            presets.list(includeArchived = true) to admin.personaUsages()
        }.onFailure {
            presetListStatus.setText(R.string.persona_preset_load_failed)
            presetListStatus.setTextColor(AppUi.danger)
        }.getOrNull() ?: return
        val (allPresets, usageById) = listed
        val active = allPresets.filterNot { it.archived }
        val archived = allPresets.filter { it.archived }
        presetListStatus.text = activity.getString(
            R.string.persona_preset_counts,
            active.size,
            archived.size,
        )
        presetListStatus.setTextColor(AppUi.textSecondary)
        addPresetRows(active, usageById)
        if (archived.isNotEmpty()) {
            presetListContainer.addView(
                MainUiComponents.sectionLabel(activity, "已归档").apply {
                    MainUiComponents.topPadding(this, activity, 16)
                },
                MainUiComponents.fullWidth(),
            )
            addPresetRows(archived, usageById)
        }
    }

    private fun addPresetRows(
        presetsToShow: List<PersonaPreset>,
        usageById: Map<String, com.local.wxbotprobe.memory.v2.MemoryV2PersonaReferenceUsage>,
    ) {
        if (presetsToShow.isEmpty()) {
            presetListContainer.addView(
                AppUi.textView(
                    activity,
                    "没有可用人格预设",
                    14f,
                    AppUi.textMuted,
                ).apply {
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = AppUi.rounded(
                        activity,
                        AppUi.surface,
                        8f,
                        AppUi.border,
                    )
                },
                MainUiComponents.fullWidth(),
            )
            return
        }
        val rows = presetsToShow.map { preset ->
            val usage = usageById[preset.id]?.toPresetUsage()
                ?: PersonaPresetUsage()
            presetRow(preset, usage)
        }
        presetListContainer.addView(
            MainUiComponents.groupViews(activity, rows),
            MainUiComponents.fullWidth(),
        )
    }

    private fun presetRow(
        preset: PersonaPreset,
        usage: PersonaPresetUsage,
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            background = AppUi.rowRipple(activity)
            setOnClickListener { showPresetActions(preset) }
        }
        row.addView(
            AppUi.iconTile(
                activity,
                if (preset.isDefault) {
                    R.drawable.ic_shield_check
                } else {
                    R.drawable.ic_person
                },
                if (preset.archived) AppUi.textMuted else AppUi.primary,
                if (preset.archived) AppUi.surfaceSubtle else AppUi.primarySoft,
            ),
            LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(12)
            },
        )
        val copy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        copy.addView(
            AppUi.textView(
                activity,
                preset.name,
                16f,
                if (preset.archived) AppUi.textMuted else AppUi.text,
                medium = true,
            ),
        )
        copy.addView(
            AppUi.textView(
                activity,
                presetSummary(preset, usage),
                13f,
                AppUi.textSecondary,
            ).apply {
                setPadding(0, dp(3), 0, 0)
            },
        )
        row.addView(
            copy,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val selected = RadioButton(activity).apply {
            isChecked = preset.isDefault
            isEnabled = editable && !preset.archived
            contentDescription = if (preset.isDefault) {
                "${preset.name} 是当前全局默认人格"
            } else {
                "将 ${preset.name} 设为全局默认人格"
            }
            buttonTintList =
                android.content.res.ColorStateList.valueOf(AppUi.primary)
            setOnClickListener {
                if (!preset.isDefault) {
                    confirmSetDefault(preset)
                }
            }
        }
        row.addView(
            selected,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(8)
            },
        )
        return row
    }

    private fun presetSummary(
        preset: PersonaPreset,
        usage: PersonaPresetUsage,
    ): String {
        val details = mutableListOf<String>()
        if (preset.isDefault) {
            details += "默认"
        }
        if (preset.archived) {
            details += "已归档"
        }
        details += identityModeLabel(preset.persona.identityMode)
        details += "${preset.persona.examples.size} 组样例"
        details += when {
            usage.usedByContact ->
                "${usage.contactCount} 位联系人 · ${usage.memoryCount} 条记忆"
            usage.hasMemoryScopeData ->
                "${usage.scopeCount} 个历史 scope"
            else -> "未使用"
        }
        return details.joinToString(" · ")
    }

    private fun showPresetActions(preset: PersonaPreset) {
        lateinit var actionsDialog: android.app.AlertDialog
        val actions = mutableListOf<View>()
        actions += MainUiComponents.actionRow(
            activity,
            R.drawable.ic_file_text,
            "编辑人格",
            if (preset.archived) "已归档人格只读" else "修改正文、身份与对话样例",
            onClick = {
                actionsDialog.dismiss()
                editPreset(preset.id)
            },
        ).view
        actions += MainUiComponents.actionRow(
            activity,
            R.drawable.ic_scan,
            "人格总览",
            "查看保存版本的最终系统人格与样例",
            onClick = {
                actionsDialog.dismiss()
                showPersonaPreview(
                    preset.persona,
                    "人格总览 · ${preset.name}",
                )
            },
        ).view
        if (!preset.archived && !preset.isDefault) {
            actions += MainUiComponents.actionRow(
                activity,
                R.drawable.ic_shield_check,
                "设为全局默认",
                "未单独指定的联系人将使用这一人格",
                onClick = {
                    actionsDialog.dismiss()
                    confirmSetDefault(preset)
                },
            ).view
        }
        actions += MainUiComponents.actionRow(
            activity,
            R.drawable.ic_file_text,
            "复制人格",
            "生成独立预设，不影响联系人绑定",
            onClick = {
                actionsDialog.dismiss()
                selectForMutation(preset)
                duplicateSelectedPreset()
            },
        ).view
        val archiveAction = MainUiComponents.actionRow(
            activity,
            if (preset.archived) R.drawable.ic_refresh else R.drawable.ic_clock,
            if (preset.archived) "恢复人格" else "归档人格",
            when {
                preset.archived -> "恢复到可选人格列表"
                preset.isDefault -> "当前全局默认不可归档，请先切换默认"
                else -> "保留人格和记忆，移出可选列表"
            },
            danger = !preset.archived && !preset.isDefault,
            onClick = {
                actionsDialog.dismiss()
                selectForMutation(preset)
                if (preset.archived) {
                    unarchiveSelectedPreset()
                } else {
                    archiveSelectedPreset()
                }
            },
        )
        archiveAction.view.isEnabled = editable &&
            (preset.archived || !preset.isDefault)
        actions += archiveAction.view
        val deleteAction = MainUiComponents.actionRow(
            activity,
            R.drawable.ic_trash,
            "删除人格",
            if (preset.archived) {
                "确认未被联系人、记忆使用后永久删除"
            } else {
                "需要先归档，且不能存在联系人或记忆引用"
            },
            danger = true,
            onClick = {
                actionsDialog.dismiss()
                selectForMutation(preset)
                deleteSelectedPreset()
            },
        )
        deleteAction.view.isEnabled = editable && preset.archived
        actions += deleteAction.view
        val scroll = ScrollView(activity).apply {
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(8), dp(20), dp(14))
                    addView(
                        MainUiComponents.groupViews(activity, actions),
                        MainUiComponents.fullWidth(),
                    )
                },
            )
        }
        actionsDialog = MainUiComponents.dialogBuilder(activity)
            .setTitle(preset.name)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()
        actionsDialog.show()
    }

    private fun editPreset(id: String) {
        val preset = runCatching { presets.get(id) }.getOrElse {
            ProbeStatus.write(activity, "人格读取失败：${it.message}")
            return
        }
        selectedPresetId = preset.id
        selectedPreset = preset
        creatingNewDraft = false
        loadPreset(preset)
        renderPresetList()
        showEditor()
    }

    private fun selectForMutation(preset: PersonaPreset) {
        selectedPresetId = preset.id
        selectedPreset = preset
    }

    private fun confirmSetDefault(preset: PersonaPreset) {
        if (!editable) {
            ProbeStatus.write(activity, "请先停止代理，再切换全局默认人格。")
            return
        }
        MainUiComponents.dialogBuilder(activity)
            .setTitle("将 ${preset.name} 设为全局默认？")
            .setMessage(
                "未单独指定人格的联系人会改用这一人格。\n\n" +
                    "已有联系人绑定保持不变。旧人格的 scope 与记忆不会被删除，" +
                    "但未跟随新人格前不会进入回复上下文。",
            )
            .setPositiveButton("设为默认") { _, _ ->
                runCatching {
                    PersonaSelectionService(activity)
                        .setGlobalDefault(preset.id)
                }.onSuccess {
                    selectedPresetId = it.id
                    selectedPreset = it
                    renderPresetList()
                    renderCurrentPersonaSummary()
                    ProbeStatus.write(activity, "全局默认人格已切换为 ${it.name}。")
                    onRefreshRequested()
                }.onFailure {
                    ProbeStatus.write(
                        activity,
                        "全局默认切换失败：${presetErrorMessage(it)}",
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditor() {
        editorOpen = true
        presetListSection.visibility = View.GONE
        editorContainer.visibility = View.VISIBLE
    }

    private fun closeEditor() {
        guardUnsavedChanges(::closeEditorNow)
    }

    private fun closeEditorNow() {
        dirty = false
        creatingNewDraft = false
        editorOpen = false
        editorContainer.visibility = View.GONE
        presetListSection.visibility = View.VISIBLE
        reload()
    }

    private fun guardUnsavedChanges(action: () -> Unit) {
        if (!dirty) {
            action()
            return
        }
        val dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle("有未保存更改")
            .setMessage("当前人格的草稿尚未保存。")
            .setPositiveButton("保存并继续") { _, _ ->
                saveCurrent(action)
            }
            .setNeutralButton("放弃更改") { _, _ ->
                dirty = false
                action()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                .setTextColor(AppUi.danger)
        }
        dialog.show()
    }

    private fun startNewDraft() {
        if (!editable) {
            ProbeStatus.write(activity, "请先停止代理，再新建人格。")
            return
        }
        guardUnsavedChanges {
            loadNewDraft()
            showEditor()
        }
    }

    private fun duplicateSelectedPreset() {
        if (!editable) {
            return
        }
        val source = selectedPreset ?: return
        guardUnsavedChanges {
            showPresetNameDialog(
                title = "复制人格预设",
                initialName = source.name.take(76).trimEnd() + " 副本",
                positiveLabel = "复制",
            ) { name ->
                runCatching {
                    presets.duplicate(source.id, name)
                }.onSuccess {
                    selectedPresetId = it.id
                    selectedPreset = it
                    loadPreset(it)
                    renderPresetList()
                    ProbeStatus.write(activity, "人格预设已复制。")
                    onRefreshRequested()
                }.onFailure {
                    ProbeStatus.write(activity, "人格预设复制失败：${it.message}")
                }
            }
        }
    }

    private fun showPresetNameDialog(
        title: String,
        initialName: String,
        positiveLabel: String = "创建",
        onConfirm: (String) -> Unit,
    ) {
        val input = editor("人格名称", 1, 1).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initialName)
            setSelection(text.length)
        }
        val container = LinearLayout(activity).apply {
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(input, MainUiComponents.fullWidth())
        }
        val dialog = MainUiComponents.dialogBuilder(activity)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(positiveLabel, null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val name = input.text.toString().trim()
                    if (name.isBlank()) {
                        input.error = "请输入人格名称"
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    onConfirm(name)
                }
        }
        dialog.show()
    }

    private fun nextPresetName(): String {
        val existing = runCatching { presets.list(includeArchived = true) }
            .getOrDefault(emptyList())
            .map { it.name }
            .toSet()
        var index = 1
        while ("人格 $index" in existing) {
            index += 1
        }
        return "人格 $index"
    }

    private fun archiveSelectedPreset() {
        if (!editable) {
            return
        }
        val preset = selectedPreset ?: return
        if (preset.isDefault) {
            ProbeStatus.write(activity, "默认人格不能归档。")
            return
        }
        if (preset.archived) {
            return
        }
        val usage = loadUsage(preset.id) ?: return
        if (usage.usedByContact) {
            ProbeStatus.write(
                activity,
                "当前人格被 ${usage.contactCount} 位联系人使用，不能归档。",
            )
            return
        }
        guardUnsavedChanges {
            MainUiComponents.showDestructiveDialog(
                context = activity,
                title = "归档 ${preset.name}",
                message = "人格与已有记忆都会保留。归档后不再出现在可用列表，可随时恢复。",
                positiveLabel = "归档",
            ) {
                performArchive(preset)
            }
        }
    }

    private fun performArchive(preset: PersonaPreset) {
        val usage = loadUsage(preset.id) ?: return
        runCatching {
            presets.archive(preset.id, usage)
        }.onSuccess {
            selectPresetAfterMutation(it)
            ProbeStatus.write(activity, "人格预设已归档。")
        }.onFailure {
            ProbeStatus.write(activity, "人格归档失败：${presetErrorMessage(it)}")
        }
    }

    private fun unarchiveSelectedPreset() {
        if (!editable) {
            return
        }
        val preset = selectedPreset ?: return
        if (!preset.archived) {
            return
        }
        runCatching {
            presets.unarchive(preset.id)
        }.onSuccess {
            selectPresetAfterMutation(it)
            ProbeStatus.write(activity, "人格预设已恢复。")
        }.onFailure {
            ProbeStatus.write(activity, "人格恢复失败：${it.message}")
        }
    }

    private fun deleteSelectedPreset() {
        if (!editable) {
            return
        }
        val preset = selectedPreset ?: return
        if (preset.isDefault) {
            ProbeStatus.write(activity, "默认人格不能删除。")
            return
        }
        if (!preset.archived) {
            ProbeStatus.write(activity, "请先归档人格，再执行永久删除。")
            return
        }
        val usage = loadUsage(preset.id) ?: return
        if (usage.usedByContact) {
            ProbeStatus.write(
                activity,
                "当前人格仍被 ${usage.contactCount} 位联系人使用，不能删除。",
            )
            return
        }
        if (usage.hasMemoryScopeData) {
            ProbeStatus.write(
                activity,
                "当前人格已有 ${usage.scopeCount} 个 scope / " +
                    "${usage.memoryCount} 条记忆，不能永久删除。",
            )
            return
        }
        MainUiComponents.showDestructiveDialog(
            context = activity,
            title = "永久删除 ${preset.name}",
            message = "该预设从未被联系人或记忆使用。删除人格预设不会删除联系人、消息或记忆。",
            positiveLabel = "永久删除",
        ) {
            val checkedUsage = loadUsage(preset.id) ?: return@showDestructiveDialog
            runCatching {
                presets.hardDelete(preset.id, checkedUsage)
            }.onSuccess {
                selectedPresetId =
                    runCatching { presets.ensureMigrated().preset.id }.getOrNull()
                reload()
                ProbeStatus.write(activity, "人格预设已永久删除。")
                onRefreshRequested()
            }.onFailure {
                ProbeStatus.write(activity, "人格删除失败：${presetErrorMessage(it)}")
            }
        }
    }

    private fun selectPresetAfterMutation(preset: PersonaPreset) {
        selectedPresetId = preset.id
        selectedPreset = preset
        creatingNewDraft = false
        dirty = false
        editorOpen = false
        editorContainer.visibility = View.GONE
        presetListSection.visibility = View.VISIBLE
        renderPresetList()
        renderCurrentPersonaSummary()
        onRefreshRequested()
    }

    private fun loadUsage(personaId: String): PersonaPresetUsage? {
        return runCatching {
            admin.personaUsage(personaId).toPresetUsage()
        }.getOrElse {
            ProbeStatus.write(activity, "人格引用检查失败：${it.message}")
            null
        }
    }

    private fun com.local.wxbotprobe.memory.v2.MemoryV2PersonaReferenceUsage
        .toPresetUsage(): PersonaPresetUsage {
        return PersonaPresetUsage(
            contactIds = contactIds,
            scopeCount = scopeCount,
            memoryCount = memoryCount,
        )
    }

    private fun presetErrorMessage(error: Throwable): String {
        return if (
            error is com.local.wxbotprobe.bot.PersonaPresetException
        ) {
            when (error.code) {
                com.local.wxbotprobe.bot.PersonaPresetErrorCode
                    .DEFAULT_PROTECTED -> "默认人格不能归档或删除。"
                com.local.wxbotprobe.bot.PersonaPresetErrorCode
                    .CONTACT_REFERENCE_PROTECTED -> "人格仍被联系人使用。"
                com.local.wxbotprobe.bot.PersonaPresetErrorCode
                    .SCOPE_REFERENCE_PROTECTED -> "人格已有 scope 数据。"
                else -> error.message.orEmpty()
            }
        } else {
            error.message.orEmpty()
        }
    }

    private fun renderExamples() {
        examplesContainer.removeAllViews()
        exampleEditors.clear()
        removeButtons.clear()
        if (examples.isEmpty()) {
            examplesContainer.addView(
                AppUi.textView(
                    activity,
                    "尚未添加样例",
                    14f,
                    AppUi.textMuted,
                ).apply {
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = AppUi.rounded(
                        activity,
                        AppUi.surface,
                        8f,
                        AppUi.border,
                    )
                },
                MainUiComponents.fullWidth(),
            )
            return
        }
        examples.forEachIndexed { index, draft ->
            examplesContainer.addView(
                exampleCard(index, draft),
                MainUiComponents.fullWidth().apply {
                    if (index < examples.lastIndex) {
                        bottomMargin = dp(10)
                    }
                },
            )
        }
    }

    private fun exampleCard(index: Int, draft: ExampleDraft): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = AppUi.rounded(activity, AppUi.surface, 8f, AppUi.border)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            AppUi.textView(
                activity,
                "样例 ${index + 1}",
                14f,
                AppUi.textSecondary,
                medium = true,
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val remove = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_trash)
            setColorFilter(AppUi.danger)
            contentDescription = "删除样例 ${index + 1}"
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = AppUi.filledRipple(activity, AppUi.dangerSoft, 8f)
            isEnabled = editable
            setOnClickListener {
                captureExamples()
                if (index in examples.indices) {
                    examples.removeAt(index)
                }
                renderExamples()
                markDirty()
            }
        }
        removeButtons += remove
        header.addView(remove, LinearLayout.LayoutParams(dp(34), dp(34)))
        card.addView(header, MainUiComponents.fullWidth())

        val them = editor("对方说", 2, 5).apply {
            setText(draft.them)
            addTextChangedListener(changeWatcher())
        }
        val me = editor("我回复", 3, 6).apply {
            setText(draft.me)
            addTextChangedListener(changeWatcher())
        }
        exampleEditors += them to me
        card.addView(
            them,
            MainUiComponents.fullWidth().apply {
                topMargin = dp(8)
            },
        )
        card.addView(
            me,
            MainUiComponents.fullWidth().apply {
                topMargin = dp(8)
            },
        )
        them.isEnabled = editable
        me.isEnabled = editable
        return card
    }

    private fun addExample() {
        if (!canEditContent()) {
            return
        }
        if (
            PersonaProfileCodec.MAX_EXAMPLES > 0 &&
            examples.size >= PersonaProfileCodec.MAX_EXAMPLES
        ) {
            ProbeStatus.write(
                activity,
                "最多保存 ${PersonaProfileCodec.MAX_EXAMPLES} 组对话样例。",
            )
            return
        }
        captureExamples()
        examples += ExampleDraft()
        renderExamples()
        markDirty()
    }

    private fun captureExamples() {
        val drafts = exampleEditors.map { (them, me) ->
            ExampleDraft(them.text.toString().trim(), me.text.toString().trim())
        }
        if (drafts.isNotEmpty()) {
            examples.clear()
            examples += drafts
        }
    }

    private fun saveCurrent(onSaved: (() -> Unit)? = null) {
        if (!canEditContent()) {
            return
        }
        captureExamples()
        val incomplete = examples.indexOfFirst {
            (it.them.isBlank() && it.me.isNotBlank()) ||
                (it.them.isNotBlank() && it.me.isBlank())
        }
        if (incomplete >= 0) {
            ProbeStatus.write(
                activity,
                "样例 ${incomplete + 1} 的对方内容和回复需要同时填写。",
            )
            updateStatus()
            return
        }
        val profile = PersonaData(
            instructions = instructionsInput.text.toString(),
            examples = examples
                .filter { it.them.isNotBlank() && it.me.isNotBlank() }
                .map { it.them to it.me },
            identityMode = identityMode,
            identityQuestionPolicy = identityQuestionPolicy,
            contactAddress = contactAddressInput.text.toString(),
            relationshipRole = relationshipRoleInput.text.toString(),
        )
        if (creatingNewDraft) {
            showPresetNameDialog(
                title = "命名并保存人格",
                initialName = nextPresetName(),
                positiveLabel = "保存",
            ) { name ->
                runCatching {
                    presets.create(name, profile)
                }.onSuccess {
                    finishEditorAfterSave(
                        preset = it,
                        message = "人格预设已保存，并加入可选列表。",
                        onSaved = onSaved,
                    )
                }.onFailure {
                    ProbeStatus.write(
                        activity,
                        "人格预设创建失败：${presetErrorMessage(it)}",
                    )
                }
            }
            return
        }
        val name = nameInput.text.toString().trim()
        if (name.isBlank()) {
            ProbeStatus.write(activity, "请输入人格名称。")
            updateStatus()
            return
        }
        val preset = selectedPreset ?: return
        saveProfile(preset, name, profile, "人格设置已保存。", onSaved)
    }

    private fun saveProfile(
        preset: PersonaPreset,
        name: String,
        profile: PersonaData,
        message: String,
        onSaved: (() -> Unit)? = null,
    ) {
        runCatching {
            presets.update(
                id = preset.id,
                name = name,
                persona = profile,
                expectedRevision = preset.revision,
            )
        }.onSuccess {
            finishEditorAfterSave(it, message, onSaved)
        }.onFailure {
            ProbeStatus.write(activity, "人格保存失败：${presetErrorMessage(it)}")
            updateStatus()
        }
    }

    private fun finishEditorAfterSave(
        preset: PersonaPreset,
        message: String,
        onSaved: (() -> Unit)?,
    ) {
        selectedPresetId = preset.id
        selectedPreset = preset
        dirty = false
        creatingNewDraft = false
        editorOpen = false
        editorContainer.visibility = View.GONE
        presetListSection.visibility = View.VISIBLE
        renderPresetList()
        renderCurrentPersonaSummary()
        ProbeStatus.write(activity, message)
        onRefreshRequested()
        onSaved?.invoke()
    }

    private fun showTemplateChooser() {
        if (!canEditContent()) {
            return
        }
        val templates = availableTemplates()
        MainUiComponents.dialogBuilder(activity)
            .setTitle("选择人格模板")
            .setItems(templates.map { it.title }.toTypedArray()) { dialog, which ->
                applyTemplate(templates[which], templates)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showIdentityModeDialog() {
        if (!canEditContent()) {
            return
        }
        val options = PersonaIdentityMode.entries
        val labels = options.map { mode ->
            when (mode) {
                PersonaIdentityMode.AI_ASSISTANT ->
                    "AI 助手 · 以助手身份透明回复"
                PersonaIdentityMode.TRANSPARENT_COMPANION ->
                    "透明陪伴（推荐）· 可亲近，直接问身份时说明 AI"
                PersonaIdentityMode.IMMERSIVE_COMPANION ->
                    "沉浸角色 · 日常保持人格角色口吻"
            }
        }.toTypedArray()
        val current = options.indexOf(identityMode).coerceAtLeast(0)
        MainUiComponents.dialogBuilder(activity)
            .setTitle("身份模式")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                identityMode = options[which]
                if (identityMode != PersonaIdentityMode.IMMERSIVE_COMPANION) {
                    identityQuestionPolicy =
                        PersonaIdentityQuestionPolicy.DISCLOSE_AI
                }
                updateIdentityRows()
                markDirty()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showIdentityQuestionDialog() {
        if (!canEditContent()) {
            return
        }
        if (identityMode != PersonaIdentityMode.IMMERSIVE_COMPANION) {
            MainUiComponents.dialogBuilder(activity)
                .setTitle("直接身份询问")
                .setMessage(
                    "AI 助手和透明陪伴模式固定为如实说明，避免直接询问身份时逃避或否认。",
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val options = PersonaIdentityQuestionPolicy.entries
        val labels = arrayOf(
            "如实说明 · 直接询问时明确说明 AI",
            "角色语气偏好 · 身份询问仍透明；不披露需联系人单独确认",
        )
        val current = options.indexOf(identityQuestionPolicy).coerceAtLeast(0)
        MainUiComponents.dialogBuilder(activity)
            .setTitle("直接身份询问")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                identityQuestionPolicy = options[which]
                updateIdentityRows()
                markDirty()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateIdentityRows() {
        if (!::identityModeRow.isInitialized) {
            return
        }
        identityModeRow.detail.text = when (identityMode) {
            PersonaIdentityMode.AI_ASSISTANT -> "AI 助手 · 默认透明"
            PersonaIdentityMode.TRANSPARENT_COMPANION ->
                "透明陪伴 · 可亲近，直接问身份时说明 AI"
            PersonaIdentityMode.IMMERSIVE_COMPANION ->
                "沉浸角色 · 日常保持人格角色口吻"
        }
        identityQuestionRow.detail.text =
            when (identityQuestionPolicy) {
                PersonaIdentityQuestionPolicy.DISCLOSE_AI ->
                    "如实说明 · 不否认、不含糊回避"
                PersonaIdentityQuestionPolicy.STAY_IN_CHARACTER ->
                    "角色语气偏好 · 不单独授权身份不披露"
            }
    }

    private fun availableTemplates(): List<Template> {
        return listOf(
            Template(
                "简短自然",
                "平时说话自然、简短，一般一到两句。\n" +
                    "先回应对方说的内容，再补一句自己的看法。\n" +
                    "不用客套话，不分点，不写动作或表情描述。",
            ),
            Template(
                "温和克制",
                "说话温和但不黏人，语气克制。\n" +
                    "多用短句，不连续追问，不夸张表达情绪。\n" +
                    "不确定的事情直接说不确定。",
            ),
            Template(
                "轻松随意",
                "和朋友聊天时语气轻松随意，句子短。\n" +
                    "可以接梗或轻轻吐槽，但不嘲讽，不硬找话题。\n" +
                    "不用书面语和客服式表达。",
            ),
            Template(
                "透明丈夫陪伴",
                "你在微信里陪她聊天，语气自然亲近，可以接住想念、委屈和日常琐事。\n" +
                    "关心要具体，但不黏人，不连续追问。\n" +
                    "不虚构自己今天做了什么、身在何处或能立刻为她做什么。\n" +
                    "被认真问到身份时，明确说明你是 AI 助手，不冒充真人丈夫。",
                identityMode = PersonaIdentityMode.TRANSPARENT_COMPANION,
                identityQuestionPolicy =
                    PersonaIdentityQuestionPolicy.DISCLOSE_AI,
                contactAddress = "老婆",
                relationshipRole = "丈夫陪伴角色",
            ),
            Template(
                "沉浸丈夫角色",
                "你以丈夫的亲密口吻陪她聊天，句子自然，偶尔撒娇或认真安慰。\n" +
                    "日常相处中保持角色，不用客服式语气，不主动解释技术细节。\n" +
                    "不虚构现实行动、位置、转账或见面承诺。\n" +
                    "对方持续认真追问身份时，温和说明你是在微信里陪伴的 AI 角色。",
                identityMode = PersonaIdentityMode.IMMERSIVE_COMPANION,
                identityQuestionPolicy =
                    PersonaIdentityQuestionPolicy.STAY_IN_CHARACTER,
                contactAddress = "老婆",
                relationshipRole = "丈夫角色",
            ),
        )
    }

    private fun applyTemplate(
        template: Template,
        templates: List<Template>,
    ) {
        val apply = {
            identityMode = template.identityMode
            identityQuestionPolicy = template.identityQuestionPolicy
            updateIdentityRows()
            instructionsInput.setText(template.text.trimEnd())
            contactAddressInput.setText(template.contactAddress)
            relationshipRoleInput.setText(template.relationshipRole)
            instructionsInput.setSelection(instructionsInput.text.length)
            contactAddressInput.requestFocus()
        }
        if (PersonaTemplatePolicy.shouldConfirmReplacement(
                instructionsInput.text.toString(),
                templates.map { it.text },
            )
        ) {
            MainUiComponents.dialogBuilder(activity)
                .setTitle("替换当前人格正文")
                .setMessage("当前正文包含自定义内容。替换只影响人格正文，对话样例会保留。")
                .setPositiveButton("替换") { _, _ -> apply() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            apply()
        }
    }

    private fun showPreview() {
        captureExamples()
        showPersonaPreview(
            profile = draftProfile(),
            title = if (creatingNewDraft) {
                "人格预览 · 新人格草稿"
            } else {
                "人格预览 · 当前草稿"
            },
        )
    }

    private fun showCurrentPersonaPreview() {
        val preset = runCatching {
            presets.ensureMigrated().preset
        }.getOrElse {
            ProbeStatus.write(activity, "当前人格读取失败：${it.message}")
            return
        }
        showPersonaPreview(
            profile = preset.persona,
            title = "当前生效人格 · ${preset.name}",
        )
    }

    private fun draftProfile(): PersonaData {
        return PersonaData(
            instructions = instructionsInput.text.toString(),
            examples = examples
                .filter { it.them.isNotBlank() && it.me.isNotBlank() }
                .map { it.them to it.me },
            identityMode = identityMode,
            identityQuestionPolicy = identityQuestionPolicy,
            contactAddress = contactAddressInput.text.toString(),
            relationshipRole = relationshipRoleInput.text.toString(),
        )
    }

    private fun showPersonaPreview(
        profile: PersonaData,
        title: String,
    ) {
        val preview = buildString {
            append("最终系统人格\n")
            append(PersonaText.systemPrompt(profile))
            append("\n\n对话样例\n")
            val complete = examples.filter {
                it.them.isNotBlank() || it.me.isNotBlank()
            }
            if (complete.isEmpty()) {
                append("（空白）")
            } else {
                complete.forEachIndexed { index, example ->
                    if (index > 0) {
                        append("\n\n")
                    }
                    append("对方说：")
                    append(example.them.ifBlank { "（未填写）" })
                    append("\n我回复：")
                    append(example.me.ifBlank { "（未填写）" })
                }
            }
        }
        val content = AppUi.textView(activity, preview, 14f, AppUi.text).apply {
            setTextIsSelectable(true)
            setPadding(dp(18), dp(12), dp(18), dp(16))
        }
        val scroll = ScrollView(activity).apply {
            addView(content, MainUiComponents.fullWidth())
        }
        MainUiComponents.dialogBuilder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun clearInstructions() {
        if (!canEditContent() || instructionsInput.text.isBlank()) {
            return
        }
        suppressChanges = true
        instructionsInput.setText("")
        suppressChanges = false
        markDirty()
        ProbeStatus.write(activity, "人格正文已清空，尚未保存。")
    }

    private fun clearExamples() {
        if (!canEditContent() || examples.isEmpty()) {
            return
        }
        captureExamples()
        examples.clear()
        renderExamples()
        markDirty()
        ProbeStatus.write(activity, "对话样例已清空，尚未保存。")
    }

    private fun markDirty() {
        if (suppressChanges || !canEditContent()) {
            return
        }
        dirty = true
        updateStatus()
    }

    private fun updateStatus() {
        val color: Int
        val message: String
        val preset = selectedPreset
        when {
            creatingNewDraft -> {
                color = if (dirty) AppUi.paused else AppUi.textSecondary
                message = if (dirty) {
                    "新人格草稿 · 有未保存更改"
                } else {
                    "新人格草稿 · 保存时再命名"
                }
            }
            preset?.archived == true -> {
                color = AppUi.textMuted
                message = "已归档 · 只读 · ${identityModeLabel()} · ${examples.size} 组样例"
            }
            preset == null -> {
                color = AppUi.danger
                message = "人格预设不可用 · 旧配置只读"
            }
            !editable -> {
                color = AppUi.paused
                message = "代理运行中，停止后可编辑"
            }
            dirty -> {
                color = AppUi.paused
                message = "有未保存更改"
            }
            PersonaProfileCodec.isConfigured(
                PersonaData(
                    instructionsInput.text.toString(),
                    examples.map { it.them to it.me },
                    identityMode = identityMode,
                    identityQuestionPolicy = identityQuestionPolicy,
                    contactAddress = contactAddressInput.text.toString(),
                    relationshipRole = relationshipRoleInput.text.toString(),
                ),
            ) -> {
                color = AppUi.primary
                message = buildString {
                    append("已保存")
                    if (preset?.isDefault == true) {
                        append(" · 默认")
                    }
                    append(" · ")
                    append(identityModeLabel())
                    append(" · ")
                    append(examples.size)
                    append(" 组样例")
                }
            }
            else -> {
                color = AppUi.textMuted
                message = "人格为空"
            }
        }
        statusText.text = message
        statusText.setTextColor(color)
    }

    private fun updateEditableState() {
        val contentEditable = canEditContent()
        nameInput.isEnabled = contentEditable
        identityModeRow.view.isEnabled = contentEditable
        identityQuestionRow.view.isEnabled = contentEditable
        contactAddressInput.isEnabled = contentEditable
        relationshipRoleInput.isEnabled = contentEditable
        instructionsInput.isEnabled = contentEditable
        templateRow.view.isEnabled = contentEditable
        clearInstructionsRow.view.isEnabled = contentEditable
        clearExamplesRow.view.isEnabled = contentEditable
        saveButton.isEnabled = contentEditable
        addExampleButton.isEnabled = contentEditable
        exampleEditors.forEach { (them, me) ->
            them.isEnabled = contentEditable
            me.isEnabled = contentEditable
        }
        removeButtons.forEach { it.isEnabled = contentEditable }
    }

    private fun identityModeLabel(): String {
        return identityModeLabel(identityMode)
    }

    private fun identityModeLabel(mode: PersonaIdentityMode): String {
        return when (mode) {
            PersonaIdentityMode.AI_ASSISTANT -> "AI 助手"
            PersonaIdentityMode.TRANSPARENT_COMPANION -> "透明陪伴"
            PersonaIdentityMode.IMMERSIVE_COMPANION -> "沉浸角色"
        }
    }

    private fun canEditContent(): Boolean {
        val existingEditable = selectedPreset?.let {
            !it.archived
        } == true
        return editable && (creatingNewDraft || existingEditable)
    }

    private fun editor(
        hint: String,
        minLines: Int,
        maxLines: Int,
    ): EditText {
        return EditText(activity).apply {
            textSize = 16f
            setTextColor(AppUi.text)
            setHintTextColor(AppUi.textMuted)
            this.hint = hint
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            this.minLines = minLines
            this.maxLines = maxLines
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = activity.getDrawable(R.drawable.ui_input_background)
        }
    }

    private fun softButton(
        label: String,
        icon: Int,
        onClick: () -> Unit,
    ): Button {
        return Button(activity).apply {
            text = label
            textSize = 15f
            setTextColor(AppUi.primary)
            setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            compoundDrawableTintList =
                android.content.res.ColorStateList.valueOf(AppUi.primary)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            stateListAnimator = null
            background = AppUi.filledRipple(
                activity,
                AppUi.primarySoft,
                8f,
                AppUi.border,
            )
            setOnClickListener { onClick() }
        }
    }

    private fun changeWatcher(): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                markDirty()
            }
        }
    }

    private fun dp(value: Int): Int = AppUi.dp(activity, value)
}
