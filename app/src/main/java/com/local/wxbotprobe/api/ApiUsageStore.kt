package com.local.wxbotprobe.api

import android.content.Context
import com.local.wxbotprobe.bot.ChatClient
import com.local.wxbotprobe.bot.ChatMessage
import com.local.wxbotprobe.bot.ChatImagePolicy
import com.local.wxbotprobe.bot.Completion
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ApiCostCalculator {
    fun calculateUsd(
        model: String,
        promptTokens: Int,
        completionTokens: Int,
    ): Double {
        val pro = model.contains("pro", ignoreCase = true)
        val inputRate = if (pro) 1.32 else 0.30
        val outputRate = if (pro) 3.96 else 1.20
        return promptTokens.coerceAtLeast(0) * inputRate / 1_000_000.0 +
            completionTokens.coerceAtLeast(0) * outputRate / 1_000_000.0
    }
}

class ApiBudgetExceededException(
    val spentUsd: Double,
    val limitUsd: Double,
) : IOException(
    "已达到每日 API 费用上限（" +
        "%.2f".format(Locale.US, spentUsd) + "/" +
        "%.2f".format(Locale.US, limitUsd) +
        " 美元），本次请求未发送。",
)

interface ApiUsageRecorder {
    fun spentToday(nowMillis: Long = System.currentTimeMillis()): Double

    fun record(
        promptTokens: Int,
        completionTokens: Int,
        model: String,
        nowMillis: Long = System.currentTimeMillis(),
    )
}

class ApiUsageStore(
    context: Context,
) : ApiUsageRecorder {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun spentToday(nowMillis: Long): Double {
        val date = localDate(nowMillis)
        return if (preferences.getString(KEY_DATE, "") == date) {
            preferences.getFloat(KEY_COST, 0f).toDouble()
        } else {
            0.0
        }
    }

    @Synchronized
    override fun record(
        promptTokens: Int,
        completionTokens: Int,
        model: String,
        nowMillis: Long,
    ) {
        val date = localDate(nowMillis)
        val existing = if (preferences.getString(KEY_DATE, "") == date) {
            preferences.getFloat(KEY_COST, 0f).toDouble()
        } else {
            0.0
        }
        val updated = existing + ApiCostCalculator.calculateUsd(
            model,
            promptTokens,
            completionTokens,
        )
        preferences.edit()
            .putString(KEY_DATE, date)
            .putFloat(KEY_COST, updated.toFloat())
            .apply()
    }

    private fun localDate(nowMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(nowMillis))
    }

    companion object {
        private const val PREFS = "api_usage"
        private const val KEY_DATE = "date"
        private const val KEY_COST = "cost"
    }
}

object ApiBudgetPolicy {
    fun estimatePromptTokens(messages: List<ChatMessage>): Int {
        return messages.sumOf { message ->
            message.role.toByteArray(Charsets.UTF_8).size +
                message.content.toByteArray(Charsets.UTF_8).size +
                message.images.sumOf(ChatImagePolicy::estimatedPromptTokens) +
                MESSAGE_OVERHEAD_TOKENS
        }.coerceAtLeast(1)
    }

    fun worstCaseUsd(
        messages: List<ChatMessage>,
        maxTokens: Int,
        model: String,
    ): Double {
        return ApiCostCalculator.calculateUsd(
            model = model,
            promptTokens = estimatePromptTokens(messages),
            completionTokens = maxTokens.coerceAtLeast(0),
        )
    }

    fun canReserve(
        spentUsd: Double,
        reservedUsd: Double,
        limitUsd: Double,
        requestUsd: Double,
    ): Boolean {
        if (!limitUsd.isFinite()) {
            return true
        }
        return spentUsd.coerceAtLeast(0.0) +
            reservedUsd.coerceAtLeast(0.0) +
            requestUsd.coerceAtLeast(0.0) <= limitUsd + EPSILON_USD
    }

    private const val MESSAGE_OVERHEAD_TOKENS = 16
    private const val EPSILON_USD = 0.0000001
}

private object ApiBudgetGate {
    private val lock = Any()
    private var dateKey = ""
    private var reservedUsd = 0.0

    fun reserve(
        date: String,
        spentUsd: Double,
        limitUsd: Double,
        requestUsd: Double,
    ): Boolean = synchronized(lock) {
        if (dateKey != date) {
            dateKey = date
            reservedUsd = 0.0
        }
        if (!ApiBudgetPolicy.canReserve(
                spentUsd = spentUsd,
                reservedUsd = reservedUsd,
                limitUsd = limitUsd,
                requestUsd = requestUsd,
            )
        ) {
            return false
        }
        reservedUsd += requestUsd
        true
    }

    fun release(date: String, requestUsd: Double) {
        synchronized(lock) {
            if (dateKey != date) {
                return
            }
            reservedUsd = (reservedUsd - requestUsd).coerceAtLeast(0.0)
        }
    }
}

class MeteredChatClient(
    private val delegate: ChatClient,
    private val usageStore: ApiUsageRecorder,
    private val dailyLimitUsd: () -> Double = { Double.POSITIVE_INFINITY },
    private val model: String = "deepseek-flash",
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val enforceDailyLimit: Boolean = true,
) : ChatClient {
    override fun chat(
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
    ): Completion {
        val now = nowMillis()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(now))
        val spent = usageStore.spentToday(now)
        val limit = dailyLimitUsd()
        val requestMessages = ChatImagePolicy.capForRequest(messages)
        val estimate = ApiBudgetPolicy.worstCaseUsd(requestMessages, maxTokens, model)
        if (
            enforceDailyLimit &&
            !ApiBudgetGate.reserve(date, spent, limit, estimate)
        ) {
            throw ApiBudgetExceededException(spentUsd = spent, limitUsd = limit)
        }
        try {
            val completion = delegate.chat(requestMessages, temperature, maxTokens)
            usageStore.record(
                promptTokens = completion.promptTokens,
                completionTokens = completion.completionTokens,
                model = completion.model.ifBlank { model },
                nowMillis = nowMillis(),
            )
            return completion
        } finally {
            if (enforceDailyLimit) {
                ApiBudgetGate.release(date, estimate)
            }
        }
    }
}
