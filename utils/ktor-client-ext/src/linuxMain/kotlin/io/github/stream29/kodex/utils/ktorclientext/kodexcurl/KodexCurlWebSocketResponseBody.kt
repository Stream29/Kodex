/*
 * Derived from Ktor's Curl client engine.
 * Copyright 2014-2026 JetBrains s.r.o and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import libcurl.*
import platform.posix.size_t

@OptIn(InternalAPI::class, ExperimentalForeignApi::class)
internal class KodexCurlWebSocketResponseBody(
    internal val easyHandle: EasyHandle,
    incomingFramesConfig: ChannelConfig,
    var maxFrameSize: Long,
) : KodexCurlResponseBodyData {
    private val closed = atomic(false)
    private val incomingChannel = run {
        require(!incomingFramesConfig.canSuspend) {
            "Curl Client does not support SUSPEND overflow strategy for incoming channel"
        }
        Channel.from<Frame>(incomingFramesConfig)
    }

    val incoming: ReceiveChannel<Frame>
        get() = incomingChannel

    private var pendingException: Throwable? = null
    private var frameDataBuffer: Buffer? = null

    override fun onBodyChunkReceived(buffer: CPointer<ByteVar>, size: size_t, count: size_t): size_t {
        if (closed.value) return 0.convert()

        val metadata = curl_ws_meta(easyHandle)?.pointed ?: return KodexLibcurl.WRITEFUNC_ERROR
        val chunkSize = metadata.len.toInt()
        val chunkData = buffer.readBytes(chunkSize)

        return if (processFrameChunk(chunkData, metadata)) chunkSize.convert() else KodexLibcurl.WRITEFUNC_ERROR
    }

    private fun processFrameChunk(chunk: ByteArray, metadata: curl_ws_frame): Boolean {
        val flags = metadata.flags
        return if (isControlFrame(flags)) {
            handleIncomingFrame(controlFrame(chunk, flags))
        } else {
            handleDataFrameChunk(chunk, metadata)
        }
    }

    private fun isControlFrame(flags: Int): Boolean =
        (flags and (CURLWS_PING or CURLWS_PONG or CURLWS_CLOSE)) != 0

    private fun handleDataFrameChunk(chunk: ByteArray, metadata: curl_ws_frame): Boolean {
        val flags = metadata.flags
        val offset = metadata.offset
        val bytesLeft = metadata.bytesleft
        val totalFrameSize = offset + chunk.size + bytesLeft
        if (totalFrameSize > maxFrameSize) {
            frameDataBuffer = null
            pendingException = FrameTooBigException(totalFrameSize)
            return false
        }

        if (offset == 0L && bytesLeft == 0L) {
            return handleIncomingFrame(dataFrame(chunk, flags))
        }
        if (offset == 0L) frameDataBuffer = Buffer()

        val frameBuffer = frameDataBuffer ?: return false
        frameBuffer.write(chunk)
        if (bytesLeft == 0L) {
            val data = frameBuffer.readByteArray()
            frameDataBuffer = null
            return handleIncomingFrame(dataFrame(data, flags))
        }
        return true
    }

    private fun handleIncomingFrame(frame: Frame?): Boolean =
        if (frame != null) incomingChannel.trySend(frame).isSuccess else false

    override fun close(cause: Throwable?) {
        if (!closed.compareAndSet(expect = false, update = true)) return
        frameDataBuffer = null
        incomingChannel.close(pendingException ?: cause)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun controlFrame(data: ByteArray, flags: Int): Frame? = when {
    (flags and CURLWS_PING != 0) -> Frame.Ping(data)
    (flags and CURLWS_PONG != 0) -> Frame.Pong(data)
    (flags and CURLWS_CLOSE != 0) -> Frame.Close(data)
    else -> null
}

@OptIn(ExperimentalForeignApi::class)
private fun dataFrame(data: ByteArray, flags: Int): Frame? {
    val isFinal = (flags and CURLWS_CONT) == 0
    return when {
        (flags and CURLWS_BINARY != 0) -> Frame.Binary(fin = isFinal, data = data)
        (flags and CURLWS_TEXT != 0) -> Frame.Text(fin = isFinal, data = data)
        else -> null
    }
}
