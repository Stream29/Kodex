/*
 * Derived from Ktor's Curl client engine.
 * Copyright 2014-2026 JetBrains s.r.o and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalForeignApi::class)
internal class KodexCurlProcessor(coroutineContext: CoroutineContext) {
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private val curlDispatcher = newSingleThreadContext("kodex-curl-dispatcher")

    private var curlApi: KodexCurlMultiApiHandler? by atomic(null)
    private val closed = atomic(false)
    private val curlScope = CoroutineScope(coroutineContext + curlDispatcher)
    private val taskQueue: Channel<KodexCurlTask> = Channel(Channel.UNLIMITED)

    init {
        val initialize = curlScope.launch {
            curlApi = KodexCurlMultiApiHandler()
        }
        runBlocking {
            initialize.join()
        }
        runEventLoop().invokeOnCompletion { cause ->
            cause?.let {
                curlScope.cancel(
                    cause = cause as? CancellationException ?: CancellationException(cause),
                )
            }
        }
    }

    suspend fun executeRequest(request: KodexCurlRequestData): KodexCurlSuccess {
        val result = CompletableDeferred<KodexCurlSuccess>()
        try {
            taskQueue.send(KodexCurlTask.SendRequest(request, result))
        } catch (cause: Throwable) {
            request.dispose()
            throw cause
        }
        curlApi!!.wakeup()
        return result.await()
    }

    suspend fun sendWebSocketFrame(websocket: KodexCurlWebSocketResponseBody, flags: Int, data: ByteArray) {
        val result = Job()
        taskQueue.send(KodexCurlTask.SendWebSocketFrame(websocket, flags, data, result))
        curlApi!!.wakeup()
        result.join()
    }

    fun cancelWebSocket(websocket: KodexCurlWebSocketResponseBody) {
        val sent = taskQueue.trySend(KodexCurlTask.CancelWebSocket(websocket))
        if (sent.isSuccess) curlApi!!.wakeup()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun runEventLoop(): Job = curlScope.launch(CoroutineName("kodex-curl-processor-loop")) {
        memScoped {
            val transfersRunning = alloc<IntVar>()
            val api = curlApi!!
            while (!taskQueue.isClosedForReceive) {
                drainTaskQueue(api)
                api.perform(transfersRunning)
            }
        }
    }

    private suspend fun drainTaskQueue(api: KodexCurlMultiApiHandler) {
        while (true) {
            val task = if (api.hasHandlers()) {
                taskQueue.tryReceive()
            } else {
                taskQueue.receiveCatching()
            }.getOrNull() ?: break

            when (task) {
                is KodexCurlTask.SendRequest -> handleSendRequest(api, task)
                is KodexCurlTask.SendWebSocketFrame ->
                    api.sendWebSocketFrame(task.websocket, task.flags, task.data, task.completionHandler)
                is KodexCurlTask.CancelWebSocket ->
                    api.cancelWebSocket(task.websocket, CancellationException("WebSocket session closed"))
            }
        }
    }

    private fun handleSendRequest(api: KodexCurlMultiApiHandler, task: KodexCurlTask.SendRequest) {
        val requestHandler = api.scheduleRequest(task.requestData, task.completionHandler)
        val requestCleaner = task.requestData.callContext.invokeOnCompletion { cause ->
            if (cause != null) cancelRequest(requestHandler, cause)
        }
        task.completionHandler.invokeOnCompletion {
            requestCleaner.dispose()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun close() {
        if (!closed.compareAndSet(false, true)) return

        taskQueue.close()
        curlApi!!.wakeup()

        GlobalScope.launch(curlDispatcher) {
            curlScope.coroutineContext[Job]!!.join()
            val closeCause = CancellationException("Kodex Curl client engine closed")
            while (true) {
                val task = taskQueue.tryReceive().getOrNull() ?: break
                task.cancel(closeCause)
            }
            curlApi!!.close()
        }.invokeOnCompletion {
            curlDispatcher.close()
        }
    }

    private fun cancelRequest(easyHandle: EasyHandle, cause: Throwable) {
        curlScope.launch {
            curlApi!!.cancelRequest(easyHandle, cause)
        }
    }
}

private sealed interface KodexCurlTask {
    data class SendRequest(
        val requestData: KodexCurlRequestData,
        val completionHandler: CompletableDeferred<KodexCurlSuccess>,
    ) : KodexCurlTask

    class SendWebSocketFrame(
        val websocket: KodexCurlWebSocketResponseBody,
        val flags: Int,
        val data: ByteArray,
        val completionHandler: CompletableJob,
    ) : KodexCurlTask

    class CancelWebSocket(
        val websocket: KodexCurlWebSocketResponseBody,
    ) : KodexCurlTask

    fun cancel(cause: Throwable) {
        when (this) {
            is SendRequest -> {
                requestData.dispose()
                completionHandler.completeExceptionally(cause)
            }

            is SendWebSocketFrame -> completionHandler.completeExceptionally(cause)
            is CancelWebSocket -> Unit
        }
    }
}
