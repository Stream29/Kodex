package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentLifecycleState
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerFailureReporter
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexDependencies
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDependencies
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.cli.agent.createHistoryIndexViewModel
import io.github.stream29.kodex.cli.agent.createRequestUserInputViewModel
import io.github.stream29.kodex.cli.history.AgentHistorySource
import io.github.stream29.kodex.cli.history.createAgentHistoryViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.utils.rpcexception.AgentRunFailureReported
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.ShellSessionNotRegistered
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

internal fun createRpcAgentViewModel(
    binding: RpcSessionBinding,
    scope: CoroutineScope,
    models: StateFlow<List<ModelInfo>>,
    reportUnhandledError: (Throwable) -> Unit,
): RpcAgentViewModel = RpcAgentViewModel(binding, scope, models, reportUnhandledError)

/** Original Agent behavior and children share one disposable, binding-scoped owner. */
internal class RpcAgentViewModel(
    internal val binding: RpcSessionBinding,
    scope: CoroutineScope,
    override val models: StateFlow<List<ModelInfo>>,
    private val reportUnhandledError: (Throwable) -> Unit,
) : AgentViewModel {
    private val owner = Job(scope.coroutineContext[Job])
    private val local = CoroutineScope(scope.coroutineContext + owner)
    override val settings = binding.settings
    override val state = binding.state
    override val running = binding.running
    override val latestIndex = binding.latestIndex
    override val pendingSteer = binding.pendingSteer
    override val runtimeConfiguration = createBoundRuntimeConfigurationViewModel(this, local)
    override val tokenCount: StateFlow<Long?> = binding.tokenCount.projectState { it?.totalTokens }
    private val mutableLifecycle = MutableStateFlow<AgentLifecycleState>(AgentLifecycleState.Open)
    override val lifecycle = mutableLifecycle.asStateFlow()
    override val composer: ComposerViewModel = createRpcComposerViewModel(
        binding = binding,
        ownerScope = local,
        failureReporter = ComposerFailureReporter { _, failure ->
            // Retain the failed draft, but report through the Application's local Hook outlet.
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
        AgentHistorySource(binding.storage, binding.latestIndex, binding.displayState, binding.storage.cacheNonce),
        CoroutineScope(local.coroutineContext + Job(owner)),
        binding.running,
    )
    override val historyIndex: HistoryIndexViewModel = createHistoryIndexViewModel(
        dependencies = object : HistoryIndexDependencies {
            override val storage = binding.storage
            override val latestIndex = binding.latestIndex
            override val cacheNonce = binding.storage.cacheNonce
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
            override val pending = binding.pendingAskUser
            override suspend fun submitAnswer(askId: Int, response: RequestUserInputResponse) {
                binding.submitAskUserAnswer(askId, response)
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
            historyIndex.close(); requestUserInput.close()
        }
        updateShells(binding.shellSessions.value)
        local.launch {
            binding.shellSessions.collect(::updateShells)
        }
    }

    private fun updateShells(snapshot: Map<Int, ShellSessionState>) {
        val previous = mutableShells.value
        mutableShells.value = snapshot.mapValues { (id, state) ->
            val handle = (previous[id] as? ShellView)?.takeIf { it.registrationNonce == state.registrationNonce }
                ?: ShellView(id, state.arguments, state.registrationNonce, state.exitCode)
            handle.observedExit.value = state.exitCode
            handle
        }
    }

    /** Return values do not write any observed backend state or overwrite a later draft revision. */
    override suspend fun submit(content: List<ContentItem>) {
        owner.ensureActive()
        binding.submit(content)
    }

    override fun resume(): Unit = operate { binding.resume() }
    override fun cancel(): Unit = operate { binding.stop() }
    override fun forceCompact(): Unit = operate { binding.forcedCompact() }
    override fun clearPending(): Unit = operate { binding.clearPending() }

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
        if (binding.storage.cacheNonce.value != nonce) throw CacheNonceMismatch()
        require(untilExclusive > 0 && untilExclusive.toLong() <= binding.latestIndex.value.toLong() + 1)
    }

    private fun operate(action: suspend () -> Unit) {
        if (!owner.isActive) return
        binding.ensureActive()
        command(action)
    }

    private fun report(error: Throwable) {
        if (!owner.isActive || error is CancellationException || error is AgentRunFailureReported) return
        reportUnhandledError(error)
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
        val registrationNonce: String,
        initialExitCode: Int?,
    ) : AgentShellSession {
        val observedExit = MutableStateFlow(initialExitCode)
        override val exitCode: StateFlow<Int?> = observedExit.asStateFlow()
        override val completed: StateFlow<Boolean> = exitCode.projectState { it != null }

        private fun isRegistered(): Boolean = mutableShells.value[sessionId] === this &&
            binding.shellSessions.value[sessionId]?.registrationNonce == registrationNonce

        private suspend fun requireRegistered() {
            currentCoroutineContext().ensureActive()
            owner.ensureActive()
            binding.ensureActive()
            if (!isRegistered()) throw ShellSessionNotRegistered()
        }

        override suspend fun readOutput(offset: ULong, limit: UInt): StdoutSlice {
            requireRegistered()
            val slice = binding.readShellSessionOutput(sessionId, offset, limit, registrationNonce)
            requireRegistered()
            return slice
        }

        override fun close() {
            if (!isRegistered() || exitCode.value != null) return
            operate {
                if (isRegistered() && binding.shellSessions.value[sessionId]?.exitCode == null) {
                    try {
                        binding.closeShellSession(sessionId, registrationNonce)
                    } catch (_: ShellSessionNotRegistered) {
                        // A menu action raced with removal; never terminate the replacement.
                    }
                }
            }
        }
    }
}
