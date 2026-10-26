@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.io.InternalIoApi::class)

package dev.kauri.framework

import kotlinx.cinterop.*
import kotlinx.io.*
import kotlin.test.*

class EmbeddedSourceTest {
    @Test fun linkedResourceObjectIsReadableWithoutAWorkingDirectoryOrAssetDirectory() {
        checkNotNull(openEmbeddedResource("sentinel.txt")).use { first ->
            checkNotNull(openEmbeddedResource("sentinel.txt")).use { second ->
                assertEquals("Kauri → Kodex — 你好\n", first.readString())
                assertEquals("Kauri → Kodex — 你好\n", second.readString())
            }
        }
        assertNull(openEmbeddedResource("missing"))
        assertNull(openEmbeddedResource("../sentinel.txt"))
    }

    @Test fun lazyBoundedReadsPreserveEveryByte() = memScoped {
        val size = 8192 * 4 + 19
        val data = allocArray<ByteVar>(size)
        repeat(size) { data[it] = (it % 251).toByte() }
        embeddedSource(data, size).use { source ->
            assertEquals(0L, source.buffer.size)
            val sink = Buffer()
            var position = 0
            while (true) {
                val count = source.readAtMostTo(sink, Long.MAX_VALUE)
                if (count == -1L) break
                assertTrue(count in 1L..8192L)
                assertEquals(count, sink.size)
                repeat(count.toInt()) { assertEquals((position++ % 251).toByte(), sink.readByte()) }
            }
            assertEquals(size, position)
            assertEquals(-1L, source.readAtMostTo(sink, 1))
            assertTrue(sink.exhausted())
        }
    }

    @Test fun independentCursorsAndEarlyClose() = memScoped {
        val data = allocArray<ByteVar>(8193)
        repeat(8193) { data[it] = (it % 127).toByte() }
        val first = embeddedSource(data, 8193)
        val second = embeddedSource(data, 8193)
        try {
            assertEquals(0.toByte(), first.readByte())
            assertEquals(1.toByte(), first.readByte())
            first.close()
            first.close()
            assertEquals(0L, first.buffer.size)
            assertFailsWith<IllegalStateException> { first.readByte() }
            assertEquals(0.toByte(), second.readByte())
            assertEquals(1.toByte(), second.readByte())
            second.skip(8191)
            assertTrue(second.exhausted())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun emptyNegativeAndZeroReads() = memScoped {
        val data = allocArray<ByteVar>(1)
        data[0] = 7
        embeddedSource(data, 0).use { source ->
            assertTrue(source.exhausted())
            assertEquals(-1L, source.readAtMostTo(Buffer(), 1))
            assertFailsWith<IllegalArgumentException> { source.readAtMostTo(Buffer(), -1) }
        }
        embeddedSource(data, 1).use { source ->
            val sink = Buffer()
            assertEquals(0L, source.readAtMostTo(sink, 0))
            assertEquals(0L, sink.size)
        }
        assertFailsWith<IllegalArgumentException> { embeddedSource(data, -1) }
        Unit
    }
}
