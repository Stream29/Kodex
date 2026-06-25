package io.github.stream29.codex.lite.utils.images.codec

import io.github.stream29.codex.lite.utils.images.ImageMimeType

public class ImageCodecException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

public class UnsupportedImageCodecException(
    public val mimeType: ImageMimeType,
    public val capability: String,
) : UnsupportedOperationException("Unsupported image codec capability `$capability` for ${mimeType.mime}")
