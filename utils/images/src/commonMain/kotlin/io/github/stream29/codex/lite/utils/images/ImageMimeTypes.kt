package io.github.stream29.codex.lite.utils.images

public enum class ImageMimeType(
    public val mime: String,
) {
    Png("image/png"),
    Jpeg("image/jpeg"),
    Gif("image/gif"),
    Webp("image/webp"),
}

/**
 * Unknown or unsupported image byte stream.
 */
public class UnsupportedImageFormatException :
    IllegalArgumentException("Unsupported image format")

/**
 * Detects the image MIME type from common file signatures.
 *
 * @return The detected MIME type, or `null` when this byte array does not match one of
 * the prompt-image formats this module recognizes.
 */
public fun ByteArray.detectImageMimeType(): ImageMimeType? =
    when {
        hasPrefix(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> ImageMimeType.Png
        hasPrefix(0xff, 0xd8, 0xff) -> ImageMimeType.Jpeg
        hasAsciiPrefix("GIF87a") || hasAsciiPrefix("GIF89a") -> ImageMimeType.Gif
        hasAsciiPrefix("RIFF") && hasAsciiAt(8, "WEBP") -> ImageMimeType.Webp
        else -> null
    }

/**
 * Detects the image MIME type or fails when the bytes are unsupported.
 */
public fun ByteArray.requireImageMimeType(): ImageMimeType =
    detectImageMimeType() ?: throw UnsupportedImageFormatException()

/**
 * Returns true when Codex can safely pass source bytes through byte-for-byte.
 */
public val ImageMimeType.canPreserveSourceBytes: Boolean
    get() = when (this) {
        ImageMimeType.Png,
        ImageMimeType.Jpeg,
        ImageMimeType.Webp,
        -> true

        ImageMimeType.Gif -> false
    }

private fun ByteArray.hasPrefix(vararg values: Int): Boolean {
    if (size < values.size) return false
    return values.indices.all { index -> this[index].toInt() and 0xff == values[index] }
}
