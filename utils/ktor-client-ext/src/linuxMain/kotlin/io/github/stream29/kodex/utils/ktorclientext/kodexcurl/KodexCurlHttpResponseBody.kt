/*
 * Derived from Ktor's Curl client engine.
 * Copyright 2014-2026 JetBrains s.r.o and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.posix.size_t
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class KodexCurlHttpResponseBody(
    callContext: Job,
    private val onUnpause: () -> Unit,
) : KodexCurlResponseBodyData, CoroutineScope {
    private val job = Job(callContext)
    override val coroutineContext: CoroutineContext = job

    val bodyChannel: ByteChannel = ByteChannel().apply {
        attachJob(job)
    }

    @Volatile
    private var paused: Boolean = false
    private var lastNetworkActivity: TimeMark = TimeSource.Monotonic.markNow()

    @OptIn(ExperimentalForeignApi::class, InternalAPI::class)
    override fun onBodyChunkReceived(buffer: CPointer<ByteVar>, size: size_t, count: size_t): size_t {
        if (bodyChannel.isClosedForWrite) {
            return if (bodyChannel.closedCause != null) KodexLibcurl.WRITEFUNC_ERROR else 0.convert()
        }
        if (paused) return KodexLibcurl.WRITEFUNC_PAUSE

        val chunkSize = (size * count).toLong()
        return try {
            bodyChannel.writeBuffer.writeFully(buffer, 0L, chunkSize)
            bodyChannel.flushWriteBuffer()
            if (chunkSize > 0) onNetworkActivity()
            if (!bodyChannel.hasFreeSpace) pauseUntilFreeSpaceAvailable()
            chunkSize.convert()
        } catch (_: Throwable) {
            KodexLibcurl.WRITEFUNC_ERROR
        }
    }

    private fun pauseUntilFreeSpaceAvailable() {
        paused = true
        launch {
            try {
                bodyChannel.awaitFreeSpace()
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                // The next Curl callback observes the write failure.
            } finally {
                paused = false
                onUnpause()
            }
        }
    }

    override fun onNetworkActivity() {
        lastNetworkActivity = TimeSource.Monotonic.markNow()
    }

    fun isSocketTimeoutExpired(socketTimeoutMillis: Long): Boolean {
        if (socketTimeoutMillis == Long.MAX_VALUE) return false
        return lastNetworkActivity.elapsedNow().inWholeMilliseconds >= socketTimeoutMillis
    }

    override fun close(cause: Throwable?) {
        if (bodyChannel.isClosedForWrite) return
        bodyChannel.close(cause)
        cancel(cause as? CancellationException ?: CancellationException(cause))
    }
}
