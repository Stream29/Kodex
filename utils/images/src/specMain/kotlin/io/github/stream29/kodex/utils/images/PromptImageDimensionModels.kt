package io.github.stream29.kodex.utils.images

public object PromptImages {
    public const val MaxDimension: Int = 2048
    public const val MaxInputBytes: Long = 1024L * 1024L * 1024L
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
