package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.AgentLifecycleState
import io.github.stream29.kodex.app.agent.contract.AgentNotification
import io.github.stream29.kodex.app.agent.contract.AgentNotificationLevel
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerResumePort
import io.github.stream29.kodex.app.agent.contract.ComposerCancellationPort
import io.github.stream29.kodex.app.agent.contract.ComposerFailureReporter
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexDependencies
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDependencies
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskDependencies
import io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.cli.agent.createHistoryIndexViewModel
import io.github.stream29.kodex.cli.agent.createRequestUserInputViewModel
import io.github.stream29.kodex.cli.agent.createSuggestSubagentTaskViewModel
import io.github.stream29.kodex.cli.history.AgentHistorySource
import io.github.stream29.kodex.cli.history.createAgentHistoryViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSessionMeta
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.CreatedSuggestedSession
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

/** Creates the sole Agent ViewModel owned by this binding's frontend scope. */
public fun createRpcAgentViewModel(
    binding: RpcSessionBinding,
    scope: CoroutineScope,
    services: RpcServices,
    models: StateFlow<List<ModelInfo>>,
    onCreated: (List<CreatedSuggestedSession>) -> Unit,
): AgentViewModel = RpcAgentViewModel(binding, scope, services, models, onCreated)

/** Original Agent behavior and children share one disposable, binding-scoped owner. */
private class RpcAgentViewModel(
    private val binding: RpcSessionBinding,
    scope: CoroutineScope,
    private val services: RpcServices,
    override val models: StateFlow<List<ModelInfo>>,
    private val onCreated: (List<CreatedSuggestedSession>) -> Unit,
) : AgentViewModel {
    private val owner = Job(scope.coroutineContext[Job])
    private val local = CoroutineScope(scope.coroutineContext + owner)
    override val storageUri = binding.storage.uri
    override val settings = binding.settings
    override val state = binding.state
    override val running = binding.running
    override val latestIndex = binding.latestIndex
    override val pendingSteer = binding.pendingSteer
    override val runtimeConfiguration = createBoundRuntimeConfigurationViewModel(this, local)
    override val tokenCount: StateFlow<Long?> = binding.tokenCount.map { it?.totalTokens }
        .stateIn(local, SharingStarted.Eagerly, binding.tokenCount.value?.totalTokens)
    private val mutableLifecycle = MutableStateFlow<AgentLifecycleState>(AgentLifecycleState.Open)
    override val lifecycle = mutableLifecycle.asStateFlow()
    private val mutableNotification = MutableStateFlow<AgentNotification?>(null)
    override val notification = mutableNotification.asStateFlow()
    private var notificationId = 0L
    private val tierWarnings = UltrafastTierWarnings(
        binding.storage.tokenCount.cacheNonce.value,
        minOf(binding.latestIndex.value, binding.storage.tokenCount.latestIndex.value),
        binding.tokenCount.value?.diagnostics?.responseId,
    )
    override val composer: ComposerViewModel = createRpcComposerViewModel(
        binding = binding,
        ownerScope = local,
        resumePort = ComposerResumePort { resume() },
        cancellationPort = ComposerCancellationPort { cancel() },
        failureReporter = ComposerFailureReporter { _, failure ->
            // Composer retains its typed summary; the existing Agent notification is the host outlet.
            if (owner.isActive) report(IllegalStateException(failure.message))
        },
    )
    private val mutableHistoryAction = MutableStateFlow<AgentHistoryActionState>(AgentHistoryActionState.None)
    override val historyAction: StateFlow<AgentHistoryActionState> = mutableHistoryAction.asStateFlow()
    private var nextHistoryRequest = 1L
    private val mutableShells = MutableStateFlow<Map<Int, AgentShellSession>>(emptyMap())
    override val shellSessions: AgentShellSessionRegistry = object : AgentShellSessionRegistry {
        override val activeSessions: StateFlow<Map<Int, AgentShellSession>> = mutableShells.asStateFlow()
    }
    override val history: AgentHistoryViewModel = createAgentHistoryViewModel(
        AgentHistorySource(binding.storage, binding.latestIndex, binding.displayState, binding.storage.index.cacheNonce),
        CoroutineScope(local.coroutineContext + Job(owner)),
        binding.running,
    )
    override val historyIndex: HistoryIndexViewModel = createHistoryIndexViewModel(
        dependencies = object : HistoryIndexDependencies {
            override val timeline = binding.storage.index
            override val timestamp = binding.storage.timestamp
            override val latestIndex = binding.latestIndex
            override val cacheNonce = binding.storage.index.cacheNonce
            override val externalWrite = binding.displayState.projectState {
                it == io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue.ExternalWrite
            }
            override fun requestScrollToStorageIndex(index: Int) {
                binding.ensureActive()
                history.requestScrollToStorageIndex(index)
            }
        },
        ownerScope = local,
    )
    override val requestUserInput: RequestUserInputViewModel = createRequestUserInputViewModel(
        dependencies = object : RequestUserInputDependencies {
            override val pending = binding.state.map {
                (it as? AgentStateValue.ToolPending)?.events
                    ?.filterIsInstance<PendingRequestUserInputToolEvent>()?.firstOrNull()
            }
            override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int =
                binding.completeToolCall(completed)
            override fun resumeRuntime() { resume() }
        },
        ownerScope = local,
    )
    override val suggestSubagentTask: SuggestSubagentTaskViewModel = createSuggestSubagentTaskViewModel(
        dependencies = object : SuggestSubagentTaskDependencies {
            override val pending = binding.state.map {
                (it as? AgentStateValue.ToolPending)?.events
                    ?.filterIsInstance<PendingSuggestSubagentTaskToolEvent>()?.firstOrNull()
            }
            override val models: StateFlow<List<ModelInfo>> = this@RpcAgentViewModel.models
            override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int =
                binding.completeToolCall(completed)
            override suspend fun createSessions(
                arguments: SuggestSubagentTaskArgs,
                configuration: SuggestedSessionConfiguration,
            ): List<SuggestedSessionMeta> {
                binding.ensureActive()
                val current = binding.settings.value
                val initial = current.copy(
                    model = configuration.model,
                    reasoning = current.reasoning.copy(effort = configuration.reasoningEffort),
                    serviceTier = configuration.serviceTier,
                    cwd = configuration.cwd,
                    requestUserInputMode = configuration.requestUserInputMode,
                )
                return services.global.createSuggestedSessions(arguments.tasks, initial)
                    .also(onCreated).map { it.meta }
            }
            override fun resumeRuntime() { resume() }
            override fun defaultConfiguration(): SuggestedSessionConfiguration =
                binding.settings.value.let {
                    SuggestedSessionConfiguration(it.model, it.reasoning.effort, it.serviceTier, it.cwd, it.requestUserInputMode)
                }
        },
        ownerScope = local,
    )

    init {
        owner.invokeOnCompletion {
            mutableLifecycle.value = AgentLifecycleState.Closed
            runtimeConfiguration.close()
            composer.close()
            history.close()
            historyIndex.close(); requestUserInput.close(); suggestSubagentTask.close()
        }
        updateShells(binding.shellSessions.value)
        local.launch {
            binding.shellSessions.collect(::updateShells)
        }
        local.launch {
            val timeline = binding.storage.tokenCount
            combine(binding.latestIndex, timeline.cacheNonce, timeline.latestIndex) { position, nonce, _ ->
                position to nonce
            }.collect { (position, nonce) ->
                try {
                    val index = timeline.floorToIndex(position) ?: -1
                    val snapshot = if (index >= 0) timeline.getExact(index) else null
                    // A revert while reading must not associate an old record with a new epoch.
                    if (nonce == timeline.cacheNonce.value && position == binding.latestIndex.value) {
                        tierWarnings.observe(index, nonce, snapshot)?.let(::warnServiceTier)
                    }
                } catch (_: CacheNonceMismatch) {
                    // The existing nonce flow will publish the replacement epoch.
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    report(error)
                }
            }
        }
    }

    private fun updateShells(snapshot: Map<Int, ShellSessionState>) {
        val previous = mutableShells.value
        mutableShells.value = snapshot.mapValues { (id, state) ->
            val handle = (previous[id] as? ShellView)?.takeIf { it.arguments == state.arguments }
                ?: ShellView(id, state.arguments, state.completed)
            handle.completion.value = state.completed
            handle
        }
    }

    /** Return values do not write any observed backend state or overwrite a later draft revision. */
    override suspend fun submit(content: List<ContentItem>) {
        owner.ensureActive()
        binding.appendUserMessage(content)
        resume()
    }

    override fun resume(): Unit = operate { binding.resume() }
    override fun cancel(): Unit = operate { binding.stop() }
    override fun forceCompact(): Unit = operate { binding.forcedCompact() }
    override fun clearPending(): Unit = operate { binding.clearPending() }
    override fun dismissNotification(notificationId: Long) {
        mutableNotification.value?.takeIf { it.id == notificationId }?.let {
            mutableNotification.compareAndSet(it, null)
        }
    }

    override fun requestHistoryRevert(untilExclusive: Int, expectedGeneration: Long): Long {
        validateHistory(untilExclusive, expectedGeneration)
        check(nextHistoryRequest < Long.MAX_VALUE)
        val request = nextHistoryRequest++
        mutableHistoryAction.value = AgentHistoryActionState.ConfirmRevert(request, untilExclusive, expectedGeneration)
        return request
    }

    override fun dismissHistoryRevert(requestId: Long) {
        val current = mutableHistoryAction.value as? AgentHistoryActionState.ConfirmRevert ?: return
        if (current.requestId == requestId) mutableHistoryAction.compareAndSet(current, AgentHistoryActionState.None)
    }

    override fun confirmHistoryRevert(requestId: Long): Unit = command { confirmCapturedHistoryRevert(requestId) }

    private suspend fun confirmCapturedHistoryRevert(requestId: Long) {
        val captured = mutableHistoryAction.value as? AgentHistoryActionState.ConfirmRevert
            ?: error("No history confirmation is pending.")
        require(captured.requestId == requestId) { "History confirmation is stale." }
        validateHistory(captured.untilExclusive, captured.expectedGeneration)
        check(mutableHistoryAction.compareAndSet(captured, AgentHistoryActionState.None))
        binding.revertHistory(captured.untilExclusive, captured.expectedGeneration)
    }

    private fun validateHistory(untilExclusive: Int, nonce: Long) {
        owner.ensureActive()
        binding.ensureActive()
        check(!binding.running.value) { "The Agent is running." }
        check(binding.state.value !is AgentStateValue.RequestResponse &&
            binding.state.value != AgentStateValue.Compacting &&
            binding.state.value != AgentStateValue.ExternalWrite) { "History is not currently editable." }
        if (binding.storage.index.cacheNonce.value != nonce) throw CacheNonceMismatch()
        require(untilExclusive > 0 && untilExclusive.toLong() <= binding.latestIndex.value.toLong() + 1)
    }

    private fun operate(action: suspend () -> Unit) {
        if (!owner.isActive) return
        binding.ensureActive()
        command(action)
    }

    private fun report(error: Throwable) {
        if (!owner.isActive) return
        mutableNotification.value = AgentNotification(
            ++notificationId, AgentNotificationLevel.Error, "Session operation failed.", error.message,
        )
    }

    private fun warnServiceTier(detail: String) {
        if (!owner.isActive) return
        val current = mutableNotification.value
        if (current?.level == AgentNotificationLevel.Error) return
        mutableNotification.compareAndSet(
            current,
            AgentNotification(++notificationId, AgentNotificationLevel.Warning, "Ultrafast was not reported.", detail),
        )
    }

    private fun command(action: suspend () -> Unit) {
        if (!owner.isActive) return
        local.launch {
            try {
                action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                report(error)
            }
        }
    }

    private suspend fun <F> edit(
        select: (KodexAgentSettings) -> F,
        replace: (KodexAgentSettings) -> KodexAgentSettings,
    ) {
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
    override suspend fun updateModelConfiguration(
        model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier,
    ): Unit = edit({ Triple(it.model, it.reasoning.effort, it.serviceTier) }, {
        it.copy(model = model, reasoning = it.reasoning.copy(effort = reasoningEffort), serviceTier = serviceTier)
    })
    override suspend fun renameThread(threadName: String) {
        require(threadName.isNotBlank())
        edit({ it.threadName }, { it.copy(threadName = threadName) })
    }
    override suspend fun revertHistory(untilExclusive: Int, expectedGeneration: Long) {
        owner.ensureActive()
        binding.revertHistory(untilExclusive, expectedGeneration)
    }

    override fun close() {
        runtimeConfiguration.close()
        owner.cancel()
    }

    private inner class ShellView(
        override val sessionId: Int,
        override val arguments: ExecCommandArguments,
        completed: Boolean,
    ) : AgentShellSession {
        val completion = MutableStateFlow(completed)
        override val completed: StateFlow<Boolean> = completion.asStateFlow()
        override fun close() {
            if (mutableShells.value[sessionId] !== this) return
            operate { binding.closeShellSession(sessionId) }
        }
    }
}
