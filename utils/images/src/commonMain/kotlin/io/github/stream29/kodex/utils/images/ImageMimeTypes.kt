package io.github.stream29.kodex.utils.images

public actual fun ByteArray.detectImageMimeType(): ImageMimeType? =
    when {
        hasPrefix(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> ImageMimeType.Png
        hasPrefix(0xff, 0xd8, 0xff) -> ImageMimeType.Jpeg
        hasAsciiPrefix("GIF87a") || hasAsciiPrefix("GIF89a") -> ImageMimeType.Gif
        else -> null
    }

public actual fun ByteArray.requireImageMimeType(): ImageMimeType =
    detectImageMimeType() ?: throw UnsupportedImageFormatException()

public actual val ImageMimeType.canPreserveSourceBytes: Boolean
    get() = when (this) {
        ImageMimeType.Png,
        ImageMimeType.Jpeg,
        -> true

        ImageMimeType.Gif -> false
    }

private fun ByteArray.hasPrefix(vararg values: Int): Boolean {
    if (size < values.size) return false
    return values.indices.all { index -> this[index].toInt() and 0xff == values[index] }
}
