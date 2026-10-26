package com.local.wxbotprobe.api

import android.content.Context

enum class ModelProvider(
    val storageId: String,
    val label: String,
    val presetBaseUrl: String,
    val presetModel: String,
    val requiresKey: Boolean,
    val includesThinkingFlag: Boolean,
    val isFreeTier: Boolean,
) {
    DEEPSEEK(
        "deepseek",
        "DeepSeek",
        "https://api.deepseek.com",
        "deepseek-flash",
        requiresKey = true,
        includesThinkingFlag = true,
        isFreeTier = false,
    ),
    OPENROUTER(
        "openrouter_free",
        "OpenRouter 免费模型",
        "https://openrouter.ai/api/v1",
        "",
        requiresKey = true,
        includesThinkingFlag = false,
        isFreeTier = true,
    ),
    GEMINI(
        "gemini_free",
        "Gemini API 免费层",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        "",
        requiresKey = true,
        includesThinkingFlag = false,
        isFreeTier = true,
    ),
    GROQ(
        "groq_free",
        "Groq 免费层",
        "https://api.groq.com/openai/v1",
        "",
        requiresKey = true,
        includesThinkingFlag = false,
        isFreeTier = true,
    ),
    CUSTOM(
        "custom_openai",
        "自定义 OpenAI-compatible",
        "",
        "",
        requiresKey = false,
        includesThinkingFlag = false,
        isFreeTier = false,
    ),
    DISABLED(
        "disabled",
        "禁用模型生成",
        "",
        "",
        requiresKey = false,
        includesThinkingFlag = false,
        isFreeTier = false,
    ),
    ;

    companion object {
        fun fromStorageId(value: String?): ModelProvider {
            return entries.firstOrNull { it.storageId == value } ?: DEEPSEEK
        }
    }
}

data class ModelConfiguration(
    val provider: ModelProvider,
    val baseUrl: String,
    val model: String,
) {
    fun configured(hasKey: Boolean): Boolean {
        if (provider == ModelProvider.DISABLED) {
            return false
        }
        if (baseUrl.isBlank() || model.isBlank()) {
            return false
        }
        return !provider.requiresKey || hasKey
    }

    fun displayName(): String {
        return if (model.isBlank()) provider.label else "${provider.label} · $model"
    }

    fun usesFreePricing(): Boolean {
        return when (provider) {
            ModelProvider.OPENROUTER ->
                model.endsWith(":free") || model.endsWith("/free")
            else -> provider.isFreeTier
        }
    }
}

object ModelSettings {
    fun load(context: Context): ModelConfiguration {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val provider = ModelProvider.fromStorageId(
            preferences.getString(KEY_PROVIDER, null),
        )
        return ModelConfiguration(
            provider = provider,
            baseUrl = preferences.getString(KEY_BASE_URL, null)
                ?: provider.presetBaseUrl,
            model = preferences.getString(KEY_MODEL, null)
                ?: provider.presetModel,
        )
    }

    fun selectProvider(
        context: Context,
        provider: ModelProvider,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROVIDER, provider.storageId)
            .putString(KEY_BASE_URL, provider.presetBaseUrl)
            .putString(KEY_MODEL, provider.presetModel)
            .apply()
    }

    fun saveEndpoint(
        context: Context,
        baseUrl: String,
        model: String,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_MODEL, model.trim())
            .apply()
    }

    fun isConfigured(context: Context): Boolean {
        val configuration = load(context)
        return configuration.configured(
            ApiKeyStore(context).load(configuration.provider.storageId) != null,
        )
    }

    private const val PREFS = "model_configuration"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
}
