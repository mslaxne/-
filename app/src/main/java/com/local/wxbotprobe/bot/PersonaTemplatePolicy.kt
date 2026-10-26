package com.local.wxbotprobe.bot

object PersonaTemplatePolicy {
    fun shouldConfirmReplacement(
        currentText: String,
        templateTexts: List<String>,
    ): Boolean {
        val current = normalise(currentText)
        if (current.isEmpty()) {
            return false
        }
        return templateTexts.none { normalise(it) == current }
    }

    private fun normalise(text: String): String {
        return text
            .replace("\r\n", "\n")
            .trim()
    }
}
