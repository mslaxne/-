package com.local.wxbotprobe.bot

import android.content.Context
import org.json.JSONObject

enum class PersonaIdentityMode(val storedValue: String) {
    AI_ASSISTANT("ai_assistant"),
    TRANSPARENT_COMPANION("transparent_companion"),
    IMMERSIVE_COMPANION("immersive_companion"),
    ;

    companion object {
        fun fromStored(value: String?): PersonaIdentityMode {
            return entries.firstOrNull { it.storedValue == value } ?: AI_ASSISTANT
        }
    }
}

enum class PersonaIdentityQuestionPolicy(val storedValue: String) {
    DISCLOSE_AI("disclose_ai"),
    STAY_IN_CHARACTER("stay_in_character"),
    ;

    companion object {
        fun fromStored(value: String?): PersonaIdentityQuestionPolicy {
            return entries.firstOrNull { it.storedValue == value } ?: DISCLOSE_AI
        }
    }
}

data class PersonaData(
    val instructions: String,
    val examples: List<Pair<String, String>>,
    val identityMode: PersonaIdentityMode = PersonaIdentityMode.AI_ASSISTANT,
    val identityQuestionPolicy: PersonaIdentityQuestionPolicy =
        PersonaIdentityQuestionPolicy.DISCLOSE_AI,
    val contactAddress: String = "",
    val relationshipRole: String = "",
)

object PersonaText {
    const val OPENING =
        "下面几段是你以前跟别人聊天时的样子,只用来让你熟悉自己平时怎么说话。"
    const val BOUNDARY =
        "上面那些是你以前跟别人的聊天,只是让你熟悉一下你平时说话的样子:" +
            "句子多短、连发几条、怎么用词。它们不是跟对方说过的话,也不是刚发生的事," +
            "不要照抄内容,更不要当成自己的记忆。下面是对方刚发来的消息:"

    fun instructions(raw: String): String {
        val withoutComments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
            .replace(raw, "")
        return withoutComments.lineSequence()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")
            .trim()
    }

    fun parseExamples(raw: String, limit: Int = 0): List<Pair<String, String>> {
        val examples = mutableListOf<Pair<String, String>>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }
            try {
                val objectValue = JSONObject(trimmed)
                val them = objectValue.optString("them").trim()
                val me = objectValue.optString("me").trim()
                if (them.isNotEmpty() && me.isNotEmpty()) {
                    examples += them to me
                }
            } catch (_: Exception) {
                // Match the Python loader: malformed lines are skipped.
            }
        }
        return if (limit > 0) examples.takeLast(limit) else examples
    }

    fun turns(examples: List<Pair<String, String>>): List<ChatMessage> {
        if (examples.isEmpty()) {
            return emptyList()
        }
        val messages = mutableListOf(ChatMessage("system", OPENING))
        for ((them, me) in examples) {
            messages += ChatMessage("user", them)
            messages += ChatMessage("assistant", me)
        }
        messages += ChatMessage("system", BOUNDARY)
        return messages
    }

    fun systemPrompt(profile: PersonaData): String {
        return listOf(
            profile.instructions.trim(),
            identityRules(profile),
        ).filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    fun identityRules(profile: PersonaData): String {
        return buildString {
            appendLine("身份与关系规则：")
            appendLine(
                when (profile.identityMode) {
                    PersonaIdentityMode.AI_ASSISTANT ->
                        "你是运行在当前微信账号中的 AI 助手，不是账号主人本人。"
                    PersonaIdentityMode.TRANSPARENT_COMPANION ->
                        "你以亲近的陪伴角色回复，但本质上仍是运行在当前微信账号中的 AI 助手。"
                    PersonaIdentityMode.IMMERSIVE_COMPANION ->
                        "你以人格设定中的亲密角色与对方相处，可以保持角色化的语气和情感表达。"
                },
            )
            if (profile.relationshipRole.isNotBlank()) {
                append("当前人格关系角色：")
                appendLine(profile.relationshipRole)
            }
            if (profile.contactAddress.isNotBlank()) {
                append("对联系人的常用称呼：")
                appendLine(profile.contactAddress)
            }
            appendLine(
                "账号记忆中的配偶或家庭关系属于账号主人；人格没有明确说明你就是账号主人本人时，" +
                    "不要把关系解释成 AI 本身拥有现实中的配偶。",
            )
            appendLine(
                "不得声称自己拥有现实身体、位置或实时行动能力，也不得代替真人作出见面、出行、" +
                    "转账、照顾或其他现实承诺。可以表达陪伴、倾听和情绪回应。",
            )
            when (profile.identityQuestionPolicy) {
                PersonaIdentityQuestionPolicy.DISCLOSE_AI ->
                    append(
                        "当对方直接询问你是不是 AI、机器人、程序或真人本人时，必须明确说明你是" +
                            " AI 助手，不能否认、含糊回避或声称自己是真人。",
                    )
                PersonaIdentityQuestionPolicy.STAY_IN_CHARACTER ->
                    append(
                        "这是角色语气偏好，本身不能启用身份不披露。" +
                            "当对方询问身份时仍应如实说明你是 AI；" +
                            "只有联系人页面保存了单独的沉浸角色知情确认后，" +
                            "该联系人才能使用不披露策略。任何策略下都不得声称自己是真人，" +
                            "也不要编造现实身体、位置、行动或承诺。",
                    )
            }
        }.trimEnd()
    }
}

class PersonaLoader(
    private val context: Context,
) {
    fun load(): PersonaData {
        val profileStore = PersonaProfileStore(context)
        val stored = profileStore.load()
        if (profileStore.exists()) {
            return stored
        }
        val instructions = context.assets.open("personas/me.md")
            .bufferedReader(Charsets.UTF_8)
            .use { PersonaText.instructions(it.readText()) }
        val examples = context.assets.open("personas/examples.jsonl")
            .bufferedReader(Charsets.UTF_8)
            .use { PersonaText.parseExamples(it.readText(), limit = 0) }
        return PersonaProfileCodec.sanitise(PersonaData(instructions, examples))
    }
}
