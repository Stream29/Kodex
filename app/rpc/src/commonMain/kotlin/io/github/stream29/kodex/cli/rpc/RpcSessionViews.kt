@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import io.github.stream29.kodex.rpc.models.CreatedSuggestedSession
import io.github.stream29.kodex.rpc.local.BackendConnectionLost
import io.github.stream29.kodex.openai.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public actual class RpcSessionViews actual constructor(
    scope: CoroutineScope,
    public actual val services: RpcServices,
    private val models: StateFlow<List<ModelInfo>>,
    private val reportUnhandledError: (Throwable) -> Unit,
    private val connectionAvailable: () -> Boolean,
    private val onCreated: (List<CreatedSuggestedSession>) -> Unit,
) : AutoCloseable {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val localScope = CoroutineScope(scope.coroutineContext + owner)
    private val mutex = Mutex()
    private val views = mutableMapOf<Int, RpcSessionView>()

    public actual suspend fun open(index: Int): RpcSessionView {
        val view = mutex.withLock {
            owner.ensureActive()
            views.getOrPut(index) { RpcSessionView(index, services, localScope, models, onCreated, reportUnhandledError, connectionAvailable) }
        }
        try {
            view.awaitReady()
            return view
        } catch (error: Throwable) {
            // A terminal failure before the first Ready result must not poison future opens.
            // A cancelled caller alone is not grounds to discard a still-initializing shared view.
            if (view.status.value is SessionViewStatus.Missing || view.status.value is SessionViewStatus.Failed) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (views[index] === view &&
                            (view.status.value is SessionViewStatus.Missing || view.status.value is SessionViewStatus.Failed)
                        ) {
                            view.close()
                            view.join()
                            views.remove(index)
                        }
                    }
                }
            }
            throw error
        }
    }

    public actual suspend fun release(view: RpcSessionView): Unit {
        mutex.withLock {
            if (views[view.index] === view) views.remove(view.index)
            view.close()
        }
        view.join()
    }

    /** Also cancels a tab that is still initializing and has not returned a view to its caller. */
    public actual suspend fun release(index: Int): Unit {
        val view = mutex.withLock { views.remove(index)?.also { it.close() } }
        view?.join()
    }

    /** Reconnect activates still-open frontend tabs before publishing the new connection. */
    public actual suspend fun reactivate(replacement: RpcServices, isConnected: () -> Boolean): Unit {
        val targets = mutex.withLock {
            owner.ensureActive()
            views.values.filter { it.status.value != SessionViewStatus.Closed }
                .map { it.index to it.binding.value }
        }
        targets.forEach { (index, binding) ->
            try { replacement.global.keepSessionAlive(index) }
            catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!isConnected()) throw failure
                // Business failures belong to the captured Session owner. A bad tab
                // does not prevent healthy tabs/global subscriptions reconnecting.
                binding?.failRecovery(failure)
            }
        }
    }

    actual override fun close() { owner.cancel() }
    public actual suspend fun join(): Unit = owner.join()
}

