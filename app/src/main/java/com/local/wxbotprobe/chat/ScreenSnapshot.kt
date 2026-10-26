package com.local.wxbotprobe.chat

data class ScreenSnapshot(
    val width: Int,
    val height: Int,
    val items: List<ScreenItem>,
    val greyRows: List<OcrRow>,
    val isGroup: Boolean,
    val inputField: ScreenPoint?,
    val sendButtonY: Int?,
    val ocrRowCount: Int,
    val engine: String,
    val chat: ChatIdentity = ChatIdentity.EMPTY,
    val frameMeanDiff: Double? = null,
    val frameChangedRatio: Double? = null,
    val ocrEngineMs: Long = 0,
    val pixelRulesMs: Long = 0,
)
