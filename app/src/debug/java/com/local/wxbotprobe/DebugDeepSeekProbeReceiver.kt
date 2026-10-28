package com.local.wxbotprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.wxbotprobe.api.ModelClientFactory
import com.local.wxbotprobe.api.ModelSettings
import com.local.wxbotprobe.bot.ConversationStore
import com.local.wxbotprobe.bot.ChatMessage
import com.local.wxbotprobe.bot.IncomingMessage
import com.local.wxbotprobe.bot.PersonaSelectionService
import com.local.wxbotprobe.bot.PersonaText
import com.local.wxbotprobe.bot.ReplyConfig
import com.local.wxbotprobe.bot.ReplyEngine
import com.local.wxbotprobe.bot.RoleplayPolicyService
import com.local.wxbotprobe.memory.v2.MemoryV2AdminStore
import com.local.wxbotprobe.memory.v2.MemoryV2ReplyContextPayloadBuilder
import com.local.wxbotprobe.send.WeChatSender
import java.io.File
import org.json.JSONObject

class DebugDeepSeekProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) {
            return
        }
        val pending = goAsync()
        val sendReply = intent.getBooleanExtra(EXTRA_SEND, false)
        val probeText = intent.getStringExtra(EXTRA_TEXT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PROBE_TEXT
        Thread(
            {
                val startedAt = System.currentTimeMillis()
                val output = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "diagnostics/deepseek-probe.json",
                )
                output.parentFile?.mkdirs()
                val result = runCatching {
                    val client = ModelClientFactory.create(context)
                        ?: error("model configuration is incomplete")
                    val contact = MemoryV2AdminStore(context)
                        .contacts()
                        .firstOrNull()
                        ?: error("no active memory contact")
                    val selection = PersonaSelectionService(context)
                        .selectedForContact(contact.contactId)
                    val persona = selection.persona
                    val roleplay = RoleplayPolicyService(context)
                    val replyContext = MemoryV2ReplyContextPayloadBuilder(context)
                        .build(contact.scopeId, probeText)
                    val conversation = if (replyContext.includesConversation) {
                        replyContext.conversationMessages.map {
                            ChatMessage(it.role, it.text)
                        } + ChatMessage("user", probeText)
                    } else {
                        null
                    }
                    val config = ReplyConfig(
                        systemPrompt = PersonaText.systemPrompt(persona),
                        personaMessages = PersonaText.turns(persona.examples),
                        temperature = 1.0,
                        maxTokens = 500,
                        historyTurns = 0,
                        identityQuestionPolicy = persona.identityQuestionPolicy,
                        roleplayPolicyProvider = {
                            roleplay.effectivePolicy(
                                contactId = contact.contactId,
                                personaId = selection.personaId,
                                persona = persona,
                            )
                        },
                    )
                    val engine = ReplyEngine(
                        config,
                        client,
                        ConversationStore(config.historyTurns),
                    )
                    val reply = engine.handle(
                        IncomingMessage(
                            id = "debug-deepseek-${System.currentTimeMillis()}",
                            text = probeText,
                        ),
                        memoryContext = replyContext.memoryText,
                        conversationHistory = conversation,
                        commitReply = false,
                    )
                    val report = JSONObject()
                        .put("status", if (reply.sent) "passed" else "failed")
                        .put("provider", ModelSettings.load(context).provider.label)
                        .put("probe_text", probeText)
                        .put("text", reply.text)
                        .put("model", reply.model)
                        .put("elapsed_ms", System.currentTimeMillis() - startedAt)
                        .put("latency_ms", reply.latencyMs)
                        .put("prompt_tokens", reply.promptTokens)
                        .put("completion_tokens", reply.completionTokens)
                        .put("reply_context_mode", replyContext.mode.storedValue)
                        .put(
                            "reply_context_bytes",
                            replyContext.memoryText
                                .toByteArray(Charsets.UTF_8)
                                .size,
                        )
                        .put(
                            "reply_context_messages",
                            conversation?.size ?: 0,
                        )
                        .put(
                            "reply_context_snapshot",
                            replyContext.snapshotSha256.orEmpty(),
                        )
                        .put("scope_id", contact.scopeId)
                        .put("budget_blocked", reply.budgetBlocked)
                        .put("error", reply.error)
                        .put("skipped_reason", reply.skippedReason)
                    if (reply.sent && sendReply) {
                        val sendResult = WeChatSender(context.applicationContext)
                            .send(reply.text, dryRun = false)
                        report
                            .put("send_requested", true)
                            .put("send_status", if (sendResult.success) "passed" else "failed")
                            .put("sent_parts", sendResult.sentParts)
                            .put("part_count", sendResult.parts.size)
                            .put("result_unknown", sendResult.resultUnknown)
                            .put("send_error", sendResult.error)
                    } else {
                        report
                            .put("send_requested", false)
                            .put("send_status", "not_requested")
                    }
                    report
                }
                output.writeText(
                    result.fold(
                        onSuccess = { it.toString(2) },
                        onFailure = {
                            JSONObject()
                                .put("status", "failed")
                                .put("elapsed_ms", System.currentTimeMillis() - startedAt)
                                .put("error", it.message.orEmpty())
                                .toString(2)
                        },
                    ),
                    Charsets.UTF_8,
                )
                android.util.Log.i("DebugDeepSeekProbe", output.readText())
                pending.finish()
            },
            "debug-deepseek-probe",
        ).start()
    }

    companion object {
        const val ACTION = "com.local.wxbotprobe.DEBUG_DEEPSEEK_PROBE"
        const val EXTRA_SEND = "send"
        const val EXTRA_TEXT = "text"
        private const val DEFAULT_PROBE_TEXT =
            "请根据当前记忆和最近对话，用一句话回应我还在不在。"
    }
}
