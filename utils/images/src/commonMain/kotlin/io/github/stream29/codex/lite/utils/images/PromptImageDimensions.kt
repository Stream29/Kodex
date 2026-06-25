package io.github.stream29.codex.lite.utils.images

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

public object PromptImages {
    public const val PatchSize: Int = 32
    public const val MaxDimension: Int = 2048
    public const val MaxInputBytes: Long = 1024L * 1024L * 1024L
    public val HighDetailLimits: PromptImageResizeLimits =
        PromptImageResizeLimits(maxDimension = MaxDimension, maxPatches = 2_500)
    public val OriginalDetailLimits: PromptImageResizeLimits =
        PromptImageResizeLimits(maxDimension = 6_000, maxPatches = 10_000)
}

public data class ImageDimensions(
    public val width: Int,
    public val height: Int,
) {
    init {
        require(width >= 1) { "width must be positive" }
        require(height >= 1) { "height must be positive" }
    }
}

public data class PromptImageResizeLimits(
    public val maxDimension: Int,
    public val maxPatches: Int,
) {
    init {
        require(maxDimension >= 1) { "maxDimension must be positive" }
        require(maxPatches >= 1) { "maxPatches must be positive" }
    }
}

/**
 * Fits dimensions into a bounding square while preserving aspect ratio.
 */
public fun ImageDimensions.fitWithinMaxDimension(maxDimension: Int): ImageDimensions {
    require(maxDimension >= 1) { "maxDimension must be positive" }
    if (width <= maxDimension && height <= maxDimension) return this

    val scale = maxDimension.toDouble() / maxOf(width, height).toDouble()
    return ImageDimensions(
        (width.toDouble() * scale).roundToInt().coerceAtLeast(1),
        (height.toDouble() * scale).roundToInt().coerceAtLeast(1),
    )
}

/**
 * Computes the output dimensions for Codex prompt image patch and dimension limits.
 */
public fun ImageDimensions.fitPromptImageLimits(
    limits: PromptImageResizeLimits,
): ImageDimensions {
    var outputWidth = width
    var outputHeight = height
    if (fitsPromptImageLimits(limits)) {
        return ImageDimensions(outputWidth, outputHeight)
    }

    val maxDimensionScale = min(
        limits.maxDimension.toDouble() / maxOf(outputWidth, outputHeight).toDouble(),
        1.0,
    )
    outputWidth = (outputWidth.toDouble() * maxDimensionScale).roundToInt().coerceAtLeast(1)
    outputHeight = (outputHeight.toDouble() * maxDimensionScale).roundToInt().coerceAtLeast(1)
    if (ImageDimensions(outputWidth, outputHeight).fitsPromptImageLimits(limits)) {
        return ImageDimensions(outputWidth, outputHeight)
    }

    val widthDouble = outputWidth.toDouble()
    val heightDouble = outputHeight.toDouble()
    val patchSize = PromptImages.PatchSize.toDouble()
    var scale = sqrt(patchSize * patchSize * limits.maxPatches.toDouble() / widthDouble / heightDouble)

    val scaledPatchesWide = widthDouble * scale / patchSize
    val scaledPatchesHigh = heightDouble * scale / patchSize
    scale *= min(
        floor(scaledPatchesWide) / scaledPatchesWide,
        floor(scaledPatchesHigh) / scaledPatchesHigh,
    )

    return ImageDimensions(
        floor(widthDouble * scale).toInt().coerceAtLeast(1),
        floor(heightDouble * scale).toInt().coerceAtLeast(1),
    )
}

/**
 * Returns true when dimensions already satisfy Codex prompt image limits.
 */
public fun ImageDimensions.fitsPromptImageLimits(
    limits: PromptImageResizeLimits,
): Boolean {
    val patchesWide = width.ceilDiv(PromptImages.PatchSize)
    val patchesHigh = height.ceilDiv(PromptImages.PatchSize)
    val patchCount = patchesWide.toLong() * patchesHigh.toLong()
    return width <= limits.maxDimension &&
        height <= limits.maxDimension &&
        patchCount <= limits.maxPatches.toLong()
}

private fun Int.ceilDiv(divisor: Int): Int =
    ((toLong() + divisor.toLong() - 1L) / divisor.toLong()).toInt()
