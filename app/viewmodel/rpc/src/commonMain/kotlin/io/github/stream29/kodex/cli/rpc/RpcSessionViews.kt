package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.cli.agent.DefaultComposerViewModelFactory
import io.github.stream29.kodex.rpc.models.CreatedSuggestedSession
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

/** Local availability, not a remote execution state or a synthetic Agent lifecycle. */
public sealed interface SessionViewStatus {
    public data object Loading : SessionViewStatus
    public data object Ready : SessionViewStatus
    public data object Missing : SessionViewStatus
    public data class Failed(public val cause: Throwable) : SessionViewStatus
    public data object Closed : SessionViewStatus
}

/** One view/heartbeat per opened tab index; a directory lookup does not create a view. */
public class RpcSessionViews(
    scope: CoroutineScope,
    public val services: RpcServices,
    private val models: StateFlow<List<ModelInfo>>,
    private val onCreated: (List<CreatedSuggestedSession>) -> Unit = {},
) : AutoCloseable {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val localScope = CoroutineScope(scope.coroutineContext + owner)
    private val mutex = Mutex()
    private val views = mutableMapOf<Int, RpcSessionView>()

    public suspend fun open(index: Int): RpcSessionView {
        val view = mutex.withLock {
            owner.ensureActive()
            views.getOrPut(index) { RpcSessionView(index, services, localScope, models, onCreated) }
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

    public suspend fun release(view: RpcSessionView) {
        mutex.withLock {
            if (views[view.index] === view) views.remove(view.index)
            view.close()
        }
        view.join()
    }

    /** Also cancels a tab that is still initializing and has not returned a view to its caller. */
    public suspend fun release(index: Int) {
        val view = mutex.withLock { views.remove(index)?.also { it.close() } }
        view?.join()
    }

    override fun close() { owner.cancel() }
    public suspend fun join(): Unit = owner.join()
}

/**
 * The coordinator outlives an individual subscription binding, not the frontend owner.
 * Recovery only repeats activation/reads; no failed or unacknowledged command is replayed.
 */
public class RpcSessionView internal constructor(
    public val index: Int,
    private val services: RpcServices,
    scope: CoroutineScope,
    private val models: StateFlow<List<ModelInfo>>,
    private val onCreated: (List<CreatedSuggestedSession>) -> Unit,
) : AutoCloseable {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val localScope = CoroutineScope(scope.coroutineContext + owner)
    private val initial = CompletableDeferred<Unit>()
    private val mutableBinding = MutableStateFlow<RpcSessionBinding?>(null)
    private val mutableStatus = MutableStateFlow<SessionViewStatus>(SessionViewStatus.Loading)
    public val binding: StateFlow<RpcSessionBinding?> = mutableBinding.asStateFlow()
    public val status: StateFlow<SessionViewStatus> = mutableStatus.asStateFlow()
    public val composer: ComposerViewModel = DefaultComposerViewModelFactory.create()
    private val mutablePresentation = MutableStateFlow<RpcAgentPresentation?>(null)
    public val presentation: StateFlow<RpcAgentPresentation?> = mutablePresentation.asStateFlow()

    init {
        owner.invokeOnCompletion {
            mutableBinding.value = null
            mutablePresentation.value = null
            composer.close()
            mutableStatus.value = SessionViewStatus.Closed
            initial.cancel()
        }
        localScope.launch {
            try {
                coordinate()
            } finally {
                mutableBinding.value = null
                mutablePresentation.value = null
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
                            services.global.keepSessionAlive(index)
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
                mutablePresentation.value = RpcAgentPresentation(next, bindingScope, services, composer, models, onCreated)
                mutableStatus.value = SessionViewStatus.Ready
                initial.complete(Unit)
                failure = failed.await()
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                failure = if (failed.isCompleted) failed.await() else error
            } finally {
                mutableBinding.value = null
                mutablePresentation.value = null
                withContext(NonCancellable) { bindingJob.cancelAndJoin() }
            }
            when (val reason = requireNotNull(failure).remoteReason()) {
                is SessionNotActive -> delay(50)
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

    public fun current(): RpcSessionBinding {
        owner.ensureActive()
        check(status.value == SessionViewStatus.Ready) { "Session view is not ready: ${status.value}" }
        return requireNotNull(binding.value).also { it.ensureActive() }
    }

    override fun close() {
        mutableStatus.value = SessionViewStatus.Closed
        mutableBinding.value = null
        mutablePresentation.value = null
        owner.cancel()
    }

    public suspend fun join(): Unit = owner.join()
}

private fun Throwable.remoteReason(): Throwable {
    var value = this
    while (value is CancellationException && value.cause != null && value.cause !== value) {
        value = requireNotNull(value.cause)
    }
    return value
}
