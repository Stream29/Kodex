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

@OptIn(ExperimentalForeignApi::class, InternalCoroutinesApi::class)
internal class KodexCurlProcessor(
    coroutineContext: CoroutineContext,
    // Internal test gate at the real native release boundary, never a second owner.
    private val beforeRelease: suspend () -> Unit = {},
) {
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private val curlDispatcher = newSingleThreadContext("kodex-curl-dispatcher")

    private var curlApi: KodexCurlMultiApiHandler? by atomic(null)
    private val closed = atomic(false)
    private val curlScope = CoroutineScope(coroutineContext)
    private val taskQueue: Channel<KodexCurlTask> = Channel(
        Channel.UNLIMITED,
        onUndeliveredElement = { it.cancel(CancellationException("Kodex Curl task was not delivered")) },
    )
    private val initialized = CompletableDeferred<Unit>()
    private val cancellationCause = atomic<Throwable?>(null)
    private var activeTask: KodexCurlTask? = null
    internal val cleanupCompleted = CompletableDeferred<Unit>()
    private val eventLoop: Job

    init {
        eventLoop = runEventLoop()
        eventLoop.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) {
                cancellationCause.value = cause
                // Parent-only cancellation must also wake a native poll.
                curlApi?.wakeup()
            }
        }
        eventLoop.invokeOnCompletion { cause ->
            cause?.let {
                curlScope.cancel(
                    cause = cause as? CancellationException ?: CancellationException(cause),
                )
            }
        }
        try {
            runBlocking { initialized.await() }
        } catch (primary: Throwable) {
            eventLoop.cancel(CancellationException("Kodex Curl initialization failed"))
            runBlocking {
                withContext(NonCancellable) {
                    eventLoop.join()
                    try {
                        cleanupCompleted.await()
                    } catch (cleanup: Throwable) {
                        if (cleanup !== primary && primary.suppressedExceptions.none { it === cleanup }) {
                            primary.addSuppressed(cleanup)
                        }
                    }
                }
            }
            throw primary
        }
    }

    suspend fun executeRequest(request: KodexCurlRequestData): KodexCurlSuccess {
        val result = CompletableDeferred<KodexCurlSuccess>()
        try {
            taskQueue.send(KodexCurlTask.SendRequest(request, result))
        } catch (cause: Throwable) {
            try {
                request.dispose()
            } catch (cleanup: Throwable) {
                if (cleanup !== cause) cause.addSuppressed(cleanup)
            }
            throw cause
        }
        curlApi?.wakeup()
        return result.await()
    }

    suspend fun sendWebSocketFrame(websocket: KodexCurlWebSocketResponseBody, flags: Int, data: ByteArray) {
        val result = Job()
        taskQueue.send(KodexCurlTask.SendWebSocketFrame(websocket, flags, data, result))
        curlApi?.wakeup()
        result.join()
    }

    fun cancelWebSocket(websocket: KodexCurlWebSocketResponseBody) {
        val sent = taskQueue.trySend(KodexCurlTask.CancelWebSocket(websocket))
        if (sent.isSuccess) curlApi?.wakeup()
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private fun runEventLoop(): Job = curlScope.launch(
        Dispatchers.IO + CoroutineName("kodex-curl-processor-loop"),
        start = CoroutineStart.ATOMIC,
    ) {
        var primary: Throwable? = null
        try {
            withContext(curlDispatcher) {
                val api = KodexCurlMultiApiHandler()
                curlApi = api
                initialized.complete(Unit)
                memScoped {
                    val transfersRunning = alloc<IntVar>()
                    while (!closed.value) {
                        ensureActive()
                        drainTaskQueue(api)
                        if (!closed.value) api.perform(transfersRunning)
                    }
                }
            }
        } catch (cause: Throwable) {
            primary = cause
            initialized.completeExceptionally(cause)
            throw cause
        } finally {
            // This child is awaited by the engine owner. Never join that owner
            // here: it cannot complete until this native release finishes.
            // Establish NonCancellable on the IO coordinator first. Returning
            // from the curl dispatcher must not resume into a cancelled Job
            // before dispatcher release and primary/suppressed publication.
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                fun attempt(block: () -> Unit) {
                    try {
                        block()
                    } catch (cause: Throwable) {
                        val first = cleanupFailure
                        if (first == null) cleanupFailure = cause
                        else if (first !== cause) first.addSuppressed(cause)
                    }
                }
                try {
                    withContext(curlDispatcher) {
                        closed.value = true
                        taskQueue.close()
                        try {
                            beforeRelease()
                        } catch (cause: Throwable) {
                            cleanupFailure = cause
                        }
                        val closeCause = primary ?: cancellationCause.value
                            ?: CancellationException("Kodex Curl client engine closed")
                        attempt { activeTask?.cancel(closeCause) }
                        activeTask = null
                        while (true) {
                            val task = taskQueue.tryReceive().getOrNull() ?: break
                            attempt { task.cancel(closeCause) }
                        }
                        attempt { curlApi?.close() }
                        curlApi = null
                    }
                } finally {
                    // Native dispatcher.close blocks for worker termination.
                    // The current coordinator is IO, not that worker.
                    withContext(Dispatchers.IO) {
                        attempt { curlDispatcher.close() }
                    }
                    val failure = cleanupFailure
                    if (failure == null) cleanupCompleted.complete(Unit)
                    else cleanupCompleted.completeExceptionally(failure)
                }
                cleanupFailure?.let { failure ->
                    val original = primary ?: cancellationCause.value
                    if (original == null) throw failure
                    if (original !== failure) original.addSuppressed(failure)
                }
            }
        }
    }

    private suspend fun drainTaskQueue(api: KodexCurlMultiApiHandler) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (closed.value) return
            val task = if (api.hasHandlers()) {
                taskQueue.tryReceive()
            } else {
                taskQueue.receiveCatching()
            }.getOrNull() ?: break

            activeTask = task
            when (task) {
                is KodexCurlTask.SendRequest -> handleSendRequest(api, task)
                is KodexCurlTask.CancelRequest ->
                    api.cancelRequest(task.request, task.cause)
                is KodexCurlTask.SendWebSocketFrame ->
                    api.sendWebSocketFrame(task.websocket, task.flags, task.data, task.completionHandler)
                is KodexCurlTask.CancelWebSocket ->
                    api.cancelWebSocket(task.websocket, CancellationException("WebSocket session closed"))
            }
            activeTask = null
        }
    }

    private fun handleSendRequest(api: KodexCurlMultiApiHandler, task: KodexCurlTask.SendRequest) {
        api.scheduleRequest(task.requestData, task.completionHandler, ::cancelRequest)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return

        taskQueue.close()
        curlApi?.wakeup()
    }

    private fun cancelRequest(request: KodexCurlRequestHandle, cause: Throwable) {
        val sent = taskQueue.trySend(KodexCurlTask.CancelRequest(request, cause))
        if (sent.isSuccess) curlApi?.wakeup()
    }
}

private sealed interface KodexCurlTask {
    data class SendRequest(
        val requestData: KodexCurlRequestData,
        val completionHandler: CompletableDeferred<KodexCurlSuccess>,
    ) : KodexCurlTask

    class CancelRequest(
        val request: KodexCurlRequestHandle,
        val cause: Throwable,
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
                try {
                    requestData.dispose()
                } finally {
                    completionHandler.completeExceptionally(cause)
                }
            }

            is CancelRequest -> Unit
            is SendWebSocketFrame -> completionHandler.completeExceptionally(cause)
            is CancelWebSocket -> Unit
        }
    }
}
