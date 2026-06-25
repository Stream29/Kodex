@file:OptIn(ExperimentalEncodingApi::class)

package io.github.stream29.codex.lite.utils.images

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val DataUrlPrefix: String = "data:"

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
    "data:$mime;base64,${Base64.Default.encode(this)}"

/**
 * Wraps image bytes in a base64 data URL without decoding or validating them.
 */
public fun ByteArray.toDataUrl(mimeType: ImageMimeType): String =
    toDataUrl(mimeType.mime)

/**
 * Decodes the base64 payload from a data URL using Codex prompt-image guards.
 *
 * This mirrors Codex's Rust behavior: the `data:` prefix and `base64` metadata
 * marker are matched case-insensitively, and metadata MIME is not trusted for
 * image format detection.
 */
public fun String.decodePromptImageDataUrlBytes(
    maxInputBytes: Long = PromptImages.MaxInputBytes,
): ByteArray {
    require(maxInputBytes >= 0) { "maxInputBytes must be non-negative" }

    val rest = this
        .takeIf { it.regionMatches(0, DataUrlPrefix, 0, DataUrlPrefix.length, ignoreCase = true) }
        ?.substring(DataUrlPrefix.length)
        ?: throw InvalidImageDataUrlException("missing data: prefix")

    val commaIndex = rest.indexOf(',')
    if (commaIndex < 0) {
        throw InvalidImageDataUrlException("missing comma separator")
    }

    val metadata = rest.substring(0, commaIndex)
    if (metadata.split(';').none { it.equals("base64", ignoreCase = true) }) {
        throw InvalidImageDataUrlException("only base64 data URLs are supported")
    }

    val encoded = rest.substring(commaIndex + 1)
    if (encoded.length.toLong() > maxInputBytes) {
        throw ImageInputTooLargeException("base64 payload", encoded.length.toLong(), maxInputBytes)
    }

    val bytes = try {
        Base64.Default.decode(encoded)
    } catch (e: IllegalArgumentException) {
        throw InvalidImageDataUrlException("invalid base64 payload: ${e.message}")
    }
    if (bytes.size.toLong() > maxInputBytes) {
        throw ImageInputTooLargeException("decoded input", bytes.size.toLong(), maxInputBytes)
    }
    return bytes
}
