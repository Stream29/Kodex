package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path

/** One disposable renderable binding. Recovery publishes a different child, not a fake runtime. */
public fun createRpcAgentViewModel(
    presentation: RpcAgentPresentation,
    models: StateFlow<List<ModelInfo>>,
    scope: CoroutineScope,
): AgentViewModel = RpcAgentViewModel(presentation, models, scope)

private class RpcAgentViewModel(
    private val presentation: RpcAgentPresentation,
    override val models: StateFlow<List<ModelInfo>>,
    scope: CoroutineScope,
) : AgentViewModel {
    private val binding = presentation.binding
    private val owner = Job(scope.coroutineContext[Job])
    private val local = CoroutineScope(scope.coroutineContext + owner)
    override val storageUri = binding.storage.uri
    override val settings = binding.settings
    override val runtimeConfiguration = createBoundRuntimeConfigurationViewModel(this, local)
    override val state = binding.state
    override val running = binding.running
    override val latestIndex = binding.latestIndex
    override val composer = presentation.composer
    override val history = presentation.history
    override val historyIndex = presentation.historyIndex
    override val requestUserInput = presentation.requestUserInput
    override val suggestSubagentTask = presentation.suggestSubagentTask
    override val shellSessions = presentation.shellSessions
    override val pendingSteer = binding.pendingSteer
    override val historyAction = presentation.historyAction
    override val tokenCount: StateFlow<Long?> = binding.tokenCount.map { it?.totalTokens }
        .stateIn(local, SharingStarted.Eagerly, binding.tokenCount.value?.totalTokens)
    private val mutableLifecycle = MutableStateFlow<AgentLifecycleState>(AgentLifecycleState.Open)
    override val lifecycle = mutableLifecycle.asStateFlow()
    private val mutableNotification = MutableStateFlow<AgentNotification?>(null)
    override val notification = mutableNotification.asStateFlow()
    private var notificationId = 0L

    init {
        owner.invokeOnCompletion { mutableLifecycle.value = AgentLifecycleState.Closed }
        local.launch { presentation.failure.collect { if (it != null) report(it) } }
    }

    private fun report(error: Throwable) {
        mutableNotification.value = AgentNotification(
            ++notificationId, AgentNotificationLevel.Error, "Session operation failed.", error.message,
        )
    }
    private fun command(action: suspend () -> Unit) {
        if (!owner.isActive) return
        local.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { report(failure) }
        }
    }
    private suspend fun <F> edit(select: (KodexAgentSettings) -> F, replace: (KodexAgentSettings) -> KodexAgentSettings) {
        owner.ensureActive()
        binding.ensureActive()
        check(settings.editField(select(settings.value), select, replace) {
            owner.ensureActive(); binding.ensureActive()
        }) { "The edited field changed. Review the latest value." }
    }
    override suspend fun updateModel(model: OpenAiModelId): Unit =
        edit({ it.model }, { it.copy(model = model) })
    override suspend fun updateWorkingDirectory(workingDirectory: Path): Unit =
        edit({ it.cwd }, { it.copy(cwd = workingDirectory) })
    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit =
        edit({ it.reasoning.effort }, { it.copy(reasoning = it.reasoning.copy(effort = reasoningEffort)) })
    override suspend fun updateServiceTier(serviceTier: ServiceTier): Unit =
        edit({ it.serviceTier }, { it.copy(serviceTier = serviceTier) })
    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit =
        edit({ it.requestUserInputMode }, { it.copy(requestUserInputMode = mode) })
    override suspend fun updateModelConfiguration(model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier): Unit =
        edit({ Triple(it.model, it.reasoning.effort, it.serviceTier) }, {
            it.copy(model = model, reasoning = it.reasoning.copy(effort = reasoningEffort), serviceTier = serviceTier)
        })
    override suspend fun renameThread(threadName: String) {
        require(threadName.isNotBlank())
        edit({ it.threadName }, { it.copy(threadName = threadName) })
    }
    override suspend fun submit(content: List<ContentItem>): Unit = presentation.submit(content)
    override suspend fun submitComposer(expectedRevision: Long): AgentComposerSubmissionResult =
        presentation.submitComposer(expectedRevision)
    override fun resume(): Unit = presentation.resume()
    override fun cancel(): Unit = presentation.stop()
    override fun forceCompact(): Unit = presentation.forcedCompact()
    override fun clearPending(): Unit = presentation.clearPending()
    override fun requestHistoryRevert(untilExclusive: Int, expectedGeneration: Long): Long =
        presentation.requestHistoryRevert(untilExclusive, expectedGeneration)
    override suspend fun revertHistory(untilExclusive: Int, expectedGeneration: Long): Unit =
        binding.revertHistory(untilExclusive, expectedGeneration)
    override fun dismissHistoryRevert(requestId: Long): Unit = presentation.dismissHistoryRevert(requestId)
    override fun confirmHistoryRevert(requestId: Long): Unit = command { presentation.confirmHistoryRevert(requestId) }
    override fun dismissNotification(notificationId: Long) {
        mutableNotification.value?.takeIf { it.id == notificationId }?.let {
            mutableNotification.compareAndSet(it, null)
            presentation.dismissFailure()
        }
    }
    override fun close() { runtimeConfiguration.close(); owner.cancel() }
}
