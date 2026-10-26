package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.bot.ChatMessage
import com.local.wxbotprobe.bot.ConversationStore
import com.local.wxbotprobe.bot.IncomingMessage
import com.local.wxbotprobe.bot.MemoryConflictClarificationMarker
import com.local.wxbotprobe.bot.PersonaData
import com.local.wxbotprobe.bot.PersonaText
import com.local.wxbotprobe.bot.ReplyConfig
import com.local.wxbotprobe.bot.ReplyEngine
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextConflict
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextMemory
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextMessage
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextOptions
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextPolicy
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class DebugMemoryV2ReplyQualityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) {
            return
        }
        val requestedCase = intent.getStringExtra(EXTRA_CASE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val pending = goAsync()
        Thread(
            {
                val output = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "diagnostics/memory-v2-reply-quality.json",
                )
                output.parentFile?.mkdirs()
                val result = runCatching {
                    runMatrix(context.applicationContext, requestedCase)
                }.getOrElse {
                    JSONObject()
                        .put("status", "failed")
                        .put("error", it.message.orEmpty())
                        .put("stack", it.stackTraceToString())
                }
                output.writeText(result.toString(2), Charsets.UTF_8)
                android.util.Log.i("MemoryV2ReplyQuality", output.readText())
                pending.finish()
            },
            "memory-v2-reply-quality",
        ).start()
    }

    private fun runMatrix(context: Context, requestedCase: String?): JSONObject {
        val cases = requestedCase?.let { id ->
            CASES.filter { it.id == id }.ifEmpty {
                error("unknown reply quality case: $id")
            }
        } ?: CASES
        val client = ModelClientFactory.create(context)
            ?: error("model client is not configured")
        val results = JSONArray()
        cases.forEach { case ->
            results.put(runCase(client, case))
        }
        val rows = (0 until results.length()).map { results.getJSONObject(it) }
        val passed = rows.count { it.optString("status") == "passed" }
        val sendRequests = rows.count { it.optBoolean("send_requested", false) }
        return JSONObject()
            .put("status", "completed")
            .put("run_id", "reply-quality-${System.currentTimeMillis() / 1_000L}")
            .put("requested_case", requestedCase ?: JSONObject.NULL)
            .put("case_count", rows.size)
            .put("passed_cases", passed)
            .put("failed_cases", rows.size - passed)
            .put("all_passed", passed == rows.size)
            .put("send_requests", sendRequests)
            .put("results", results)
    }

    private fun runCase(client: com.local.wxbotprobe.bot.ChatClient, case: ReplyCase): JSONObject {
        val now = System.currentTimeMillis() / 1_000L
        val memory = MemoryV2ReplyContextMemory(
            memoryId = case.memoryId,
            version = 1,
            tier = "A1",
            predicate = case.predicate,
            text = case.memoryText,
            epistemic = "declared",
            commitmentState = "",
            temporalJson = "{}",
            sourceAnchor = now - 300,
            updatedAt = now - 300,
            expiresAt = null,
            retentionScore = 0.9,
            pinned = false,
            evidenceCount = 1,
            pendingConflicts = if (case.pendingConflict) 1 else 0,
        )
        val conflicts = if (case.pendingConflict) {
            listOf(
                MemoryV2ReplyContextConflict(
                    conflictId = "reply-quality-conflict",
                    memoryId = case.memoryId,
                    existingTier = "A1",
                    existingText = case.memoryText,
                    candidateText = case.candidateText,
                    candidateValue = case.candidateValue,
                    createdAt = now - 120,
                ),
            )
        } else {
            emptyList()
        }
        val options = MemoryV2ReplyContextOptions(timezone = "Asia/Shanghai")
        val layers = MemoryV2ReplyContextPolicy.assemble(
            nowEpochSeconds = now,
            query = case.message,
            memories = listOf(memory),
            sessionSummaries = emptyList(),
            sessionMessages = case.history.mapIndexed { index, message ->
                MemoryV2ReplyContextMessage(
                    seq = index + 1,
                    role = message.first,
                    text = message.second,
                    occurredAt = now - 60 + index,
                    messageId = "reply-quality-message-$index",
                )
            },
            omittedMessageCount = 0,
            options = options,
            pendingConflicts = conflicts,
        )
        val memoryContext = MemoryConflictClarificationMarker.wrap(
            MemoryV2ReplyContextPolicy.render(layers, options),
            layers.preflightConflict?.clarification,
        )
        val startedAt = System.currentTimeMillis()
        val engine = ReplyEngine(
            config = ReplyConfig(
                systemPrompt = PersonaText.systemPrompt(ASSISTANT),
                personaMessages = emptyList(),
                temperature = 0.2,
                maxTokens = 300,
                historyTurns = 0,
                minReplyInterval = 0.0,
                maxReplyChars = 300,
                identityQuestionPolicy = ASSISTANT.identityQuestionPolicy,
            ),
            client = client,
            store = ConversationStore(0),
        )
        val reply = engine.handle(
            IncomingMessage(
                id = "reply-quality-${case.id}-$now",
                text = case.message,
            ),
            conversationId = "reply-quality-${case.id}",
            memoryContext = memoryContext,
            conversationHistory = case.history.map {
                ChatMessage(it.first, it.second)
            } + ChatMessage("user", case.message),
            commitReply = false,
        )
        val checks = checks(case.id, reply.text)
        val passed = reply.sent && checks.none { !it.second }
        return JSONObject()
            .put("case", case.id)
            .put("message", case.message)
            .put("memory_text", case.memoryText)
            .put("pending_conflict", case.pendingConflict)
            .put("preflight_clarification", layers.preflightConflict?.clarification ?: JSONObject.NULL)
            .put("reply", reply.text)
            .put("model", reply.model)
            .put("error", reply.error)
            .put("skipped_reason", reply.skippedReason)
            .put("latency_ms", System.currentTimeMillis() - startedAt)
            .put("prompt_tokens", reply.promptTokens)
            .put("completion_tokens", reply.completionTokens)
            .put(
                "checks",
                JSONArray().apply {
                    checks.forEach { check ->
                        put(
                            JSONObject()
                                .put("name", check.first)
                                .put("passed", check.second),
                        )
                    }
                },
            )
            .put("send_requested", false)
            .put("status", if (passed) "passed" else "failed")
    }

    private fun checks(caseId: String, answer: String): List<Pair<String, Boolean>> {
        return when (caseId) {
            CASE_CLARIFY -> listOf(
                "asks_clarification" to (
                    answer.contains("？") ||
                        answer.contains("?")
                    ),
            )
            CASE_EXPLICIT -> listOf(
                "accepts_new_city" to answer.contains("上海"),
                "does_not_deny_correction" to !answer.contains("没记错"),
                "does_not_keep_old_city" to !answer.contains("按杭州"),
                "does_not_repeat_clarification" to listOf(
                    "是我记错了吗",
                    "你之前在杭州吗",
                    "是不是在杭州",
                ).none(answer::contains),
            )
            CASE_AMBIGUOUS -> listOf(
                "does_not_confirm_new_city" to listOf(
                    "已经改成上海",
                    "记住你现在在上海",
                    "确定是上海",
                ).none(answer::contains),
                "keeps_uncertainty_or_old_record" to listOf(
                    "杭州",
                    "不确定",
                    "记不清",
                    "是不是",
                    "吗",
                    "？",
                    "?",
                ).any(answer::contains),
            )
            else -> emptyList()
        }
    }

    private data class ReplyCase(
        val id: String,
        val message: String,
        val memoryId: String,
        val predicate: String,
        val memoryText: String,
        val candidateText: String,
        val candidateValue: String,
        val pendingConflict: Boolean,
        val history: List<Pair<String, String>>,
    )

    companion object {
        const val ACTION = "com.local.wxbotprobe.DEBUG_MEMORY_V2_REPLY_QUALITY"
        const val EXTRA_CASE = "case"
        private const val CASE_CLARIFY = "conflict-clarification"
        private const val CASE_EXPLICIT = "explicit-correction-followup"
        private const val CASE_AMBIGUOUS = "ambiguous-uncertain-followup"
        private val ASSISTANT = PersonaData(
            instructions = "你是一个简洁、诚实、尊重记忆边界的中文聊天助手。",
            examples = emptyList(),
        )
        private val CASES = listOf(
            ReplyCase(
                id = CASE_CLARIFY,
                message = "我不喜欢草莓冰淇淋，喜欢咖啡。",
                memoryId = "reply-quality-preference",
                predicate = "preference.food",
                memoryText = "对方喜欢吃草莓冰淇淋。",
                candidateText = "对方喜欢咖啡。",
                candidateValue = "咖啡",
                pendingConflict = false,
                history = emptyList(),
            ),
            ReplyCase(
                id = CASE_EXPLICIT,
                message = "你确实记错了，我根本没在杭州住过，现在人在上海。",
                memoryId = "reply-quality-city",
                predicate = "profile.city",
                memoryText = "对方住在杭州。",
                candidateText = "对方现在住在上海。",
                candidateValue = "上海",
                pendingConflict = true,
                history = listOf(
                    "user" to "我可能搬到上海了，但还没确定。",
                    "assistant" to "我记得你之前在杭州，是我记错了吗？",
                ),
            ),
            ReplyCase(
                id = CASE_AMBIGUOUS,
                message = "可能是吧，我记不清了。",
                memoryId = "reply-quality-city",
                predicate = "profile.city",
                memoryText = "对方住在杭州。",
                candidateText = "对方现在住在上海。",
                candidateValue = "上海",
                pendingConflict = true,
                history = listOf(
                    "user" to "我可能搬到上海了，但还没确定。",
                    "assistant" to "我记得你之前在杭州，是我记错了吗？",
                ),
            ),
        )
    }
}
