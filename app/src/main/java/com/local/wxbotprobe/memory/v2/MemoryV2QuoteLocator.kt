package com.local.wxbotprobe.memory.v2

import java.security.MessageDigest

data class LocatedQuote(
    val startCodepoint: Int,
    val endCodepoint: Int,
    val sourceSha256: String,
)

object MemoryV2QuoteLocator {
    fun locate(
        body: String,
        quote: String,
        maxQuoteUtf8Bytes: Int = 2_048,
    ): LocatedQuote {
        if (hasUnpairedSurrogate(body) || hasUnpairedSurrogate(quote)) {
            throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_UNICODE)
        }
        if (quote.isEmpty()) {
            throw MemoryV2Exception(MemoryV2ErrorCode.INVALID_ARGUMENT)
        }
        if (quote.toByteArray(Charsets.UTF_8).size > maxQuoteUtf8Bytes) {
            throw MemoryV2Exception(MemoryV2ErrorCode.QUOTE_TOO_LARGE)
        }
        val start = body.indexOf(quote)
        if (start < 0) {
            throw MemoryV2Exception(MemoryV2ErrorCode.QUOTE_NOT_FOUND)
        }
        if (body.indexOf(quote, startIndex = start + 1) >= 0) {
            throw MemoryV2Exception(MemoryV2ErrorCode.QUOTE_AMBIGUOUS)
        }
        val startCodepoint = body.codePointCount(0, start)
        val endCodepoint = body.codePointCount(0, start + quote.length)
        return LocatedQuote(
            startCodepoint = startCodepoint,
            endCodepoint = endCodepoint,
            sourceSha256 = sha256(body),
        )
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    internal fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        return true
                    }
                    index += 2
                }
                current.isLowSurrogate() -> return true
                else -> index++
            }
        }
        return false
    }
}
