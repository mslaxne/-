package com.local.wxbotprobe.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalOcrAuditPolicyTest {
    @Test
    fun `exact existing text is not counted as recovered`() {
        assertEquals(
            0,
            LocalOcrAuditPolicy.countRecovered(
                baseTexts = listOf("你好"),
                auditTexts = listOf("你好"),
            ),
        )
    }

    @Test
    fun `new text is counted once`() {
        assertEquals(
            1,
            LocalOcrAuditPolicy.countRecovered(
                baseTexts = listOf("你好"),
                auditTexts = listOf("新消息", "新消息", "你好"),
            ),
        )
    }

    @Test
    fun `long OCR drift is not counted as new`() {
        assertEquals(
            0,
            LocalOcrAuditPolicy.countRecovered(
                baseTexts = listOf("今天下午三点开会记得带身份证和笔记本电脑还有充电器别忘了"),
                auditTexts = listOf("今天下午三点开会记得带身份证和笔记本还有充电器别忘了"),
            ),
        )
    }

    @Test
    fun `short one-character OCR fragments are ignored`() {
        assertEquals(
            0,
            LocalOcrAuditPolicy.countRecovered(
                baseTexts = emptyList(),
                auditTexts = listOf("测"),
            ),
        )
    }
}