public actual class RpcSessionView internal actual constructor(
    public actual val index: Int,
    private val services: RpcServices,
    scope: CoroutineScope,
    private val models: StateFlow<List<ModelInfo>>,
    private val onCreated: (List<CreatedSuggestedSession>) -> Unit,
    private val reportUnhandledError: (Throwable) -> Unit,
    private val connectionAvailable: () -> Boolean,
) : AutoCloseable {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val localScope = CoroutineScope(scope.coroutineContext + owner)
    private val initial = CompletableDeferred<Unit>()
    private val mutableBinding = MutableStateFlow<RpcSessionBinding?>(null)
    private val mutableStatus = MutableStateFlow<SessionViewStatus>(SessionViewStatus.Loading)
    public actual val binding: StateFlow<RpcSessionBinding?> = mutableBinding.asStateFlow()
    public actual val status: StateFlow<SessionViewStatus> = mutableStatus.asStateFlow()
    private val mutableAgent = MutableStateFlow<AgentViewModel?>(null)
    /** Sole binding-owned Agent; recovery replaces it rather than wrapping the previous owner. */
    public actual val agent: StateFlow<AgentViewModel?> = mutableAgent.asStateFlow()

    init {
        owner.invokeOnCompletion {
            mutableBinding.value = null
            mutableAgent.value?.close()
            mutableAgent.value = null
            mutableStatus.value = SessionViewStatus.Closed
            initial.cancel()
        }
        localScope.launch {
            try {
                coordinate()
            } finally {
                mutableBinding.value = null
                mutableAgent.value?.close()
                mutableAgent.value = null
                if (!owner.isActive) mutableStatus.value = SessionViewStatus.Closed
                initial.cancel()
            }
        }
    }

    internal suspend fun awaitReady() { initial.await(); owner.ensureActive() }

    private suspend fun coordinate() {
        while (true) {
            currentCoroutineContext().ensureActive()
            mutableStatus.value = SessionViewStatus.Loading
            val failed = CompletableDeferred<Throwable>()
            val bindingJob = Job(owner)
            val handler = CoroutineExceptionHandler { _, error -> failed.complete(error) }
            val bindingScope = CoroutineScope(localScope.coroutineContext + bindingJob + handler)
            bindingJob.invokeOnCompletion { cause ->
                if (cause != null) failed.complete(cause)
            }
            var failure: Throwable? = null
            try {
                val next = withContext(bindingScope.coroutineContext) {
                    services.global.keepSessionAlive(index)
                    bindingScope.launch {
                        while (true) {
                            delay(20_000)
                            if (connectionAvailable()) {
                                try { services.global.keepSessionAlive(index) }
                                catch (failure: Throwable) {
                                    currentCoroutineContext().ensureActive()
                                    if (connectionAvailable()) throw failure
                                }
                            }
                        }
                    }
                    val result = RpcSessionBinding.create(index, services, bindingScope) { error ->
                        failed.complete(error)
                    }
                    // Returning from withContext must not wait for the long-lived observers:
                    // observers are launched in bindingScope, not this initialization coroutine.
                    result
                }
                owner.ensureActive()
                bindingJob.ensureActive()
                mutableBinding.value = next
                val nextAgent = createRpcAgentViewModel(
                    next,
                    bindingScope,
                    services,
                    models,
                    onCreated,
                    reportUnhandledError,
                )
                mutableAgent.value = nextAgent
                mutableStatus.value = SessionViewStatus.Ready
                initial.complete(Unit)
                failure = failed.await()
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                failure = if (failed.isCompleted) failed.await() else error
            } finally {
                mutableBinding.value = null
                mutableAgent.value?.close()
                mutableAgent.value = null
                withContext(NonCancellable) { bindingJob.cancelAndJoin() }
            }
            when (val reason = requireNotNull(failure).remoteReason()) {
                is SessionNotActive -> delay(50)
                is BackendConnectionLost -> {
                    mutableStatus.value = SessionViewStatus.Loading
                    do { delay(50) } while (!connectionAvailable())
                }
                is SessionNotFound -> {
                    mutableStatus.value = SessionViewStatus.Missing
                    initial.completeExceptionally(reason)
                    awaitCancellation()
                }
                else -> {
                    mutableStatus.value = SessionViewStatus.Failed(reason)
                    initial.completeExceptionally(reason)
                    awaitCancellation()
                }
            }
        }
    }

    public actual fun current(): RpcSessionBinding {
        owner.ensureActive()
        check(status.value == SessionViewStatus.Ready) { "Session view is not ready: ${status.value}" }
        return requireNotNull(binding.value).also { it.ensureActive() }
    }

    actual override fun close() {
        mutableStatus.value = SessionViewStatus.Closed
        mutableBinding.value = null
        mutableAgent.value?.close()
        mutableAgent.value = null
        owner.cancel()
    }

    public actual suspend fun join(): Unit = owner.join()
}

private fun Throwable.remoteReason(): Throwable {
    var value = this
    while (value is CancellationException && value.cause != null && value.cause !== value) {
        value = requireNotNull(value.cause)
    }
    return value
}
