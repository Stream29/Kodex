package io.github.stream29.kodex.utils.images

import kotlin.math.roundToInt

public actual fun ImageDimensions.fitWithinMaxDimension(maxDimension: Int): ImageDimensions {
    require(maxDimension >= 1) { "maxDimension must be positive" }
    if (width <= maxDimension && height <= maxDimension) return this

    val scale = maxDimension.toDouble() / maxOf(width, height).toDouble()
    return ImageDimensions(
        (width.toDouble() * scale).roundToInt().coerceAtLeast(1),
        (height.toDouble() * scale).roundToInt().coerceAtLeast(1),
    )
}
