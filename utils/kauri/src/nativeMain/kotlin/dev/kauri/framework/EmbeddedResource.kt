@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.io.UnsafeIoApi::class)

package dev.kauri.framework

import dev.kauri.assets.kauri_assets_start
import dev.kauri.assets.kauri_assets_end
import kotlinx.cinterop.*
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.unsafe.UnsafeBufferOperations
import platform.posix.memcpy

public actual fun openEmbeddedResource(path: String): Source? {
    val data = kauri_assets_start.reinterpret<ByteVar>()
    val size = kauri_assets_end.rawValue.toLong() - data.rawValue.toLong()
    return findResource(size, { data[it].toInt() and 255 }, path)?.let { (offset, length) ->
        embeddedSource(checkNotNull(data + offset), length)
    }
}

internal fun embeddedSource(data: CPointer<ByteVar>, length: Int): Source {
    require(length >= 0)
    return object : RawSource {
        private var position = 0
        private var closed = false

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            check(!closed) { "Embedded source is closed" }
            require(byteCount >= 0) { "Negative byte count" }
            if (byteCount == 0L) return 0
            if (position == length) return -1
            val count = UnsafeBufferOperations.writeToTail(sink, 1) { bytes, start, end ->
                val copied = minOf(byteCount, (length - position).toLong(), (end - start).toLong(), 8192L).toInt()
                bytes.usePinned { memcpy(it.addressOf(start), data + position, copied.convert()) }
                copied
            }
            position += count
            return count.toLong()
        }

        override fun close() { closed = true }
    }.buffered()
}

internal fun findResource(size: Long, byteAt: (Int) -> Int, name: String): Pair<Int, Int>? {
    check(size in 8..Int.MAX_VALUE.toLong()) { "Invalid embedded resource size" }
    var position = 0
    fun integer(): Int {
        check(position.toLong() + 4 <= size) { "Truncated embedded resource header" }
        var value = 0
        repeat(4) { value = (value shl 8) or byteAt(position++) }
        return value
    }
    check(integer() == 0x4b415552) { "Invalid embedded resource signature" }
    val count = integer()
    check(count >= 0 && count.toLong() * 8 <= size - 8) { "Invalid embedded resource count" }
    var found: Pair<Int, Int>? = null
    val names = mutableSetOf<String>()
    repeat(count) {
        val nameLength = integer()
        val length = integer()
        check(nameLength > 0 && length >= 0 && position.toLong() + nameLength + length <= size) {
            "Invalid embedded resource entry"
        }
        val key = ByteArray(nameLength) { byteAt(position++).toByte() }.decodeToString(throwOnInvalidSequence = true)
        check(names.add(key)) { "Duplicate embedded resource" }
        if (key == name) found = position to length
        position += length
    }
    check(position.toLong() == size) { "Trailing embedded resource data" }
    return found
}
