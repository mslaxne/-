package com.local.wxbotprobe.chat

import kotlin.math.roundToInt

object TextRules {
    private val nonText = Regex("[\\s\\p{P}\\p{S}_]+")

    fun normalise(text: String): String = text.replace(nonText, "")

    fun sequenceRatio(first: String, second: String): Double {
        if (first.isEmpty() || second.isEmpty()) {
            return 0.0
        }
        if (first == second) {
            return 1.0
        }

        var previous = IntArray(second.length + 1)
        var current = IntArray(second.length + 1)
        for (firstIndex in first.indices) {
            for (secondIndex in second.indices) {
                current[secondIndex + 1] = if (first[firstIndex] == second[secondIndex]) {
                    previous[secondIndex] + 1
                } else {
                    maxOf(previous[secondIndex + 1], current[secondIndex])
                }
            }
            val swap = previous
            previous = current
            current = swap
            current.fill(0)
        }
        return 2.0 * previous[second.length] / (first.length + second.length)
    }
}

class ScreenReadingRules(
    private val width: Int,
    private val height: Int,
    private val inputY: Int,
    private val contentTopY: Int = (height * CHAT_TOP).toInt(),
    private val minChars: Int = 2,
    private val minScore: Double = 0.5,
    private val watch: String = "",
    private val verticalScale: Float = 1f,
) {
    private val sendButtonBottomGap = (90 * verticalScale).roundToInt()
    private val inputBottomGap = (60 * verticalScale).roundToInt()
    private val sameLineGap = (SAME_LINE_GAP * verticalScale).roundToInt()
    private val lineGap = (LINE_GAP * verticalScale).roundToInt()
    private val noisePatterns = listOf(
        Regex("^\\d{1,2}:\\d{2}$"),
        Regex("^\\d+(\\.\\d+)?\\s*KB/s$"),
        Regex("^P:\\s*\\d"),
        Regex("^[dD][xY]:"),
        Regex("^[xX]v:|^[yY]v:"),
        Regex("^(Prs|Size|dX|dY):"),
        Regex("^(今天|昨天|前天|星期[一二三四五六日天])\\s*\\d{0,2}:?\\d{0,2}$"),
        Regex("^\\d{1,2}月\\d{1,2}日\\s*\\d{0,2}:?\\d{0,2}$"),
        Regex("^(上午|下午|晚上)\\s*\\d{1,2}:\\d{2}$"),
        Regex("可按住转文字"),
        Regex("未读|未下载|已下载"),
        Regex("^\\d+\\s*(KB|MB|B)$"),
        Regex("^\\d+$"),
        Regex("^[A-Z]$"),
        Regex("倾听中|松手结束|松开发送"),
        Regex("^你撤回了一条消息"),
        Regex("^对方正在输入"),
        Regex("^(转账|微信转账|已收款|已收钱|已被接收|已退还|待收款|请收款)$"),
        Regex("^[¥￥\\s]*\\d+(\\.\\d{1,2})?$"),
    )

    fun filterRows(
        rows: List<OcrRow>,
        sendButtonY: Int?,
        inkIsMessage: (OcrRow) -> Boolean,
    ): FilteredRows {
        val cutoffTop = maxOf((CHAT_TOP * height).toInt(), contentTopY)
        val cutoffBottom = sendButtonY?.minus(sendButtonBottomGap)
            ?: (inputY - inputBottomGap)
        val messages = mutableListOf<OcrRow>()
        val grey = mutableListOf<OcrRow>()

        for (row in rows) {
            val text = row.text.trim()
            if (text.isEmpty() || row.score < minScore) {
                continue
            }
            if (row.y <= cutoffTop || row.y >= cutoffBottom) {
                continue
            }
            if (TextRules.normalise(text).length < minChars) {
                continue
            }
            if (noisePatterns.any { it.containsMatchIn(text) }) {
                continue
            }
            if (!inkIsMessage(row.copy(text = text))) {
                grey += row.copy(text = text)
                continue
            }
            if (watch.isNotEmpty() && watch in text && row.y < cutoffTop * 3) {
                continue
            }
            messages += row.copy(text = text)
        }
        messages.sortBy { it.y }
        grey.sortBy { it.y }
        return FilteredRows(messages, grey)
    }

    fun bubbleBlocks(rows: List<OcrRow>): List<BubbleBlock> {
        val groups = mutableListOf<MutableList<OcrRow>>()
        var current = mutableListOf<OcrRow>()

        for (row in rows.sortedBy { it.y }) {
            if (current.isNotEmpty()) {
                val previous = current.last()
                val gap = row.y - previous.y
                val sameLine = gap < sameLineGap
                val tooFar = gap > lineGap
                val otherSide = side(row) != side(previous)
                if (tooFar || (otherSide && !sameLine)) {
                    groups += current
                    current = mutableListOf()
                }
            }
            current += row
        }
        if (current.isNotEmpty()) {
            groups += current
        }

        return groups.mapNotNull { group ->
            val block = BubbleBlock(group)
            if (TextRules.normalise(block.text).length < minChars) {
                return@mapNotNull null
            }
            if (side(group.last()) == Side.LEFT) {
                if (block.xLeft < width * LEFT_STUB_MIN) {
                    return@mapNotNull null
                }
            } else if (block.xRight < width * RIGHT_STUB_MIN) {
                return@mapNotNull null
            }
            block
        }
    }

    fun side(row: OcrRow): Side {
        return if (row.xLeft <= width * LEFT_MARGIN_MAX) Side.LEFT else Side.RIGHT
    }

    fun toItems(
        blocks: List<BubbleBlock>,
        kind: (BubbleBlock) -> BubbleKind,
    ): List<ScreenItem> {
        return blocks
            .map {
                ScreenItem(
                    text = it.text,
                    kind = kind(it),
                    y = it.rows.first().y,
                    xLeft = it.xLeft,
                )
            }
            .sortedBy { it.y }
    }

    enum class Side {
        LEFT,
        RIGHT,
    }

    companion object {
        const val LINE_GAP = 90
        const val SAME_LINE_GAP = 25
        const val CHAT_TOP = 0.09
        const val LEFT_MARGIN_MAX = 0.30
        const val LEFT_STUB_MIN = 0.14
        const val RIGHT_STUB_MIN = 0.70
    }
}
