@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionLifecycleState
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.rpc.local.BackendConnectionLost
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import io.github.stream29.kodex.utils.rpcexception.AgentNotActive
import io.github.stream29.kodex.utils.rpcexception.AgentNotFound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.io.files.Path
import kotlin.time.Instant

public actual class RpcSessionViews actual constructor(
    scope: CoroutineScope,
    public actual val services: RpcServices,
    private val models: StateFlow<List<ModelInfo>>,
    private val reportUnhandledError: (Throwable) -> Unit,
    private val connectionAvailable: () -> Boolean,
) : PersistedSessionViewModelRegistry, AutoCloseable {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val localScope = CoroutineScope(scope.coroutineContext + owner)
    private val mutex = Mutex()
    private val views = mutableMapOf<Int, RpcSessionView>()
    private var closed = false

    actual override suspend fun open(sessionIndex: Int): RpcSessionView {
        val (view, alreadyReady) = mutex.withLock {
            check(!closed)
            owner.ensureActive()
            val current = views.getOrPut(sessionIndex) {
                RpcSessionView(sessionIndex, services, localScope, models, reportUnhandledError, connectionAvailable)
            }
            current to current.isInitiallyReady
        }
        if (alreadyReady) {
            services.global.unarchiveSession(sessionIndex)
            return view
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
                        if (views[sessionIndex] === view &&
                            (view.status.value is SessionViewStatus.Missing || view.status.value is SessionViewStatus.Failed)
                        ) {
                            view.close()
                            view.join()
                            views.remove(sessionIndex)
                        }
                    }
                }
            }
            throw error
        }
    }

    public actual suspend fun release(view: RpcSessionView): Unit {
        mutex.withLock {
            if (views[view.sessionIndex] === view) views.remove(view.sessionIndex)
            view.close()
        }
        view.join()
    }

    /** Also cancels a tab that is still initializing and has not returned a view to its caller. */
    actual override suspend fun release(sessionIndex: Int): Unit {
        val view = mutex.withLock { views.remove(sessionIndex)?.also { it.close() } }
        view?.join()
    }

    actual override suspend fun archive(sessionIndex: Int): Unit = services.global.archiveSession(sessionIndex)
    actual override suspend fun unarchive(sessionIndex: Int): Unit = services.global.unarchiveSession(sessionIndex)
    actual override suspend fun fork(sessionIndex: Int): Int = services.global.forkSession(sessionIndex)
    actual override suspend fun delete(sessionIndex: Int): Boolean =
        services.global.deleteSession(sessionIndex).also { if (it) release(sessionIndex) }

    actual override suspend fun shutdown(): Unit {
        currentCoroutineContext().ensureActive()
        val snapshot = mutex.withLock {
            closed = true
            views.values.toList().also {
                views.clear()
                it.forEach(RpcSessionView::close)
            }
        }
        close()
        withContext(NonCancellable) {
            snapshot.forEach { it.join() }
            join()
        }
    }

    /** Reconnect activates still-open frontend tabs before publishing the new connection. */
    public actual suspend fun reactivate(replacement: RpcServices, isConnected: () -> Boolean): Unit {
        val targets = mutex.withLock {
            owner.ensureActive()
            views.values.filter { it.status.value != SessionViewStatus.Closed }
                .mapNotNull { it.binding.value }
        }
        targets.forEach { binding ->
            try { replacement.runtime.keepAgentAlive(binding.agentId) }
            catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!isConnected()) throw failure
                // Business failures belong to the captured Session owner. A bad tab
                // does not prevent healthy tabs/global subscriptions reconnecting.
                binding.failRecovery(failure)
            }
        }
    }

    actual override fun close() { owner.cancel() }
    public actual suspend fun join(): Unit = owner.join()
}

