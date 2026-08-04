/*
 * Derived from Ktor's Curl client engine.
 * Copyright 2014-2026 JetBrains s.r.o and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.locks.*
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import libcurl.*
import platform.posix.getenv
import platform.posix.size_tVar

@OptIn(ExperimentalForeignApi::class)
private class RequestHolder(
    val responseCompletable: CompletableDeferred<KodexCurlSuccess>,
    val requestHeaders: CPointer<curl_slist>,
    val responseDataRef: StableRef<KodexCurlResponseBuilder>,
    val requestWrapper: StableRef<KodexCurlRequestBodyData>,
    val responseWrapper: StableRef<KodexCurlResponseBodyData>,
) {
    fun dispose() {
        curl_slist_free_all(requestHeaders)
        responseDataRef.dispose()
        requestWrapper.dispose()
        responseWrapper.dispose()
    }
}

@OptIn(InternalAPI::class, ExperimentalForeignApi::class)
internal class KodexCurlMultiApiHandler : Closeable {
    private val activeHandles: MutableMap<EasyHandle, RequestHolder> = mutableMapOf()
    private val cancelledHandles: MutableSet<Pair<EasyHandle, Throwable>> = mutableSetOf()
    private val closed = atomic(false)

    private val multiHandle: MultiHandle = curl_multi_init()
        ?: error("Could not initialize a Curl multi handle")

    private val easyHandlesToUnpauseLock = SynchronizedObject()
    private val easyHandlesToUnpause: MutableList<EasyHandle> = mutableListOf()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (activeHandles.isNotEmpty() || cancelledHandles.isNotEmpty()) handleCompleted()

        val closeCause = CancellationException("Kodex Curl client engine closed")
        var firstFailure: Throwable? = null
        for ((handle, holder) in activeHandles) {
            try {
                closeResponse(holder.responseDataRef.get(), closeCause)
                cleanupEasyHandle(handle)
            } catch (cause: Throwable) {
                if (firstFailure == null) firstFailure = cause
            } finally {
                holder.responseCompletable.completeExceptionally(closeCause)
                holder.dispose()
            }
        }

        activeHandles.clear()
        cancelledHandles.clear()
        try {
            curl_multi_cleanup(multiHandle).verify()
        } catch (cause: Throwable) {
            if (firstFailure == null) firstFailure = cause
        }
        firstFailure?.let { throw it }
    }

    fun scheduleRequest(
        request: KodexCurlRequestData,
        deferred: CompletableDeferred<KodexCurlSuccess>,
    ): EasyHandle {
        val easyHandle = curl_easy_init()
        if (easyHandle == null) {
            request.dispose()
            error("Could not initialize a Curl easy handle")
        }
        val bodyStartedReceiving = CompletableDeferred<Unit>()
        val responseBody = if (request.isUpgradeRequest) {
            val webSocketConfig = request.attributes[WEBSOCKETS_KEY]
            KodexCurlWebSocketResponseBody(
                easyHandle = easyHandle,
                incomingFramesConfig = webSocketConfig.channelsConfig.incoming,
                maxFrameSize = webSocketConfig.maxFrameSize,
            )
        } else {
            KodexCurlHttpResponseBody(request.callContext) {
                unpauseEasyHandle(easyHandle)
            }
        }
        val responseData = KodexCurlResponseBuilder(request, bodyStartedReceiving, responseBody)
        var responseDataRef: StableRef<KodexCurlResponseBuilder>? = null
        var requestWrapperRef: StableRef<KodexCurlRequestBodyData>? = null
        var responseWrapperRef: StableRef<KodexCurlResponseBodyData>? = null
        var requestHeaders: CPointer<curl_slist>? = null
        var requestHolder: RequestHolder? = null
        var addedToMulti = false

        try {
            responseDataRef = StableRef.create(responseData)
            val responseDataPointer = checkNotNull(responseDataRef).asCPointer()
            responseWrapperRef = StableRef.create(responseBody)
            val responseWrapperPointer = checkNotNull(responseWrapperRef).asCPointer()
            val requestBody = KodexCurlRequestBodyData(
                body = request.content,
                callContext = request.callContext,
                onUnpause = { unpauseEasyHandle(easyHandle) },
            )
            requestWrapperRef = StableRef.create(requestBody)
            val requestWrapperPointer = checkNotNull(requestWrapperRef).asCPointer()
            requestHeaders = request.takeHeaders()
            val requestHeadersPointer = checkNotNull(requestHeaders)
            val holder = RequestHolder(
                responseCompletable = deferred,
                requestHeaders = requestHeadersPointer,
                responseDataRef = checkNotNull(responseDataRef),
                requestWrapper = checkNotNull(requestWrapperRef),
                responseWrapper = checkNotNull(responseWrapperRef),
            )
            requestHolder = holder
            responseDataRef = null
            requestWrapperRef = null
            responseWrapperRef = null
            requestHeaders = null

            bodyStartedReceiving.invokeOnCompletion {
                val activeHolder = activeHandles[easyHandle] ?: return@invokeOnCompletion
                val result = collectSuccessResponse(easyHandle, responseData) ?: return@invokeOnCompletion
                activeHolder.responseCompletable.complete(result)
            }
            activeHandles[easyHandle] = holder

            setupMethod(easyHandle, request.method, request.contentLength)
            easyHandle.apply {
                option(CURLOPT_READDATA, requestWrapperPointer)
                option(CURLOPT_READFUNCTION, staticCFunction(::onKodexCurlBodyChunkRequested))
                option(CURLOPT_URL, request.url)
                option(CURLOPT_HTTPHEADER, requestHeadersPointer)
                option(CURLOPT_HEADERFUNCTION, staticCFunction(::onKodexCurlHeadersReceived))
                option(CURLOPT_HEADERDATA, responseDataPointer)
                option(CURLOPT_WRITEFUNCTION, staticCFunction(::onKodexCurlBodyChunkReceived))
                option(CURLOPT_WRITEDATA, responseWrapperPointer)
                option(CURLOPT_ACCEPT_ENCODING, "")
                request.connectTimeout?.let { timeout ->
                    option(
                        CURLOPT_CONNECTTIMEOUT_MS,
                        if (timeout == HttpTimeoutConfig.INFINITE_TIMEOUT_MS) Long.MAX_VALUE else timeout,
                    )
                }
                request.proxy?.let { proxy ->
                    option(CURLOPT_PROXY, fixProxyUrl(proxy.toString(), proxy.type))
                    option(CURLOPT_SUPPRESS_CONNECT_HEADERS, 1L)
                    if (request.forceProxyTunneling) option(CURLOPT_HTTPPROXYTUNNEL, 1L)
                }
                if (!request.sslVerify) {
                    option(CURLOPT_SSL_VERIFYPEER, 0L)
                    option(CURLOPT_SSL_VERIFYHOST, 0L)
                }
                request.caPath?.let { option(CURLOPT_CAPATH, it) }
                request.caInfo?.let { option(CURLOPT_CAINFO, it) }
            }
            curl_multi_add_handle(multiHandle, easyHandle).verify()
            addedToMulti = true
        } catch (cause: Throwable) {
            activeHandles.remove(easyHandle)
            try {
                closeResponse(responseData, cause)
            } finally {
                try {
                    if (addedToMulti) cleanupEasyHandle(easyHandle) else curl_easy_cleanup(easyHandle)
                } finally {
                    val holder = requestHolder
                    if (holder != null) {
                        holder.dispose()
                    } else {
                        requestHeaders?.let(::curl_slist_free_all)
                        request.dispose()
                        responseDataRef?.dispose()
                        requestWrapperRef?.dispose()
                        responseWrapperRef?.dispose()
                    }
                }
            }
            throw cause
        }

        return easyHandle
    }

    fun cancelRequest(easyHandle: EasyHandle, cause: Throwable) {
        if (closed.value) return
        cancelledHandles += easyHandle to cause
    }

    fun cancelWebSocket(websocket: KodexCurlWebSocketResponseBody, cause: Throwable) {
        val easyHandle = websocket.easyHandle
        val holder = activeHandles[easyHandle] ?: return
        if (holder.responseWrapper.get() !== websocket) return
        removeEasyHandle(easyHandle, cause)
    }

    fun perform(transfersRunning: IntVarOf<Int>) {
        if (activeHandles.isEmpty()) return
        if (cancelledHandles.isNotEmpty()) handleCompleted()
        if (activeHandles.isEmpty()) return

        synchronized(easyHandlesToUnpauseLock) {
            var handle = easyHandlesToUnpause.removeFirstOrNull()
            while (handle != null) {
                if (handle in activeHandles) curl_easy_pause(handle, CURLPAUSE_CONT)
                handle = easyHandlesToUnpause.removeFirstOrNull()
            }
        }
        curl_multi_perform(multiHandle, transfersRunning.ptr).verify()
        if (transfersRunning.value != 0) {
            curl_multi_poll(multiHandle, null, 0.toUInt(), pollTimeout, null).verify()
        }
        if (transfersRunning.value < activeHandles.size) handleCompleted()
    }

    fun hasHandlers(): Boolean = activeHandles.isNotEmpty()

    fun wakeup() {
        if (closed.value) return
        curl_multi_wakeup(multiHandle)
    }

    fun sendWebSocketFrame(
        websocket: KodexCurlWebSocketResponseBody,
        flags: Int,
        data: ByteArray,
        completionHandler: CompletableJob,
    ) {
        try {
            trySendWebSocketFrame(websocket.easyHandle, flags, data)
            completionHandler.complete()
        } catch (cause: Throwable) {
            completionHandler.completeExceptionally(cause)
        }
    }

    private fun trySendWebSocketFrame(
        easyHandle: EasyHandle,
        flags: Int,
        data: ByteArray,
    ) = memScoped {
        var offset = 0
        val sent = alloc<size_tVar>()
        data.usePinned { pinned ->
            while (true) {
                val bufferStart = if (data.isNotEmpty()) pinned.addressOf(offset) else null
                val remaining = if (data.isNotEmpty()) data.size - offset else 0
                val status = curl_ws_send(
                    curl = easyHandle,
                    buffer_arg = bufferStart,
                    buflen = remaining.convert(),
                    sent = sent.ptr,
                    fragsize = 0,
                    flags = flags.convert(),
                )
                when (status) {
                    CURLE_OK -> {
                        offset += sent.value.toInt()
                        if (data.isEmpty() || offset == data.size) break
                    }

                    else -> status.verify()
                }
            }
        }
    }

    private fun handleCompleted() {
        for ((easyHandle, cause) in cancelledHandles) {
            removeEasyHandle(easyHandle, cause)
        }
        cancelledHandles.clear()

        memScoped {
            do {
                val messagesLeft = alloc<IntVar>()
                val message = curl_multi_info_read(multiHandle, messagesLeft.ptr)?.pointed ?: continue
                val easyHandle = message.easy_handle ?: error("Curl completed a null easy handle")
                val holder = activeHandles[easyHandle] ?: continue
                try {
                    val result = processCompletedEasyHandle(
                        message = message.msg,
                        easyHandle = easyHandle,
                        result = message.data.result,
                        holder = holder,
                    )
                    if (!holder.responseCompletable.isCompleted) {
                        when (result) {
                            is KodexCurlSuccess -> holder.responseCompletable.complete(result)
                            is KodexCurlFail -> holder.responseCompletable.completeExceptionally(result.cause)
                        }
                    }
                } finally {
                    activeHandles.remove(easyHandle)?.dispose()
                }
            } while (messagesLeft.value != 0)
        }
    }

    private fun removeEasyHandle(easyHandle: EasyHandle, cause: Throwable) {
        val holder = activeHandles.remove(easyHandle) ?: return
        try {
            processCancelledEasyHandle(easyHandle, holder, cause)
        } finally {
            holder.responseCompletable.completeExceptionally(cause)
            holder.dispose()
        }
    }

    private fun processCancelledEasyHandle(
        easyHandle: EasyHandle,
        holder: RequestHolder,
        cause: Throwable,
    ) {
        try {
            closeResponse(holder.responseDataRef.get(), cause)
        } finally {
            cleanupEasyHandle(easyHandle)
        }
    }

    private fun processCompletedEasyHandle(
        message: CURLMSG?,
        easyHandle: EasyHandle,
        result: CURLcode,
        holder: RequestHolder,
    ): KodexCurlResponseData {
        val responseBuilder = holder.responseDataRef.get()
        try {
            return memScoped {
                val httpStatusCode = alloc<LongVar>()
                val proxyCode = alloc<CURLproxycode.Var>()
                easyHandle.apply {
                    getInfo(CURLINFO_RESPONSE_CODE, httpStatusCode.ptr)
                    getInfo(CURLINFO_PROXY_ERROR, proxyCode.ptr)
                }
                collectFailedResponse(
                    message = message,
                    request = responseBuilder.request,
                    result = result,
                    httpStatusCode = httpStatusCode.value,
                    proxyCode = proxyCode.value,
                ) ?: checkNotNull(collectSuccessResponse(easyHandle, responseBuilder))
            }
        } finally {
            try {
                closeResponse(responseBuilder)
            } finally {
                cleanupEasyHandle(easyHandle)
            }
        }
    }

    private fun collectFailedResponse(
        message: CURLMSG?,
        request: KodexCurlRequestData,
        result: CURLcode,
        httpStatusCode: Long,
        proxyCode: CURLproxycode,
    ): KodexCurlFail? {
        if (message != CURLMSG.CURLMSG_DONE) {
            return KodexCurlFail(IllegalStateException("Request $request failed: $message"))
        }
        if (httpStatusCode != 0L) return null
        if (result == CURLE_OPERATION_TIMEDOUT) {
            return KodexCurlFail(ConnectTimeoutException(request.url, request.connectTimeout))
        }

        val errorMessage = result.errorMessage
        if (result == CURLE_PEER_FAILED_VERIFICATION) {
            return KodexCurlFail(
                IllegalStateException(
                    "TLS verification failed for request: $request. Reason: $errorMessage",
                ),
            )
        }
        if (result == CURLE_PROXY && proxyCode != CURLproxycode.CURLPX_OK) {
            return KodexCurlFail(
                IllegalStateException("Proxy handshake error for request: $request. Reason: $proxyCode"),
            )
        }
        return KodexCurlFail(
            IllegalStateException("Connection failed for request: $request. Reason: $errorMessage"),
        )
    }

    private fun collectSuccessResponse(
        easyHandle: EasyHandle,
        responseBuilder: KodexCurlResponseBuilder,
    ): KodexCurlSuccess? = memScoped {
        val httpProtocolVersion = alloc<LongVar>()
        val httpStatusCode = alloc<LongVar>()
        easyHandle.apply {
            getInfo(CURLINFO_RESPONSE_CODE, httpStatusCode.ptr)
            getInfo(CURLINFO_HTTP_VERSION, httpProtocolVersion.ptr)
        }
        if (httpStatusCode.value == 0L) return@memScoped null

        KodexCurlSuccess(
            status = httpStatusCode.value.toInt(),
            version = httpProtocolVersion.value,
            headersBytes = responseBuilder.headersBytes.build().readByteArray(),
            responseBody = responseBuilder.responseBody,
        )
    }

    private fun setupMethod(easyHandle: EasyHandle, method: String, size: Long) {
        easyHandle.apply {
            when (method) {
                "GET" -> option(CURLOPT_HTTPGET, 1L)
                "PUT" -> {
                    option(CURLOPT_PUT, 1L)
                    option(CURLOPT_INFILESIZE_LARGE, size)
                }

                "POST" -> {
                    option(CURLOPT_POST, 1L)
                    option(CURLOPT_POSTFIELDSIZE_LARGE, size)
                }

                "HEAD" -> option(CURLOPT_NOBODY, 1L)
                else -> {
                    if (size > 0) {
                        option(CURLOPT_POST, 1L)
                        option(CURLOPT_POSTFIELDSIZE_LARGE, size)
                    }
                    option(CURLOPT_CUSTOMREQUEST, method)
                }
            }
        }
    }

    private fun fixProxyUrl(url: String, proxyType: ProxyType): String =
        if (proxyType == ProxyType.SOCKS) url.replaceFirst("socks://", "socks5h://") else url

    private fun closeResponse(responseBuilder: KodexCurlResponseBuilder, cause: Throwable? = null) {
        try {
            responseBuilder.responseBody.close(cause)
        } finally {
            responseBuilder.headersBytes.close()
        }
    }

    private fun unpauseEasyHandle(easyHandle: EasyHandle) {
        if (closed.value) return
        synchronized(easyHandlesToUnpauseLock) {
            if (!closed.value) easyHandlesToUnpause.add(easyHandle)
        }
        if (!closed.value) curl_multi_wakeup(multiHandle)
    }

    private fun cleanupEasyHandle(easyHandle: EasyHandle) {
        curl_multi_remove_handle(multiHandle, easyHandle).verify()
        curl_easy_cleanup(easyHandle)
    }

    private companion object {
        private const val DefaultPollTimeoutMs: Int = 100
        val pollTimeout: Int by lazy {
            getenv("KTOR_CURL_POLL_TIMEOUT")?.toKString()?.toInt() ?: DefaultPollTimeoutMs
        }
    }
}
