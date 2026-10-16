package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource

internal class BlockingCoroutineRawSource(
    private val delegate: RawSource,
    private val dispatcher: CoroutineDispatcher,
) : CoroutineRawSource {
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long =
        withContext(dispatcher) {
            delegate.readAtMostTo(sink, byteCount)
        }

    override suspend fun close(): Unit =
        withContext(dispatcher) {
            closeImmediately()
        }

    fun closeImmediately() {
        delegate.close()
    }
}

internal class BlockingCoroutineRawSink(
    private val delegate: RawSink,
    private val dispatcher: CoroutineDispatcher,
) : CoroutineRawSink {
    override suspend fun write(source: Buffer, byteCount: Long): Unit =
        withContext(dispatcher) {
            delegate.write(source, byteCount)
        }

    override suspend fun flush(): Unit =
        withContext(dispatcher) {
            delegate.flush()
        }

    override suspend fun close(): Unit =
        withContext(dispatcher) {
            closeImmediately()
        }

    fun closeImmediately() {
        delegate.close()
    }
}
