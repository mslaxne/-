package com.local.wxbotprobe.bot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTemplatePolicyTest {
    private val templates = listOf("简短自然", "温和克制")

    @Test
    fun `blank text can accept a template directly`() {
        assertFalse(
            PersonaTemplatePolicy.shouldConfirmReplacement("", templates),
        )
    }

    @Test
    fun `existing known template is replaced directly`() {
        assertFalse(
            PersonaTemplatePolicy.shouldConfirmReplacement(
                "  简短自然\n",
                templates,
            ),
        )
    }

    @Test
    fun `edited or custom text requires confirmation`() {
        assertTrue(
            PersonaTemplatePolicy.shouldConfirmReplacement(
                "简短自然\n我补充的内容",
                templates,
            ),
        )
    }
}
