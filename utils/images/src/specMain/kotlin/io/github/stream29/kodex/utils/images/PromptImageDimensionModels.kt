package io.github.stream29.kodex.utils.images

public object PromptImages {
    public const val PatchSize: Int = 32
    public const val MaxDimension: Int = 2048
    public const val MaxInputBytes: Long = 1024L * 1024L * 1024L
    public val HighDetailLimits: PromptImageResizeLimits =
        PromptImageResizeLimits(maxDimension = MaxDimension, maxPatches = 2_500)
    public val OriginalDetailLimits: PromptImageResizeLimits =
        PromptImageResizeLimits(maxDimension = 6_000, maxPatches = 10_000)
}

/** @throws IllegalArgumentException if width or height is not positive. */
public data class ImageDimensions(
    public val width: Int,
    public val height: Int,
) {
    init {
        require(width >= 1) { "width must be positive" }
        require(height >= 1) { "height must be positive" }
    }
}

/** @throws IllegalArgumentException if maxDimension or maxPatches is not positive. */
public data class PromptImageResizeLimits(
    public val maxDimension: Int,
    public val maxPatches: Int,
) {
    init {
        require(maxDimension >= 1) { "maxDimension must be positive" }
        require(maxPatches >= 1) { "maxPatches must be positive" }
    }
}
