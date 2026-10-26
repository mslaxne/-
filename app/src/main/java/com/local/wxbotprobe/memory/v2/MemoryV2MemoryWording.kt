package com.local.wxbotprobe.memory.v2

object MemoryV2MemoryWording {
    private val internalAgent = Regex(
        "(?i)(?<![A-Za-z0-9_-])\\s*agent\\s*(?![A-Za-z0-9_-])",
    )

    fun forCurrentPersona(text: String): String {
        return text
            .replace("用户", "对方")
            .replace(internalAgent, "你")
    }
}
