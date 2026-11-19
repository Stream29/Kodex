@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.AgentParentAnchor
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
                .toList()
        }
        targets.forEach { view ->
            view.recoveryBindings().forEach nextBinding@{ binding ->
                if (!view.isOpenBinding(binding)) return@nextBinding
                try { replacement.runtime.keepAgentAlive(binding.agentId) }
                catch (failure: Throwable) {
                    currentCoroutineContext().ensureActive()
                    if (!isConnected()) throw failure
                    // A business failure belongs to that exact binding, not another tab or Agent.
                    binding.failRecovery(failure)
                }
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
    private val mutableSelected = MutableStateFlow<AgentViewModel?>(null)
    override val selectedAgent: StateFlow<AgentViewModel?> = mutableSelected.asStateFlow()
    private val path = MutableStateFlow<List<OpenedChild>>(emptyList())
    private val pathMutex = Mutex()
    private val enterMutex = Mutex()
    private var pathRevision = 0L
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
            mutableSelected.value = null
            path.value = emptyList()
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
                mutableSelected.value = null
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
                    val agentId = services.global.getRootAgentId(sessionIndex)
                    if (boundAgentId != null && boundAgentId != agentId) {
                        throw IllegalStateException("The original Session no longer exists.")
                    }
                    boundAgentId = agentId
                    openBinding(agentId, bindingScope, failed) {
                        if (!initial.isCompleted) services.global.unarchiveSession(sessionIndex)
                    }
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
                pathMutex.withLock {
                    owner.ensureActive()
                    mutableAgent.value = nextAgent
                    mutableStatus.value = SessionViewStatus.Ready
                    publishSelection()
                }
                initial.complete(Unit)
                failure = failed.await()
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                failure = if (failed.isCompleted) failed.await() else error
            } finally {
                withContext(NonCancellable) {
                    pathMutex.withLock {
                        mutableBinding.value = null
                        mutableAgent.value?.close()
                        mutableAgent.value = null
                        publishSelection()
                    }
                    bindingJob.cancelAndJoin()
                }
            }
            when (val reason = requireNotNull(failure).remoteReason()) {
                is AgentNotActive, is SessionNotActive -> delay(50)
                is BackendConnectionLost -> {
                    mutableStatus.value = SessionViewStatus.Loading
                    do { delay(50) } while (!connectionAvailable())
                }
                is AgentNotFound, is SessionNotFound -> {
                    closeChildren()
                    mutableStatus.value = SessionViewStatus.Missing
                    initial.completeExceptionally(reason)
                    awaitCancellation()
                }
                else -> {
                    closeChildren()
                    mutableStatus.value = SessionViewStatus.Failed(reason)
                    initial.completeExceptionally(reason)
                    awaitCancellation()
                }
            }
        }
    }

    /** Root and child use the same binding, renewal cadence, and connection admission. */
    private suspend fun openBinding(
        agentId: Int, scope: CoroutineScope, failed: CompletableDeferred<Throwable>,
        afterActivation: suspend () -> Unit = {},
    ): RpcSessionBinding {
        services.runtime.keepAgentAlive(agentId)
        afterActivation()
        scope.launch {
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
        return RpcSessionBinding.create(sessionIndex, services, scope, agentId = agentId) { failed.complete(it) }
    }

    private fun publishSelection() {
        val root = mutableAgent.value
        val last = path.value.lastOrNull()
        mutableSelected.value = if (root == null || last == null) root else last.agent.value
    }

    private suspend fun closeChildren() {
        pathMutex.withLock {
            val children = path.value
            path.value = emptyList()
            pathRevision++
            children.forEach { it.owner.cancel() }
            publishSelection()
        }
    }

    internal fun recoveryBindings(): List<RpcSessionBinding> =
        listOfNotNull(binding.value) + path.value.mapNotNull { it.agent.value?.binding }

    internal fun isOpenBinding(target: RpcSessionBinding): Boolean =
        owner.isActive && (binding.value === target || path.value.any { it.agent.value?.binding === target })

    override suspend fun enterChild(
        source: AgentViewModel, index: Int, generation: Long, revision: Long, childAgentId: Int,
        isTabSelected: () -> Boolean,
    ): Unit = enterMutex.withLock enter@{
        // Capture the projected event and recheck after both remote activation and binding reads.
        fun sourceIsCurrent(): Boolean {
            if (!owner.isActive || !isTabSelected() || selectedAgent.value !== source) return false
            val indexView = source.historyIndex
            val window = indexView.window.value
            return window.revision == revision && indexView.contains(generation, index) &&
                window.entryAtOrNull(index)?.createdChildAgentId == childAgentId
        }
        val root = pathMutex.withLock checkPath@{
            if (!sourceIsCurrent()) return@checkPath null
            val active = current()
            check(active.agentId != childAgentId && path.value.none { it.id == childAgentId })
            active
        } ?: return@enter
        suspend fun checkRoot() {
            check(services.global.getRootAgentId(sessionIndex) == root.agentId) { "The original Session no longer exists." }
        }
        checkRoot()
        if (!sourceIsCurrent() || binding.value !== root) return@enter
        val child = OpenedChild(childAgentId)
        var attached = false
        try {
            child.ready.await()
            checkRoot()
            pathMutex.withLock attach@{
                currentCoroutineContext().ensureActive()
                if (!sourceIsCurrent() || binding.value !== root || child.agent.value == null) return@attach
                child.owner.ensureActive()
                path.value += child
                pathRevision++
                attached = true
                publishSelection()
            }
        } finally {
            if (!attached) child.owner.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun hasCompletionAnchor(source: AgentViewModel): Flow<Boolean> = flow {
        val opened = path.value
        val child = opened.lastOrNull()
        if (child == null || child.agent.value !== source) {
            emit(false)
            return@flow
        }
        val parent = if (opened.size == 1) rootAgent else opened[opened.lastIndex - 1].agent
        emitAll(parent.flatMapLatest { agent ->
            flow bindingFlow@{
                emit(false)
                val binding = (agent as? RpcAgentViewModel)?.binding ?: return@bindingFlow
                emitAll(binding.storage.observeSubagentCompletionAnchor(child.id) {
                    child.agent.value === source && parent.value === agent && isOpenBinding(binding)
                })
            }
        })
    }

    override suspend fun returnToParent(
        source: AgentViewModel, anchor: AgentParentAnchor, isTabSelected: () -> Boolean,
    ) {
        val (child, parent, revision) = pathMutex.withLock {
            if (!owner.isActive || !isTabSelected() || selectedAgent.value !== source) return
            val child = path.value.lastOrNull() ?: return
            val parent = (if (path.value.size == 1) mutableAgent.value else path.value[path.value.lastIndex - 1].agent.value)
                as? RpcAgentViewModel
            path.value = path.value.dropLast(1)
            pathRevision++
            child.owner.cancel() // Local observers only; no Stop or archive.
            publishSelection()
            Triple(child, parent, pathRevision)
        }
        if (parent == null) return
        val parentBinding = parent.binding
        val type = when (anchor) {
            AgentParentAnchor.Creation -> AgentCommitType.EventSubagentCreated
            AgentParentAnchor.Completion -> AgentCommitType.EventSubagentCompleted
        }
        val found = parentBinding.findSubagentReturnAnchor(child.id, type) as? SubagentReturnAnchorResult.Found ?: return
        pathMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (owner.isActive && isTabSelected() && selectedAgent.value === parent && pathRevision == revision &&
                parentBinding.storage.cacheNonce.value == found.cacheNonce
            ) {
                parentBinding.ensureActive()
                parent.history.requestScrollToStorageIndex(found.index)
            }
        }
    }

    private inner class OpenedChild(val id: Int) {
        val owner = SupervisorJob(this@RpcSessionView.owner)
        val ready = CompletableDeferred<Unit>(owner)
        val agent = MutableStateFlow<RpcAgentViewModel?>(null)

        init {
            CoroutineScope(localScope.coroutineContext + owner).launch {
                var initialized = false
                while (true) {
                    val failed = CompletableDeferred<Throwable>()
                    val job = Job(owner)
                    val handler = CoroutineExceptionHandler { _, error -> failed.complete(error) }
                    val scope = CoroutineScope(localScope.coroutineContext + job + handler)
                    job.invokeOnCompletion { cause -> if (cause != null) failed.complete(cause) }
                    val reason = try {
                        val binding = withContext(scope.coroutineContext) { openBinding(id, scope, failed) }
                        val next = createRpcAgentViewModel(binding, scope, models, reportUnhandledError)
                        pathMutex.withLock {
                            owner.ensureActive()
                            job.ensureActive()
                            agent.value = next
                            publishSelection()
                            initialized = true
                            ready.complete(Unit)
                        }
                        failed.await().remoteReason()
                    } catch (failure: Throwable) {
                        currentCoroutineContext().ensureActive()
                        (if (failed.isCompleted) failed.await() else failure).remoteReason()
                    } finally {
                        withContext(NonCancellable) {
                            pathMutex.withLock {
                                agent.value?.close()
                                agent.value = null
                                publishSelection()
                            }
                            job.cancelAndJoin()
                        }
                    }
                    if (initialized) when (reason) {
                        is AgentNotActive, is SessionNotActive -> { delay(50); continue }
                        is BackendConnectionLost -> {
                            do { delay(50) } while (!connectionAvailable())
                            continue
                        }
                    }
                    if (!initialized) ready.completeExceptionally(reason)
                    else {
                        pathMutex.withLock {
                            val at = path.value.indexOfFirst { it === this@OpenedChild }
                            if (at >= 0) {
                                val removed = path.value.drop(at)
                                path.value = path.value.take(at)
                                pathRevision++
                                publishSelection()
                                removed.forEach { it.owner.cancel() }
                            }
                        }
                        reportUnhandledError(reason)
                    }
                    owner.cancel()
                    return@launch
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
        mutableSelected.value = null
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