public actual class RpcSessionView internal actual constructor(
    actual override val sessionIndex: Int,
    private val services: RpcServices,
    scope: CoroutineScope,
    actual override val models: StateFlow<List<ModelInfo>>,
    private val reportUnhandledError: (Throwable) -> Unit,
    private val connectionAvailable: () -> Boolean,
) : PersistedSessionViewModel {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val localScope = CoroutineScope(scope.coroutineContext + owner)
    private val initial = CompletableDeferred<Unit>()
    private val mutableBinding = MutableStateFlow<RpcSessionBinding?>(null)
    private val mutableStatus = MutableStateFlow<SessionViewStatus>(SessionViewStatus.Loading)
    public actual val binding: StateFlow<RpcSessionBinding?> = mutableBinding.asStateFlow()
    public actual val status: StateFlow<SessionViewStatus> = mutableStatus.asStateFlow()
    private val mutableAgent = MutableStateFlow<AgentViewModel?>(null)
    /** Sole binding-owned Agent; recovery replaces it rather than wrapping the previous owner. */
    actual override val rootAgent: StateFlow<AgentViewModel?> = mutableAgent.asStateFlow()
    // Last-known read cache: recovery can withdraw the Agent/binding before publishing
    // the replacement. It must not blank the tab name or become a writable second truth.
    private val mutableSettings = MutableStateFlow(KodexAgentSettings(model = OpenAiModelId("placeholder")))
    actual override val settings: StateFlow<KodexAgentSettings> = mutableSettings.asStateFlow()
    actual override val name: StateFlow<String> = settings.projectState { it.threadName }
    actual override val lifecycle: StateFlow<PersistedSessionLifecycleState> = status.projectState(::lifecycleStatus)

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

    internal val isInitiallyReady: Boolean get() = initial.isCompleted && owner.isActive

    internal suspend fun awaitReady() { initial.await(); owner.ensureActive() }

    private suspend fun coordinate() {
        var boundAgentId: Int? = null
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
                    val agentId = boundAgentId ?: services.global.getRootAgentId(sessionIndex).also { boundAgentId = it }
                    services.runtime.keepAgentAlive(agentId)
                    if (!initial.isCompleted) services.global.unarchiveSession(sessionIndex)
                    bindingScope.launch {
                        while (true) {
                            delay(20_000)
                            if (connectionAvailable()) {
                                try { services.runtime.keepAgentAlive(agentId) }
                                catch (failure: Throwable) {
                                    currentCoroutineContext().ensureActive()
                                    if (connectionAvailable()) throw failure
                                }
                            }
                        }
                    }
                    val result = RpcSessionBinding.create(sessionIndex, services, bindingScope, agentId = agentId) { error ->
                        failed.complete(error)
                    }
                    // Returning from withContext must not wait for the long-lived observers:
                    // observers are launched in bindingScope, not this initialization coroutine.
                    result
                }
                owner.ensureActive()
                bindingJob.ensureActive()
                mutableSettings.value = next.settings.value
                bindingScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    next.settings.collect { mutableSettings.value = it }
                }
                mutableBinding.value = next
                val nextAgent = createRpcAgentViewModel(
                    next,
                    bindingScope,
                    models,
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
                is AgentNotActive, is SessionNotActive -> delay(50)
                is BackendConnectionLost -> {
                    mutableStatus.value = SessionViewStatus.Loading
                    do { delay(50) } while (!connectionAvailable())
                }
                is AgentNotFound, is SessionNotFound -> {
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

    private fun currentAgent(): AgentViewModel {
        owner.ensureActive()
        val activeBinding = current()
        return requireNotNull(rootAgent.value).also { check(it.settings === activeBinding.settings) }
    }

    actual override suspend fun readCreatedAt(): Instant? = current().readCreatedAt()
    actual override suspend fun readUpdatedAt(): Instant? = current().readUpdatedAt()

    actual override suspend fun fork(
        source: AgentViewModel,
        untilExclusive: Int,
        expectedGeneration: Long,
    ): Int {
        owner.ensureActive()
        val activeBinding = current()
        val capturedAgent = requireNotNull(rootAgent.value).also { check(it.settings === activeBinding.settings) }
        require(source === capturedAgent) { "The history target expired." }
        return activeBinding.forkHistory(untilExclusive, expectedGeneration)
    }

    actual override suspend fun rename(name: String): Unit = currentAgent().renameThread(name.trim())
    actual override suspend fun updateModel(model: OpenAiModelId): Unit = currentAgent().updateModel(model)
    actual override suspend fun updateWorkingDirectory(workingDirectory: Path): Unit =
        currentAgent().updateWorkingDirectory(workingDirectory)
    actual override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit =
        currentAgent().updateReasoningEffort(reasoningEffort)
    actual override suspend fun updateServiceTier(serviceTier: ServiceTier): Unit =
        currentAgent().updateServiceTier(serviceTier)
    actual override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit =
        currentAgent().updateRequestUserInputMode(mode)
    actual override suspend fun updateModelConfiguration(
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
        serviceTier: ServiceTier,
    ): Unit = currentAgent().updateModelConfiguration(model, reasoningEffort, serviceTier)

    actual override suspend fun shutdown() {
        close()
        join()
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

private fun lifecycleStatus(status: SessionViewStatus): PersistedSessionLifecycleState =
    when (status) {
        SessionViewStatus.Loading -> PersistedSessionLifecycleState.Loading
        SessionViewStatus.Ready -> PersistedSessionLifecycleState.Open
        SessionViewStatus.Missing -> PersistedSessionLifecycleState.Failed("Session no longer exists.")
        is SessionViewStatus.Failed -> PersistedSessionLifecycleState.Failed(status.cause.message ?: "Session view failed.")
        SessionViewStatus.Closed -> PersistedSessionLifecycleState.Closed
    }

private fun Throwable.remoteReason(): Throwable {
    var value = this
    while (value is CancellationException && value.cause != null && value.cause !== value) {
        value = requireNotNull(value.cause)
    }
    return value
}
