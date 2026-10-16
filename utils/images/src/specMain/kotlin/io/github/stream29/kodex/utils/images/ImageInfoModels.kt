package io.github.stream29.kodex.utils.images

public data class ImageInfo(
    public val mimeType: ImageMimeType,
    public val dimensions: ImageDimensions,
)

/**
 * Image bytes matched a supported container signature but could not be parsed.
 */
public class InvalidImageException(
    public val reason: String,
) : IllegalArgumentException("Invalid image: $reason")
