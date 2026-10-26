package com.local.wxbotprobe.api

import android.content.Context
import com.local.wxbotprobe.SendSettings
import com.local.wxbotprobe.bot.ChatClient

object ModelClientFactory {
    fun create(
        context: Context,
        timeoutMs: Int = 60_000,
        maxRetries: Int = 3,
    ): ChatClient? {
        val configuration = ModelSettings.load(context)
        val apiKey = ApiKeyStore(context)
            .load(configuration.provider.storageId)
            .orEmpty()
        if (!configuration.configured(hasKey = apiKey.isNotBlank())) {
            return null
        }
        val client = DeepSeekClient(
            apiKey = apiKey,
            baseUrl = configuration.baseUrl,
            model = configuration.model,
            timeoutMs = timeoutMs,
            maxRetries = maxRetries,
            includeThinkingFlag = configuration.provider.includesThinkingFlag,
            providerLabel = configuration.provider.label,
        )
        if (configuration.usesFreePricing()) {
            return client
        }
        return MeteredChatClient(
            delegate = client,
            usageStore = ApiUsageStore(context),
            dailyLimitUsd = { SendSettings.dailyCostLimitUsd(context) },
            model = configuration.model,
        )
    }
}
