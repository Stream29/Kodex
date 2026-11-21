@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.AgentParentAnchor
import io.github.stream29.kodex.app.session.contract.AgentTabViewModel
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
    private val standalone = mutableMapOf<Int, RpcAgentTab>()
    private var closed = false

    actual override suspend fun open(sessionIndex: Int): RpcSessionView =
        openRoot(sessionIndex, expectedAgentId = null, unarchive = true)

    internal suspend fun openCatalogEntry(sessionIndex: Int, rootAgentId: Int): RpcSessionView =
        openRoot(sessionIndex, expectedAgentId = rootAgentId, unarchive = true)

    private suspend fun openRoot(sessionIndex: Int, expectedAgentId: Int?, unarchive: Boolean): RpcSessionView {
        val (view, alreadyReady) = mutex.withLock {
            check(!closed)
            owner.ensureActive()
            val current = views.getOrPut(sessionIndex) {
                RpcSessionView(sessionIndex, models, newTab(sessionIndex, expectedAgentId, unarchive))
            }
            current to current.isInitiallyReady
        }
        try {
            view.awaitReady()
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
        if (expectedAgentId != null) check(view.agentId == expectedAgentId) { "The original Session no longer exists." }
        // A direct Agent open may have created a root handle without unarchiving it.
        if (unarchive && (alreadyReady || !view.tab.unarchivesOnFirstOpen)) services.global.unarchiveSession(sessionIndex)
        return view
    }

    override suspend fun openAgent(agentId: Int): AgentTabViewModel {
        mutex.withLock {
            check(!closed)
            owner.ensureActive()
            views.values.firstOrNull { it.tab.resolvedAgentId == agentId }
        }?.let { it.awaitReady(); return it }
        // Existing read-only catalog supplies root capability. Child ids are never
        // inferred from a Session number, and no extra delegation lookup is needed.
        val root = services.global.getSessionCatalog(includeArchived = true).firstOrNull { it.rootAgentId == agentId }
        if (root != null) return openRoot(root.sessionIndex, expectedAgentId = agentId, unarchive = false)
        val tab = mutex.withLock {
            check(!closed)
            owner.ensureActive()
            standalone.getOrPut(agentId) { newTab(null, agentId, false) }
        }
        try {
            tab.awaitReady()
            return tab
        } catch (error: Throwable) {
            if (tab.status.value is SessionViewStatus.Missing || tab.status.value is SessionViewStatus.Failed) {
                withContext(NonCancellable) { releaseAgent(tab) }
            }
            throw error
        }
    }

    private fun newTab(sessionIndex: Int?, agentId: Int?, unarchive: Boolean) =
        RpcAgentTab(sessionIndex, agentId, unarchive, services, localScope, models, reportUnhandledError, connectionAvailable)

    override suspend fun releaseAgent(target: AgentTabViewModel) {
        when (target) {
            is RpcSessionView -> release(target)
            is RpcAgentTab -> {
                mutex.withLock {
                    if (standalone[target.agentId] === target) standalone.remove(target.agentId)
                    target.close()
                }
                target.join()
            }
            else -> error("Unknown Agent page.")
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
    actual override suspend fun delete(sessionIndex: Int): Boolean =
        services.global.deleteSession(sessionIndex).also { if (it) release(sessionIndex) }

    actual override suspend fun shutdown(): Unit {
        currentCoroutineContext().ensureActive()
        val snapshot = mutex.withLock {
            closed = true
            (views.values.map { it.tab } + standalone.values).also {
                views.clear()
                standalone.clear()
                it.forEach(RpcAgentTab::close)
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
            (views.values.map { it.tab } + standalone.values).mapNotNull { tab ->
                tab.binding.value?.takeIf { tab.isInitiallyReady }?.let { tab to it }
            }
        }
        targets.forEach { (view, binding) ->
            if (!view.isOpenBinding(binding)) return@forEach
            try { replacement.runtime.keepAgentAlive(binding.agentId) }
            catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!isConnected()) throw failure
                // A business failure belongs to that exact binding, not another tab or Agent.
                binding.failRecovery(failure)
            }
        }
    }

    actual override fun close() { owner.cancel() }
    public actual suspend fun join(): Unit = owner.join()
}

/** Exactly one Agent binding lifecycle. Only a root tab has Session management capability. */
internal class RpcAgentTab(
    private val sessionIndex: Int?,
    expectedAgentId: Int?,
    val unarchivesOnFirstOpen: Boolean,
    private val services: RpcServices,
    scope: CoroutineScope,
    private val models: StateFlow<List<ModelInfo>>,
    private val reportUnhandledError: (Throwable) -> Unit,
    private val connectionAvailable: () -> Boolean,
) : AgentTabViewModel {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val localScope = CoroutineScope(scope.coroutineContext + owner)
    private val initial = CompletableDeferred<Unit>()
    private val mutableBinding = MutableStateFlow<RpcSessionBinding?>(null)
    private val mutableStatus = MutableStateFlow<SessionViewStatus>(SessionViewStatus.Loading)
    val binding: StateFlow<RpcSessionBinding?> = mutableBinding.asStateFlow()
    val status: StateFlow<SessionViewStatus> = mutableStatus.asStateFlow()
    private val mutableAgent = MutableStateFlow<AgentViewModel?>(null)
    override val agent: StateFlow<AgentViewModel?> = mutableAgent.asStateFlow()
    private val identity = MutableStateFlow(expectedAgentId)
    val resolvedAgentId: Int? get() = identity.value
    override val agentId: Int get() = checkNotNull(resolvedAgentId)
    // Last-known read cache: recovery can withdraw the Agent/binding before publishing
    // the replacement. It must not blank the tab name or become a writable second truth.
    private val mutableSettings = MutableStateFlow(KodexAgentSettings(model = OpenAiModelId("placeholder")))
    val settings: StateFlow<KodexAgentSettings> = mutableSettings.asStateFlow()
    override val name: StateFlow<String> = settings.projectState {
        it.name.ifBlank { resolvedAgentId?.let { id -> "Agent #$id" } ?: "Session #$sessionIndex" }
    }
    override val lifecycle: StateFlow<PersistedSessionLifecycleState> =
        status.projectState { lifecycleStatus(it, isRoot = sessionIndex != null) }

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

    val isInitiallyReady: Boolean get() = initial.isCompleted && !initial.isCancelled && owner.isActive

    suspend fun awaitReady() { initial.await(); owner.ensureActive() }

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
                    val agentId = if (sessionIndex != null) {
                        services.global.getRootAgentId(sessionIndex).also { root ->
                            check(resolvedAgentId == null || resolvedAgentId == root) { "The original Session no longer exists." }
                            identity.value = root
                        }
                    } else this@RpcAgentTab.agentId
                    openBinding(agentId, bindingScope, failed) {
                        if (sessionIndex != null && unarchivesOnFirstOpen && !initial.isCompleted) {
                            services.global.unarchiveSession(sessionIndex)
                        }
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
                owner.ensureActive()
                mutableAgent.value = nextAgent
                mutableStatus.value = SessionViewStatus.Ready
                initial.complete(Unit)
                failure = failed.await()
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                failure = if (failed.isCompleted) failed.await() else error
            } finally {
                withContext(NonCancellable) {
                    mutableBinding.value = null
                    mutableAgent.value?.close()
                    mutableAgent.value = null
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

    fun isOpenBinding(target: RpcSessionBinding): Boolean = owner.isActive && binding.value === target

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCompletionAnchor(childAgentId: Int): Flow<Boolean> =
        binding.flatMapLatest { captured -> flow {
            emit(false)
            if (captured != null) {
                emitAll(captured.storage.observeSubagentCompletionAnchor(childAgentId) { isOpenBinding(captured) })
            }
        } }

    override suspend fun locateChildEvent(
        source: AgentViewModel, childAgentId: Int, anchor: AgentParentAnchor, isCurrent: () -> Boolean,
    ) {
        val captured = (source as? RpcAgentViewModel)?.binding ?: return
        fun stillCurrent() = isCurrent() && isOpenBinding(captured) && agent.value === source
        if (!stillCurrent()) return
        val type = when (anchor) {
            AgentParentAnchor.Creation -> AgentCommitType.EventSubagentCreated
            AgentParentAnchor.Completion -> AgentCommitType.EventSubagentCompleted
        }
        val found = findSubagentReturnAnchor(captured.storage, childAgentId, type, isBound = ::stillCurrent)
            as? SubagentReturnAnchorResult.Found ?: return
        currentCoroutineContext().ensureActive()
        if (stillCurrent() && captured.storage.cacheNonce.value == found.cacheNonce) {
            source.history.requestScrollToAnchor(found.index, found.cacheNonce, ::stillCurrent)
        }
    }

    fun current(): RpcSessionBinding {
        owner.ensureActive()
        check(status.value == SessionViewStatus.Ready) { "Agent view is not ready: ${status.value}" }
        return requireNotNull(binding.value).also { it.ensureActive() }
    }

    suspend fun requireOriginalRoot() {
        if (sessionIndex == null) return
        val captured = current()
        check(services.global.getRootAgentId(sessionIndex) == captured.agentId) { "The original Session no longer exists." }
        captured.ensureActive()
    }

    override fun close() {
        owner.cancel()
        mutableStatus.value = SessionViewStatus.Closed
        mutableBinding.value = null
        mutableAgent.value?.close()
        mutableAgent.value = null
    }

    suspend fun join(): Unit = owner.join()
}

public actual class RpcSessionView internal constructor(
    actual override val sessionIndex: Int,
    actual override val models: StateFlow<List<ModelInfo>>,
    internal val tab: RpcAgentTab,
) : PersistedSessionViewModel, AgentTabViewModel by tab {
    public actual val binding: StateFlow<RpcSessionBinding?> = tab.binding
    public actual val status: StateFlow<SessionViewStatus> = tab.status
    actual override val rootAgent: StateFlow<AgentViewModel?> = tab.agent
    actual override val settings: StateFlow<KodexAgentSettings> = tab.settings
    actual override val name: StateFlow<String> = tab.name
    actual override val lifecycle: StateFlow<PersistedSessionLifecycleState> = tab.lifecycle
    override val agent: StateFlow<AgentViewModel?> get() = rootAgent
    override val agentId: Int get() = tab.agentId
    override suspend fun requireOriginalRoot(): Unit = tab.requireOriginalRoot()

    internal val isInitiallyReady: Boolean get() = tab.isInitiallyReady
    internal suspend fun awaitReady(): Unit = tab.awaitReady()
    public actual fun current(): RpcSessionBinding = tab.current()

    private fun currentAgent(): AgentViewModel {
        val activeBinding = current()
        return requireNotNull(rootAgent.value).also { check(it.settings === activeBinding.settings) }
    }

    actual override suspend fun readCreatedAt(): Instant? = current().readCreatedAt()
    actual override suspend fun readUpdatedAt(): Instant? = current().readUpdatedAt()

    actual override suspend fun rename(name: String): Unit = currentAgent().rename(name.trim())
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

    actual override fun close(): Unit = tab.close()

    public actual suspend fun join(): Unit = tab.join()
}

private fun lifecycleStatus(status: SessionViewStatus, isRoot: Boolean): PersistedSessionLifecycleState =
    when (status) {
        SessionViewStatus.Loading -> PersistedSessionLifecycleState.Loading
        SessionViewStatus.Ready -> PersistedSessionLifecycleState.Open
        SessionViewStatus.Missing -> PersistedSessionLifecycleState.Failed(if (isRoot) "Session no longer exists." else "Agent no longer exists.")
        is SessionViewStatus.Failed -> PersistedSessionLifecycleState.Failed(status.cause.message ?: "Agent view failed.")
        SessionViewStatus.Closed -> PersistedSessionLifecycleState.Closed
    }

private fun Throwable.remoteReason(): Throwable {
    var value = this
    while (value is CancellationException && value.cause != null && value.cause !== value) {
        value = requireNotNull(value.cause)
    }
    return value
}
