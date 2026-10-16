package io.github.stream29.kodex.utils.images

import kotlin.io.encoding.Base64

/**
 * Invalid prompt image data URL.
 */
public class InvalidImageDataUrlException(
    public val reason: String,
) : IllegalArgumentException("Invalid image data URL: $reason")

/**
 * Prompt image input representation exceeded the configured sanity limit.
 */
public class ImageInputTooLargeException(
    public val representation: String,
    public val size: Long,
    public val max: Long,
) : IllegalArgumentException("$representation is too large: $size bytes exceeds $max bytes")

/**
 * Wraps image bytes in a base64 data URL without decoding or validating them.
 */
public fun ByteArray.toDataUrl(mime: String): String =
    "data:$mime;base64,${Base64.encode(this)}"

/**
 * Wraps image bytes in a base64 data URL without decoding or validating them.
 */
public fun ByteArray.toDataUrl(mimeType: ImageMimeType): String =
    toDataUrl(mimeType.mime)
