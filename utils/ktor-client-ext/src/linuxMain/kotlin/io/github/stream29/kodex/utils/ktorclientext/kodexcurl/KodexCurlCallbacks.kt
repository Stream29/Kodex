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
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import platform.posix.size_t
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalForeignApi::class)
internal fun onKodexCurlHeadersReceived(
    buffer: CPointer<ByteVar>,
    size: size_t,
    count: size_t,
    userdata: COpaquePointer,
): size_t {
    val response = userdata.fromCPointer<KodexCurlResponseBuilder>()
    val chunkSize = (size * count).toLong()
    if (chunkSize > 0) response.responseBody.onNetworkActivity()
    response.headersBytes.writeFully(buffer, 0, chunkSize)

    if (isFinalHeaderLine(chunkSize, buffer) && !response.bodyStartedReceiving.isCompleted) {
        response.bodyStartedReceiving.complete(Unit)
    }

    return chunkSize.convert()
}

@OptIn(ExperimentalForeignApi::class)
private fun isFinalHeaderLine(chunkSize: Long, buffer: CPointer<ByteVar>): Boolean =
    chunkSize == 2L && buffer[0] == 0x0D.toByte() && buffer[1] == 0x0A.toByte()

@OptIn(ExperimentalForeignApi::class)
internal fun onKodexCurlBodyChunkReceived(
    buffer: CPointer<ByteVar>,
    size: size_t,
    count: size_t,
    userdata: COpaquePointer,
): size_t {
    val wrapper = userdata.fromCPointer<KodexCurlResponseBodyData>()
    return wrapper.onBodyChunkReceived(buffer, size, count)
}

@OptIn(ExperimentalForeignApi::class)
internal fun onKodexCurlBodyChunkRequested(
    buffer: CPointer<ByteVar>,
    size: size_t,
    count: size_t,
    dataRef: COpaquePointer,
): size_t {
    val wrapper: KodexCurlRequestBodyData = dataRef.fromCPointer()
    val body = wrapper.body
    val requested = (size * count).toInt()

    if (body.isClosedForRead) {
        return if (body.closedCause != null) KodexLibcurl.READFUNC_ABORT else 0.convert()
    }
    val readCount = try {
        body.readAvailable(1) { source: Buffer ->
            source.readAvailable(buffer, 0, requested)
        }
    } catch (_: Throwable) {
        return KodexLibcurl.READFUNC_ABORT
    }
    if (readCount > 0) {
        wrapper.onNetworkActivity()
        return readCount.convert()
    }

    CoroutineScope(wrapper.callContext).launch {
        try {
            body.awaitContent()
        } catch (_: Throwable) {
            // The next Curl callback observes the read failure.
        } finally {
            wrapper.onUnpause()
        }
    }
    return KodexLibcurl.READFUNC_PAUSE
}

internal class KodexCurlRequestBodyData(
    val body: ByteReadChannel,
    val callContext: CoroutineContext,
    val onUnpause: () -> Unit,
    val onNetworkActivity: () -> Unit,
)

internal interface KodexCurlResponseBodyData {
    @OptIn(ExperimentalForeignApi::class)
    fun onBodyChunkReceived(buffer: CPointer<ByteVar>, size: size_t, count: size_t): size_t

    fun onNetworkActivity() {}

    fun close(cause: Throwable? = null)
}
