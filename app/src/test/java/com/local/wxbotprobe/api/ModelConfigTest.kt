package com.local.wxbotprobe.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigTest {
    @Test
    fun `deepseek requires a user key`() {
        val config = ModelConfiguration(
            provider = ModelProvider.DEEPSEEK,
            baseUrl = ModelProvider.DEEPSEEK.presetBaseUrl,
            model = ModelProvider.DEEPSEEK.presetModel,
        )

        assertFalse(config.configured(hasKey = false))
        assertTrue(config.configured(hasKey = true))
    }

    @Test
    fun `free provider still requires its own key`() {
        val config = ModelConfiguration(
            provider = ModelProvider.GROQ,
            baseUrl = ModelProvider.GROQ.presetBaseUrl,
            model = "test-model",
        )

        assertFalse(config.configured(hasKey = false))
        assertTrue(config.configured(hasKey = true))
    }

    @Test
    fun `custom compatible endpoint can be keyless`() {
        val config = ModelConfiguration(
            provider = ModelProvider.CUSTOM,
            baseUrl = "https://local.example/v1",
            model = "local-model",
        )

        assertTrue(config.configured(hasKey = false))
    }

    @Test
    fun `disabled provider is never configured`() {
        val config = ModelConfiguration(
            provider = ModelProvider.DISABLED,
            baseUrl = "https://example.com/v1",
            model = "model",
        )

        assertFalse(config.configured(hasKey = true))
    }

    @Test
    fun `openrouter bypasses cost meter only for explicitly free models`() {
        val free = ModelConfiguration(
            provider = ModelProvider.OPENROUTER,
            baseUrl = ModelProvider.OPENROUTER.presetBaseUrl,
            model = "some-model:free",
        )
        val paid = free.copy(model = "some-model")

        assertTrue(free.usesFreePricing())
        assertFalse(paid.usesFreePricing())
    }
}
