package com.local.wxbotprobe.chat

object GroupTitleWarningPolicy {
    private val memberCountSuffix = Regex(".*[（(]\\s*\\d{1,4}\\s*[）)]\\s*$")

    fun isLikelyGroupTitle(title: String): Boolean {
        return memberCountSuffix.matches(title.trim())
    }
}
