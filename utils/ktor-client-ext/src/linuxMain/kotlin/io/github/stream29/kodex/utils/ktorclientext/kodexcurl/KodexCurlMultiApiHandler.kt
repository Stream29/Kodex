/*
 * Derived from Ktor's Curl client engine.
 * Copyright 2014-2026 JetBrains s.r.o and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.client.engine.*
import io.ktor.client.network.sockets.SocketTimeoutException
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
import kotlinx.coroutines.DisposableHandle
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import libcurl.*
import platform.posix.getenv
import platform.posix.size_tVar

// Local to Curl release. Identity traversal is bounded even for a supplied
// exception graph that already contains a cycle.
private fun curlFailureGraph(root: Throwable): List<Throwable> {
    val visited = mutableListOf<Throwable>()
    val pending = mutableListOf(root)
    while (pending.isNotEmpty()) {
        val current = pending.removeAt(pending.lastIndex)
        if (visited.any { it === current }) continue
        visited += current
        current.cause?.let { pending += it }
        pending.addAll(current.suppressedExceptions)
    }
    return visited
}

private fun attachCurlCleanup(
    primary: Throwable,
    cleanup: Throwable,
    recorded: MutableList<Pair<Throwable, Throwable>>,
    parent: Throwable = primary,
): Throwable {
    val existing = curlFailureGraph(primary)
    if (existing.any { it === cleanup }) return cleanup
    val remembered = recorded.firstOrNull { it.first === cleanup }?.second
    if (remembered != null && existing.any { it === remembered }) return remembered
    val candidate = remembered ?: cleanup
    val incoming = curlFailureGraph(candidate)
    // An exception whose cause already reaches the primary cannot be attached
    // by identity without a cycle. Preserve its diagnostic context without
    // linking that unsafe graph (or duplicating an already-reachable secondary).
    val secondary = if (incoming.any { node -> existing.any { it === node } }) {
        IOException("Curl cleanup graph overlaps the primary: $cleanup")
    } else candidate
    recorded.removeAll { it.first === cleanup }
    recorded += cleanup to secondary
    parent.addSuppressed(secondary)
    return secondary
}

@OptIn(ExperimentalForeignApi::class)
private class RequestHolder(
    val token: Any,
    val responseCompletable: CompletableDeferred<KodexCurlSuccess>,
    val requestHeaders: CPointer<curl_slist>,
    val responseDataRef: StableRef<KodexCurlResponseBuilder>,
    val requestWrapper: StableRef<KodexCurlRequestBodyData>,
    val responseWrapper: StableRef<KodexCurlResponseBodyData>,
) {
    var cancellationHandler: DisposableHandle? = null

    fun dispose(
        afterRelease: ((String) -> Unit)? = null,
        onFailure: (Throwable) -> Unit,
    ) {
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                onFailure(failure)
            }
        }
        attempt { cancellationHandler?.dispose(); afterRelease?.invoke("cancellationHandler") }
        attempt { curl_slist_free_all(requestHeaders); afterRelease?.invoke("requestHeaders") }
        attempt { responseDataRef.dispose(); afterRelease?.invoke("responseDataRef") }
        attempt { requestWrapper.dispose(); afterRelease?.invoke("requestWrapper") }
        attempt { responseWrapper.dispose(); afterRelease?.invoke("responseWrapper") }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class KodexCurlRequestHandle(
    val easyHandle: EasyHandle,
    val token: Any,
)

// Per-handler test seam. Native operations still execute, and injected release
// faults occur afterwards so the fixture never intentionally leaks a pointer.
internal class KodexCurlNativeTestHooks {
    var afterOperation: ((String) -> Unit)? = null
    var publishHeaders: Boolean = true
    var beforeNativeWakeup: (() -> Unit)? = null
    var nativeWakeup: (() -> Unit)? = null
    var onScheduled: ((KodexCurlRequestHandle, (KodexCurlRequestHandle) -> Unit) -> Unit)? = null
}

@OptIn(InternalAPI::class, ExperimentalForeignApi::class)
internal class KodexCurlMultiApiHandler(
    private val testHooks: KodexCurlNativeTestHooks? = null,
) : Closeable {
    private val activeHandles: MutableMap<EasyHandle, RequestHolder> = mutableMapOf()
    private val cancelledHandles: MutableSet<Pair<EasyHandle, Throwable>> = mutableSetOf()
    private val closed = atomic(false)
    private val nativeLifecycleLock = SynchronizedObject()

    private val multiHandle: MultiHandle = curl_multi_init()
        ?: error("Could not initialize a Curl multi handle")

    init {
        // Keep long-lived streams on separate connections and failure domains.
        try {
            curl_multi_setopt(multiHandle, CURLMOPT_PIPELINING, CURLPIPE_NOTHING).verify()
        } catch (primary: Throwable) {
            try {
                curl_multi_cleanup(multiHandle).verify()
            } catch (cleanup: Throwable) {
                attachCurlCleanup(primary, cleanup, mutableListOf())
            }
            throw primary
        }
    }

    private val easyHandlesToUnpauseLock = SynchronizedObject()
    private val easyHandlesToUnpause: MutableList<KodexCurlRequestHandle> = mutableListOf()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val closeCause = CancellationException("Kodex Curl client engine closed")
        var firstFailure: Throwable? = null
        val recorded = mutableListOf<Pair<Throwable, Throwable>>()
        var holderCleanup: Throwable? = null
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (cause: Throwable) {
                val first = firstFailure
                if (first == null) firstFailure = cause
                else attachCurlCleanup(first, cause, recorded)
            }
        }
        attempt {
            if (activeHandles.isNotEmpty() || cancelledHandles.isNotEmpty()) handleCompleted()
        }
        for ((handle, holder) in activeHandles.toList()) {
            attempt {
                finishRequest(
                    handle, holder, null, closeCause,
                    forbidReuse = true, recorded = recorded, cleanupRoot = holderCleanup,
                )?.let { holderCleanup = it; throw it }
            }
        }

        activeHandles.clear()
        cancelledHandles.clear()
        attempt { testHooks?.afterOperation?.invoke("beforeMultiCleanup") }
        attempt {
            synchronized(nativeLifecycleLock) {
                curl_multi_cleanup(multiHandle).verify()
                testHooks?.afterOperation?.invoke("multiCleanup")
            }
        }
        firstFailure?.let { throw it }
    }

    fun scheduleRequest(
        request: KodexCurlRequestData,
        deferred: CompletableDeferred<KodexCurlSuccess>,
        onCancellation: (KodexCurlRequestHandle, Throwable) -> Unit,
    ) {
        val easyHandle = curl_easy_init()
        if (easyHandle == null) {
            request.dispose()
            error("Could not initialize a Curl easy handle")
        }
        val bodyStartedReceiving = CompletableDeferred<Unit>()
        val requestHandle = KodexCurlRequestHandle(easyHandle, Any())
        val responseBody = try {
            if (request.isUpgradeRequest) {
                val webSocketConfig = request.attributes[WEBSOCKETS_KEY]
                KodexCurlWebSocketResponseBody(
                    easyHandle = easyHandle,
                    incomingFramesConfig = webSocketConfig.channelsConfig.incoming,
                    maxFrameSize = webSocketConfig.maxFrameSize,
                )
            } else {
                KodexCurlHttpResponseBody(request.callContext) {
                    unpauseEasyHandle(requestHandle)
                }
            }
        } catch (primary: Throwable) {
            curl_easy_cleanup(easyHandle)
            try {
                request.dispose()
            } catch (cleanup: Throwable) {
                attachCurlCleanup(primary, cleanup, mutableListOf())
            }
            throw primary
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
                onUnpause = { unpauseEasyHandle(requestHandle) },
                onNetworkActivity = responseBody::onNetworkActivity,
            )
            requestWrapperRef = StableRef.create(requestBody)
            val requestWrapperPointer = checkNotNull(requestWrapperRef).asCPointer()
            requestHeaders = request.takeHeaders()
            val requestHeadersPointer = checkNotNull(requestHeaders)
            val holder = RequestHolder(
                token = requestHandle.token,
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
                if (testHooks?.publishHeaders == false) return@invokeOnCompletion
                val activeHolder = activeHandles[easyHandle] ?: return@invokeOnCompletion
                val result = collectSuccessResponse(easyHandle, responseData) ?: return@invokeOnCompletion
                activeHolder.responseCompletable.complete(result)
            }
            activeHandles[easyHandle] = holder
            holder.cancellationHandler = request.callContext.invokeOnCompletion { cause ->
                if (cause != null) onCancellation(requestHandle, cause)
            }

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
            testHooks?.onScheduled?.invoke(requestHandle, ::unpauseEasyHandle)
        } catch (cause: Throwable) {
            activeHandles.remove(easyHandle)
            val recorded = mutableListOf<Pair<Throwable, Throwable>>()
            fun release(block: () -> Unit) {
                try {
                    block()
                } catch (cleanup: Throwable) {
                    attachCurlCleanup(cause, cleanup, recorded)
                }
            }
            release { closeResponse(responseData, cause) }
            release { if (addedToMulti) cleanupEasyHandle(easyHandle) else curl_easy_cleanup(easyHandle) }
            val holder = requestHolder
            if (holder != null) {
                release {
                    holder.dispose(onFailure = { cleanup -> attachCurlCleanup(cause, cleanup, recorded) })
                }
            } else {
                release { requestHeaders?.let(::curl_slist_free_all) }
                release { request.dispose() }
                release { responseDataRef?.dispose() }
                release { requestWrapperRef?.dispose() }
                release { responseWrapperRef?.dispose() }
            }
            throw cause
        }
    }

    fun cancelRequest(request: KodexCurlRequestHandle, cause: Throwable) {
        if (closed.value) return
        val holder = activeHandles[request.easyHandle] ?: return
        if (holder.token !== request.token) return
        cancelledHandles += request.easyHandle to cause
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
                val holder = activeHandles[handle.easyHandle]
                if (holder?.token === handle.token) {
                    curl_easy_pause(handle.easyHandle, CURLPAUSE_CONT)
                    testHooks?.afterOperation?.invoke("easyUnpause")
                } else {
                    testHooks?.afterOperation?.invoke("staleUnpauseDiscarded")
                }
                handle = easyHandlesToUnpause.removeFirstOrNull()
            }
        }
        curl_multi_perform(multiHandle, transfersRunning.ptr).verify()
        handleSocketTimeouts()
        if (activeHandles.isEmpty()) return
        if (transfersRunning.value != 0) {
            curl_multi_poll(multiHandle, null, 0.toUInt(), pollTimeout, null).verify()
        }
        handleSocketTimeouts()
        if (activeHandles.isEmpty()) return
        if (transfersRunning.value < activeHandles.size) handleCompleted()
    }

    fun hasHandlers(): Boolean = activeHandles.isNotEmpty()

    fun wakeup() {
        synchronized(nativeLifecycleLock) {
            if (!closed.value) {
                // Gate the actual check/use boundary, not an earlier queue
                // check: a second atomic check cannot exclude native release.
                testHooks?.beforeNativeWakeup?.invoke()
                nativeWakeup()
            }
        }
    }

    private fun nativeWakeup() {
        val intercepted = testHooks?.nativeWakeup
        if (intercepted == null) curl_multi_wakeup(multiHandle) else intercepted()
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
                val result = processCompletedEasyHandle(
                    message = message.msg,
                    easyHandle = easyHandle,
                    result = message.data.result,
                    holder = holder,
                )
                finishRequest(easyHandle, holder, result, (result as? KodexCurlFail)?.cause)
            } while (messagesLeft.value != 0)
        }
    }

    private fun removeEasyHandle(easyHandle: EasyHandle, cause: Throwable) {
        val holder = activeHandles[easyHandle] ?: return
        finishRequest(easyHandle, holder, null, cause, forbidReuse = true)
    }

    private fun finishRequest(
        easyHandle: EasyHandle,
        holder: RequestHolder,
        result: KodexCurlResponseData?,
        cause: Throwable?,
        forbidReuse: Boolean = false,
        recorded: MutableList<Pair<Throwable, Throwable>> = mutableListOf(),
        cleanupRoot: Throwable? = null,
    ): Throwable? {
        // Keep this actual holder reachable until its promise has a terminal
        // outcome. A returned CurlFail is just as primary as a thrown getInfo.
        var primary = cause
        var cleanupFailed = false
        var firstCleanup = cleanupRoot
        fun recordCleanup(cleanup: Throwable) {
            cleanupFailed = true
            val original = primary
            if (original == null) {
                primary = cleanup
                firstCleanup = cleanup
            } else {
                val attached = attachCurlCleanup(original, cleanup, recorded, firstCleanup ?: original)
                if (firstCleanup == null) firstCleanup = attached
            }
        }
        fun attempt(operation: String, block: () -> Unit) {
            try {
                block()
                testHooks?.afterOperation?.invoke(operation)
            } catch (cleanup: Throwable) {
                recordCleanup(cleanup)
            }
        }
        val builder = holder.responseDataRef.get()
        if (forbidReuse) attempt("forbidReuse") { easyHandle.option(CURLOPT_FORBID_REUSE, 1L) }
        attempt("multiRemove") { curl_multi_remove_handle(multiHandle, easyHandle).verify() }
        attempt("easyCleanup") { curl_easy_cleanup(easyHandle) }
        attempt("responseHeaders") { builder.headersBytes.close() }
        // StableRefs and request headers remain alive through easy cleanup.
        attempt("holderDispose") { holder.dispose(testHooks?.afterOperation, ::recordCleanup) }
        // Do not publish successful EOF before a native release failure is known.
        // A synthetic body-close fault still performs the real close with it.
        attempt("beforeBodyClose") {}
        attempt("bodyClose") { builder.responseBody.close(primary) }
        try {
            val failure = primary
            if (failure != null) holder.responseCompletable.completeExceptionally(failure)
            else holder.responseCompletable.complete(checkNotNull(result) as KodexCurlSuccess)
        } catch (completionFailure: Throwable) {
            // Completion handlers can throw after the Deferred has settled.
            // Do not leave disposed references reachable for a second release.
            recordCleanup(completionFailure)
            if (!holder.responseCompletable.isCompleted) {
                holder.responseCompletable.completeExceptionally(checkNotNull(primary))
            }
        } finally {
            activeHandles.remove(easyHandle)
        }
        val failure = primary
        // Failure results/cancellation belong to this request, not the loop.
        // A previously successful operation's cleanup must remain observable.
        if (cause == null && failure != null) throw failure
        // One cleanup subtree is shared with API close. Later failures attach
        // only there, never both there and directly on the admitted primary.
        return if (cleanupFailed) firstCleanup else null
    }

    private fun processCompletedEasyHandle(
        message: CURLMSG?,
        easyHandle: EasyHandle,
        result: CURLcode,
        holder: RequestHolder,
    ): KodexCurlResponseData {
        val responseBuilder = holder.responseDataRef.get()
        try {
            val completed = memScoped {
                val httpStatusCode = alloc<LongVar>()
                val proxyCode = alloc<CURLproxycode.Var>()
                easyHandle.apply {
                    getInfo(CURLINFO_RESPONSE_CODE, httpStatusCode.ptr)
                    getInfo(CURLINFO_PROXY_ERROR, proxyCode.ptr)
                }
                testHooks?.afterOperation?.invoke("completedGetInfo")
                collectFailedResponse(
                    message = message,
                    request = responseBuilder.request,
                    result = result,
                    httpStatusCode = httpStatusCode.value,
                    proxyCode = proxyCode.value,
                ) ?: checkNotNull(collectSuccessResponse(easyHandle, responseBuilder))
            }
            return completed
        } catch (failure: Throwable) {
            return KodexCurlFail(failure)
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
        if (result == CURLE_OK) return null
        if (result == CURLE_OPERATION_TIMEDOUT) {
            return KodexCurlFail(if (httpStatusCode == 0L) {
                ConnectTimeoutException(request.url, request.connectTimeout)
            } else {
                SocketTimeoutException(
                    message = "Socket timeout has expired [url=${request.url}, socket_timeout=${request.socketTimeout} ms]",
                    cause = null,
                )
            })
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
            IOException("Connection failed for request: $request. Reason: $errorMessage"),
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

    private fun handleSocketTimeouts() {
        val timedOut = activeHandles.mapNotNull { (easyHandle, holder) ->
            val request = holder.responseDataRef.get().request
            val timeout = request.socketTimeout ?: return@mapNotNull null
            val responseBody = holder.responseWrapper.get() as? KodexCurlHttpResponseBody
                ?: return@mapNotNull null
            if (!responseBody.isSocketTimeoutExpired(timeout)) return@mapNotNull null
            easyHandle to SocketTimeoutException(
                message = "Socket timeout has expired [url=${request.url}, socket_timeout=${timeout} ms]",
                cause = null,
            )
        }
        for ((easyHandle, cause) in timedOut) {
            removeEasyHandle(easyHandle, cause)
        }
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

    private fun unpauseEasyHandle(request: KodexCurlRequestHandle) {
        if (closed.value) return
        synchronized(easyHandlesToUnpauseLock) {
            if (!closed.value) easyHandlesToUnpause.add(request)
        }
        wakeup()
    }

    private fun cleanupEasyHandle(easyHandle: EasyHandle) {
        try {
            curl_multi_remove_handle(multiHandle, easyHandle).verify()
        } finally {
            curl_easy_cleanup(easyHandle)
        }
    }

    private companion object {
        private const val DefaultPollTimeoutMs: Int = 100
        val pollTimeout: Int by lazy {
            getenv("KTOR_CURL_POLL_TIMEOUT")?.toKString()?.toInt() ?: DefaultPollTimeoutMs
        }
    }
}
