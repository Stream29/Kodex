package io.github.stream29.codex.lite.utils.images

internal fun ByteArray.hasAsciiPrefix(value: String): Boolean =
    hasAsciiAt(0, value)

internal fun ByteArray.hasAsciiAt(offset: Int, value: String): Boolean {
    if (offset < 0 || size < offset + value.length) return false
    return value.indices.all { index -> u8(offset + index) == value[index].code }
}

internal fun ByteArray.hasBytesAt(offset: Int, vararg values: Int): Boolean {
    if (offset < 0 || size < offset + values.size) return false
    return values.indices.all { index -> u8(offset + index) == values[index] }
}

internal fun ByteArray.u8(index: Int): Int =
    this[index].toInt() and 0xff

internal fun ByteArray.u16BE(index: Int): Int =
    (u8(index) shl 8) or u8(index + 1)

internal fun ByteArray.u16LE(index: Int): Int =
    u8(index) or (u8(index + 1) shl 8)

internal fun ByteArray.u32BE(index: Int): Long =
    (u8(index).toLong() shl 24) or
        (u8(index + 1).toLong() shl 16) or
        (u8(index + 2).toLong() shl 8) or
        u8(index + 3).toLong()
