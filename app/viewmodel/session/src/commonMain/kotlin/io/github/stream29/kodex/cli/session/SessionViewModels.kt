package io.github.stream29.kodex.cli.session

import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.cli.rpc.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.time.Instant

/** Frontend handles only. The real repository and its resource graph never enter this module. */
public class DefaultPersistedSessionViewModelRegistry(
    public val views: RpcSessionViews,
    private val models: StateFlow<List<ModelInfo>>,
    private val scope: CoroutineScope,
) : PersistedSessionViewModelRegistry {
    private val mutex = Mutex()
    private val handles = mutableMapOf<Int, RpcPersistedSessionViewModel>()
    private var closed = false

    override suspend fun open(sessionIndex: Int): PersistedSessionViewModel = mutex.withLock {
        check(!closed)
        views.services.global.unarchiveSession(sessionIndex)
        handles[sessionIndex] ?: RpcPersistedSessionViewModel(
            views.open(sessionIndex), views.services, models, scope,
        ).also { handles[sessionIndex] = it }
    }
    override suspend fun release(sessionIndex: Int) {
        val handle = mutex.withLock { handles.remove(sessionIndex)?.also { it.close() } }
        views.release(sessionIndex)
        handle?.join()
    }
    override suspend fun archive(sessionIndex: Int): Unit = views.services.global.archiveSession(sessionIndex)
    override suspend fun unarchive(sessionIndex: Int): Unit = views.services.global.unarchiveSession(sessionIndex)
    override suspend fun fork(sessionIndex: Int): Int = views.services.global.forkSession(sessionIndex)
    override suspend fun delete(sessionIndex: Int): Boolean =
        views.services.global.deleteSession(sessionIndex).also { if (it) release(sessionIndex) }
    override suspend fun shutdown() {
        val previous = mutex.withLock {
            closed = true
            handles.values.toList().also { handles.clear(); it.forEach(RpcPersistedSessionViewModel::close) }
        }
        views.close()
        withContext(NonCancellable) { views.join(); previous.forEach { it.join() } }
    }
}

/** A stable tab owns successive read bindings, not a stable backend resource reference. */
public class RpcPersistedSessionViewModel internal constructor(
    public val view: RpcSessionView,
    private val services: RpcServices,
    override val models: StateFlow<List<ModelInfo>>,
    scope: CoroutineScope,
) : PersistedSessionViewModel {
    private val owner = Job(scope.coroutineContext[Job])
    private val local = CoroutineScope(scope.coroutineContext + owner)
    private val initial = requireNotNull(view.presentation.value)
    private val mutableAgent = MutableStateFlow<AgentViewModel?>(createRpcAgentViewModel(initial, models, local))
    override val rootAgent: StateFlow<AgentViewModel?> = mutableAgent.asStateFlow()
    override val sessionIndex: Int = view.index
    private val mutableSettings = MutableStateFlow(view.current().settings.value)
    override val settings: StateFlow<KodexAgentSettings> = mutableSettings.asStateFlow()
    override val name: StateFlow<String> = settings.map { it.threadName }
        .stateIn(local, SharingStarted.Eagerly, settings.value.threadName)
    private fun lifecycleStatus(status: SessionViewStatus): PersistedSessionLifecycleState =
        when (status) {
            SessionViewStatus.Loading -> PersistedSessionLifecycleState.Loading
            SessionViewStatus.Ready -> PersistedSessionLifecycleState.Open
            SessionViewStatus.Missing -> PersistedSessionLifecycleState.Failed("Session no longer exists.")
            is SessionViewStatus.Failed -> PersistedSessionLifecycleState.Failed(status.cause.message ?: "Session view failed.")
            SessionViewStatus.Closed -> PersistedSessionLifecycleState.Closed
        }
    private val mutableLifecycle = MutableStateFlow(lifecycleStatus(view.status.value))
    override val lifecycle: StateFlow<PersistedSessionLifecycleState> = mutableLifecycle.asStateFlow()

    init {
        owner.invokeOnCompletion {
            mutableAgent.value?.close(); mutableAgent.value = null
            mutableLifecycle.value = PersistedSessionLifecycleState.Closed
        }
        local.launch { view.status.collect { mutableLifecycle.value = lifecycleStatus(it) } }
        local.launch {
            var previous: RpcAgentPresentation? = initial
            view.presentation.collect { next ->
                if (next !== previous) {
                    mutableAgent.value?.close()
                    mutableAgent.value = next?.let { createRpcAgentViewModel(it, models, local) }
                    previous = next
                }
            }
        }
        local.launch {
            view.binding.collectLatest { binding ->
                binding?.settings?.collect { mutableSettings.value = it }
            }
        }
    }
    private fun current(): AgentViewModel {
        owner.ensureActive()
        val binding = view.current()
        // The observed child can lag one coordinator turn; never send a command through an old binding.
        check(view.presentation.value?.binding === binding)
        return requireNotNull(rootAgent.value).also { check(it.settings === binding.settings) }
    }
    override suspend fun refresh() { view.current() /* subscriptions own the settings projection */ }
    override suspend fun readCreatedAt(): Instant? = view.current().readCreatedAt()
    override suspend fun readUpdatedAt(): Instant? = view.current().readUpdatedAt()
    override suspend fun fork(source: AgentViewModel, untilExclusive: Int, expectedGeneration: Long): Int {
        require(source === current()) { "The history target expired." }
        return view.current().forkHistory(untilExclusive, expectedGeneration)
    }
    override suspend fun fork(): Int = services.global.forkSession(sessionIndex)
    override suspend fun rename(name: String): Unit = current().renameThread(name.trim())
    override suspend fun updateModel(model: OpenAiModelId): Unit = current().updateModel(model)
    override suspend fun updateWorkingDirectory(workingDirectory: Path): Unit = current().updateWorkingDirectory(workingDirectory)
    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit = current().updateReasoningEffort(reasoningEffort)
    override suspend fun updateServiceTier(serviceTier: ServiceTier): Unit = current().updateServiceTier(serviceTier)
    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit = current().updateRequestUserInputMode(mode)
    override suspend fun updateModelConfiguration(model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier): Unit =
        current().updateModelConfiguration(model, reasoningEffort, serviceTier)
    override suspend fun shutdown() { close(); join() }
    override fun close() { view.close(); owner.cancel() }
    public suspend fun join() { owner.join(); view.join() }
}
