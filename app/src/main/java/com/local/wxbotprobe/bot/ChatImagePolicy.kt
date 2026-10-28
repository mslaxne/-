package com.local.wxbotprobe.bot

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ChatImagePolicy {
    const val MAX_IMAGES_PER_REQUEST = 4
    private const val IMAGE_FRAME_TOKENS = 85
    private const val TOKENS_PER_TILE_EDGE = 28
    private const val MIN_IMAGE_AREA = 544.0 * 544.0
    private const val MAX_IMAGE_AREA = 1_300.0 * 1_300.0
    private const val MAX_IMAGE_TOKENS = 1_024

    fun capForRequest(messages: List<ChatMessage>): List<ChatMessage> {
        var remaining = MAX_IMAGES_PER_REQUEST
        val retained = mutableMapOf<Int, List<ChatImage>>()
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (remaining <= 0 || message.role != "user" || message.images.isEmpty()) {
                continue
            }
            val images = message.images.takeLast(remaining)
            retained[index] = images
            remaining -= images.size
        }
        return messages.mapIndexed { index, message ->
            val images = retained[index].orEmpty()
            if (images == message.images) {
                message
            } else {
                message.copy(images = images)
            }
        }
    }

    fun estimatedPromptTokens(image: ChatImage): Int {
        val area = image.width.toDouble() * image.height
        val scale = when {
            area < MIN_IMAGE_AREA -> sqrt(MIN_IMAGE_AREA / area)
            area > MAX_IMAGE_AREA -> sqrt(MAX_IMAGE_AREA / area)
            else -> 1.0
        }
        val width = (image.width * scale).roundToInt().coerceAtLeast(1)
        val height = (image.height * scale).roundToInt().coerceAtLeast(1)
        val horizontalTiles = ceil(width / TOKENS_PER_TILE_EDGE.toDouble())
            .toInt()
            .coerceAtLeast(1)
        val verticalTiles = ceil(height / TOKENS_PER_TILE_EDGE.toDouble())
            .toInt()
            .coerceAtLeast(1)
        return (horizontalTiles * verticalTiles + IMAGE_FRAME_TOKENS)
            .coerceAtMost(MAX_IMAGE_TOKENS)
    }
}
