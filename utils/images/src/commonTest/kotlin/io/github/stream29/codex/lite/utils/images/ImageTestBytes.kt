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

internal fun riffWebpBytes(): ByteArray =
    "RIFF".encodeToByteArray() +
        byteArrayOf(0x00, 0x00, 0x00, 0x00) +
        "WEBP".encodeToByteArray() +
        "VP8X".encodeToByteArray()

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
