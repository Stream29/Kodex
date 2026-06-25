package io.github.stream29.codex.lite.utils.images

internal fun pngBytes(width: Int, height: Int): ByteArray =
    byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x0d,
        0x49, 0x48, 0x44, 0x52,
    ) + width.toBigEndianBytes() + height.toBigEndianBytes()

internal fun gifBytes(width: Int, height: Int): ByteArray =
    "GIF89a".encodeToByteArray() + width.toLittleEndianShortBytes() + height.toLittleEndianShortBytes()

internal fun jpegBytes(width: Int, height: Int): ByteArray =
    byteArrayOf(
        0xff.toByte(), 0xd8.toByte(),
        0xff.toByte(), 0xc0.toByte(),
        0x00, 0x11,
        0x08,
    ) + height.toBigEndianShortBytes() + width.toBigEndianShortBytes() +
        byteArrayOf(
            0x03,
            0x01, 0x11, 0x00,
            0x02, 0x11, 0x00,
            0x03, 0x11, 0x00,
        )

internal fun webpExtendedBytes(width: Int, height: Int): ByteArray =
    riffWebpHeader("VP8X") +
        byteArrayOf(0x0a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) +
        (width - 1).toLittleEndian24Bytes() +
        (height - 1).toLittleEndian24Bytes()

internal fun webpLossyBytes(width: Int, height: Int): ByteArray =
    riffWebpHeader("VP8 ") +
        byteArrayOf(0x0a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x9d.toByte(), 0x01, 0x2a) +
        width.toLittleEndianShortBytes() +
        height.toLittleEndianShortBytes()

internal fun webpLosslessBytes(width: Int, height: Int): ByteArray {
    val widthMinusOne = width - 1
    val heightMinusOne = height - 1
    return riffWebpHeader("VP8L") +
        byteArrayOf(
            0x05, 0x00, 0x00, 0x00,
            0x2f,
            (widthMinusOne and 0xff).toByte(),
            (((widthMinusOne shr 8) and 0x3f) or ((heightMinusOne and 0x03) shl 6)).toByte(),
            ((heightMinusOne shr 2) and 0xff).toByte(),
            ((heightMinusOne shr 10) and 0x0f).toByte(),
        )
}

private fun riffWebpHeader(chunkType: String): ByteArray =
    "RIFF".encodeToByteArray() +
        byteArrayOf(0x00, 0x00, 0x00, 0x00) +
        "WEBP".encodeToByteArray() +
        chunkType.encodeToByteArray()

private fun Int.toBigEndianBytes(): ByteArray =
    byteArrayOf(
        ((this ushr 24) and 0xff).toByte(),
        ((this ushr 16) and 0xff).toByte(),
        ((this ushr 8) and 0xff).toByte(),
        (this and 0xff).toByte(),
    )

private fun Int.toBigEndianShortBytes(): ByteArray =
    byteArrayOf(((this ushr 8) and 0xff).toByte(), (this and 0xff).toByte())

private fun Int.toLittleEndianShortBytes(): ByteArray =
    byteArrayOf((this and 0xff).toByte(), ((this ushr 8) and 0xff).toByte())

private fun Int.toLittleEndian24Bytes(): ByteArray =
    byteArrayOf(
        (this and 0xff).toByte(),
        ((this ushr 8) and 0xff).toByte(),
        ((this ushr 16) and 0xff).toByte(),
    )
