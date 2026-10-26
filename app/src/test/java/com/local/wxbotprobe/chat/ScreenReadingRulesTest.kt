package com.local.wxbotprobe.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenReadingRulesTest {
    private fun rules(
        width: Int = 1080,
        height: Int = 2400,
        inputY: Int = 2320,
    ) = ScreenReadingRules(
        width = width,
        height = height,
        inputY = inputY,
        watch = "",
    )

    private fun row(
        y: Int,
        xLeft: Int,
        xRight: Int,
        text: String,
    ) = OcrRow(y, xLeft, xRight, text, 0.99)

    @Test
    fun `full width incoming line still belongs to left side`() {
        assertEquals(
            ScreenReadingRules.Side.LEFT,
            rules().side(row(100, 195, 1040, "long line")),
        )
    }

    @Test
    fun `own bubble remains right side`() {
        assertEquals(
            ScreenReadingRules.Side.RIGHT,
            rules().side(row(100, 700, 1040, "own line")),
        )
    }

    @Test
    fun `wrapped lines merge into one bubble`() {
        val rows = listOf(
            row(100, 195, 1040, "项目总结已经提交给负责人"),
            row(158, 195, 700, "等待确认后再归档"),
            row(216, 195, 900, "今天可以完成收尾"),
        )
        val blocks = rules().bubbleBlocks(rows)
        assertEquals(1, blocks.size)
        assertEquals(
            "项目总结已经提交给负责人等待确认后再归档今天可以完成收尾",
            blocks.single().text,
        )
    }

    @Test
    fun `different sides do not merge`() {
        val rows = listOf(
            row(100, 195, 900, "对方"),
            row(160, 700, 1040, "自己"),
        )
        assertEquals(2, rules().bubbleBlocks(rows).size)
    }

    @Test
    fun `bare dates and ui noise are dropped but real today message stays`() {
        val filtered = rules().filterRows(
            rows = listOf(
                row(200, 195, 700, "昨天"),
                row(300, 195, 700, "今天好累啊"),
                row(400, 195, 700, "可按住转文字"),
            ),
            sendButtonY = null,
            inkIsMessage = { true },
        )
        assertEquals(listOf("今天好累啊"), filtered.messages.map { it.text })
    }

    @Test
    fun `grey nickname rows are separated from messages`() {
        val filtered = rules().filterRows(
            rows = listOf(
                row(300, 200, 650, "顾嘉小筑"),
                row(400, 200, 650, "剃了毛"),
            ),
            sendButtonY = null,
            inkIsMessage = { it.text != "顾嘉小筑" },
        )
        assertEquals(listOf("剃了毛"), filtered.messages.map { it.text })
        assertEquals(listOf("顾嘉小筑"), filtered.grey.map { it.text })
    }

    @Test
    fun `transfer labels are not message rows`() {
        val filtered = rules().filterRows(
            rows = listOf(
                row(300, 200, 420, "转账"),
                row(320, 200, 430, "¥100.00"),
                row(340, 200, 430, "已被接收"),
                row(400, 200, 650, "同事?"),
            ),
            sendButtonY = null,
            inkIsMessage = { it.text == "同事?" },
        )
        assertEquals(emptyList<String>(), filtered.grey.map { it.text })
    }

    @Test
    fun `top notification band is excluded while lower messages remain`() {
        val filtered = ScreenReadingRules(
            width = 1080,
            height = 2400,
            inputY = 2320,
            contentTopY = 500,
        ).filterRows(
            rows = listOf(
                row(499, 80, 1000, "通知预览：你有一条新消息"),
                row(500, 80, 1000, "通知预览：边界行"),
                row(501, 195, 700, "真正的聊天消息"),
            ),
            sendButtonY = null,
            inkIsMessage = { true },
        )
        assertEquals(listOf("真正的聊天消息"), filtered.messages.map { it.text })
    }
}
