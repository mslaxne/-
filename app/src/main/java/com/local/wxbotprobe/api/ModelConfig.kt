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
    val supportsVision: Boolean = false,
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

    fun visionScopeKey(): String {
        return ModelVisionScope.key(provider, baseUrl, model)
    }
}

object ModelVisionScope {
    fun key(
        provider: ModelProvider,
        baseUrl: String,
        model: String,
    ): String {
        return listOf(
            provider.storageId,
            baseUrl.trim().trimEnd('/'),
            model.trim(),
        ).joinToString("|")
    }
}

object ModelSettings {
    fun load(context: Context): ModelConfiguration {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val provider = ModelProvider.fromStorageId(
            preferences.getString(KEY_PROVIDER, null),
        )
        val baseUrl = preferences.getString(KEY_BASE_URL, null)
            ?: provider.presetBaseUrl
        val model = preferences.getString(KEY_MODEL, null)
            ?: provider.presetModel
        val visionKey = ModelVisionScope.key(provider, baseUrl, model)
        return ModelConfiguration(
            provider = provider,
            baseUrl = baseUrl,
            model = model,
            supportsVision = preferences.getBoolean(KEY_VISION_ENABLED, false) &&
                preferences.getString(KEY_VISION_SCOPE, "") == visionKey,
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
            .putBoolean(KEY_VISION_ENABLED, false)
            .putString(
                KEY_VISION_SCOPE,
                ModelVisionScope.key(
                    provider,
                    provider.presetBaseUrl,
                    provider.presetModel,
                ),
            )
            .apply()
    }

    fun saveEndpoint(
        context: Context,
        baseUrl: String,
        model: String,
    ) {
        val previous = load(context)
        val nextKey = ModelVisionScope.key(
            previous.provider,
            baseUrl,
            model,
        )
        val preserveVision = previous.supportsVision &&
            previous.visionScopeKey() == nextKey
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_MODEL, model.trim())
            .putBoolean(KEY_VISION_ENABLED, preserveVision)
            .putString(KEY_VISION_SCOPE, nextKey)
            .apply()
    }

    fun setVisionInputEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        val configuration = load(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VISION_ENABLED, enabled)
            .putString(KEY_VISION_SCOPE, configuration.visionScopeKey())
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
    private const val KEY_VISION_ENABLED = "vision_enabled"
    private const val KEY_VISION_SCOPE = "vision_scope"
}
