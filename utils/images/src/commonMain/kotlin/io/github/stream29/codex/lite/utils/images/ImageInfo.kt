package io.github.stream29.codex.lite.utils.images

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

/**
 * Detects image format and dimensions from common image container headers.
 *
 * @return The detected image info, or `null` when this byte array does not
 * match a supported prompt-image container signature.
 */
public fun ByteArray.detectImageInfo(): ImageInfo? {
    val mimeType = detectImageMimeType() ?: return null
    return ImageInfo(
        mimeType = mimeType,
        dimensions = when (mimeType) {
            ImageMimeType.Png -> pngDimensions()
            ImageMimeType.Jpeg -> jpegDimensions()
            ImageMimeType.Gif -> gifDimensions()
            ImageMimeType.Webp -> webpDimensions()
        },
    )
}

/**
 * Detects image format and dimensions or fails when the bytes are unsupported.
 */
public fun ByteArray.requireImageInfo(): ImageInfo =
    detectImageInfo() ?: throw UnsupportedImageFormatException()

private fun ByteArray.pngDimensions(): ImageDimensions {
    if (size < 24) invalidImage("truncated PNG header")
    if (!hasAsciiAt(12, "IHDR")) invalidImage("missing PNG IHDR chunk")
    return dimensionsFromLongs(u32BE(16), u32BE(20), "PNG")
}

private fun ByteArray.gifDimensions(): ImageDimensions {
    if (size < 10) invalidImage("truncated GIF header")
    return dimensionsFromInts(u16LE(6), u16LE(8), "GIF")
}

private fun ByteArray.jpegDimensions(): ImageDimensions {
    var offset = 2
    while (offset < size) {
        while (offset < size && u8(offset) != 0xff) {
            offset++
        }
        while (offset < size && u8(offset) == 0xff) {
            offset++
        }
        if (offset >= size) break

        val marker = u8(offset)
        offset++
        if (marker == 0xd9 || marker == 0xda) break
        if (marker == 0x01 || marker in 0xd0..0xd7) continue

        if (offset + 2 > size) invalidImage("truncated JPEG segment length")
        val segmentLength = u16BE(offset)
        offset += 2
        if (segmentLength < 2) invalidImage("invalid JPEG segment length")

        val payloadLength = segmentLength - 2
        val segmentStart = offset
        val segmentEnd = segmentStart + payloadLength
        if (segmentEnd > size) invalidImage("truncated JPEG segment")

        if (marker.isJpegStartOfFrame()) {
            if (payloadLength < 6) invalidImage("truncated JPEG size segment")
            return dimensionsFromInts(
                width = u16BE(segmentStart + 3),
                height = u16BE(segmentStart + 1),
                format = "JPEG",
            )
        }
        offset = segmentEnd
    }
    invalidImage("missing JPEG size segment")
}

private fun Int.isJpegStartOfFrame(): Boolean =
    this in 0xc0..0xcf && this != 0xc4 && this != 0xc8 && this != 0xcc

private fun ByteArray.webpDimensions(): ImageDimensions {
    if (size < 20) invalidImage("truncated WebP header")
    return when {
        hasAsciiAt(12, "VP8X") -> webpExtendedDimensions()
        hasAsciiAt(12, "VP8 ") -> webpLossyDimensions()
        hasAsciiAt(12, "VP8L") -> webpLosslessDimensions()
        else -> invalidImage("unsupported WebP chunk")
    }
}

private fun ByteArray.webpExtendedDimensions(): ImageDimensions {
    if (size < 30) invalidImage("truncated WebP extended header")
    return dimensionsFromInts(
        width = u24LE(24) + 1,
        height = u24LE(27) + 1,
        format = "WebP",
    )
}

private fun ByteArray.webpLossyDimensions(): ImageDimensions {
    if (size < 30) invalidImage("truncated WebP lossy header")
    if (!hasBytesAt(23, 0x9d, 0x01, 0x2a)) invalidImage("missing WebP lossy sync code")
    return dimensionsFromInts(
        width = u16LE(26) and 0x3fff,
        height = u16LE(28) and 0x3fff,
        format = "WebP",
    )
}

private fun ByteArray.webpLosslessDimensions(): ImageDimensions {
    if (size < 25) invalidImage("truncated WebP lossless header")
    if (u8(20) != 0x2f) invalidImage("missing WebP lossless signature")

    val b0 = u8(21)
    val b1 = u8(22)
    val b2 = u8(23)
    val b3 = u8(24)
    return dimensionsFromInts(
        width = 1 + (((b1 and 0x3f) shl 8) or b0),
        height = 1 + (((b3 and 0x0f) shl 10) or (b2 shl 2) or ((b1 and 0xc0) shr 6)),
        format = "WebP",
    )
}

private fun dimensionsFromInts(width: Int, height: Int, format: String): ImageDimensions =
    dimensionsFromLongs(width.toLong(), height.toLong(), format)

private fun dimensionsFromLongs(width: Long, height: Long, format: String): ImageDimensions {
    if (width !in 1..Int.MAX_VALUE.toLong() || height !in 1..Int.MAX_VALUE.toLong()) {
        invalidImage("$format dimensions are out of range")
    }
    return ImageDimensions(width.toInt(), height.toInt())
}

private fun invalidImage(reason: String): Nothing =
    throw InvalidImageException(reason)
