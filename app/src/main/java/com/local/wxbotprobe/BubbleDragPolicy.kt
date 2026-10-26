package com.local.wxbotprobe

import kotlin.math.abs

object BubbleDragPolicy {
    fun moved(dx: Int, dy: Int, touchSlop: Int): Boolean {
        return abs(dx) > touchSlop || abs(dy) > touchSlop
    }

    fun coordinate(
        start: Int,
        delta: Int,
        minimum: Int,
        maximum: Int,
    ): Int {
        return (start + delta).coerceIn(minimum, maximum.coerceAtLeast(minimum))
    }
}
