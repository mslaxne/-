package com.local.wxbotprobe.chat

const val IMAGE_PLACEHOLDER = "[[图片]]"

data class OcrRow(
    val y: Int,
    val xLeft: Int,
    val xRight: Int,
    val text: String,
    val score: Double,
)

data class BubbleBlock(
    val rows: List<OcrRow>,
) {
    val text: String = rows.joinToString(separator = "") { it.text }.trim()
    val xLeft: Int = rows.minOf { it.xLeft }
    val xRight: Int = rows.maxOf { it.xRight }
}

enum class BubbleKind {
    IN,
    OUT,
    UNKNOWN,
}

data class ScreenItem(
    val text: String,
    val kind: BubbleKind,
    val y: Int,
    val xLeft: Int,
    val image: ByteArray? = null,
)

data class FilteredRows(
    val messages: List<OcrRow>,
    val grey: List<OcrRow>,
)
