package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.app.agent.contract.AgentComposerSubmissionResult
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
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
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSessionMeta
import io.github.stream29.kodex.rpc.client.update
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.CreatedSuggestedSession
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Existing history and draft implementations, supplied solely with frontend values and commands. */
public class RpcAgentPresentation internal constructor(
    public val binding: RpcSessionBinding,
    scope: CoroutineScope,
    private val services: RpcServices,
    public val composer: ComposerViewModel,
    private val models: StateFlow<List<ModelInfo>>,
    private val onCreated: (List<CreatedSuggestedSession>) -> Unit,
) : AutoCloseable {
    private val owner = Job(scope.coroutineContext[Job])
    private val local = CoroutineScope(scope.coroutineContext + owner)
    private val submission = Mutex()
    private val mutableFailure = MutableStateFlow<Throwable?>(null)
    public val failure: StateFlow<Throwable?> = mutableFailure.asStateFlow()
    private val mutableHistoryAction = MutableStateFlow<AgentHistoryActionState>(AgentHistoryActionState.None)
    public val historyAction: StateFlow<AgentHistoryActionState> = mutableHistoryAction.asStateFlow()
    private var nextHistoryRequest = 1L
    private val mutableShells = MutableStateFlow<Map<Int, AgentShellSession>>(emptyMap())
    public val shellSessions: AgentShellSessionRegistry = object : AgentShellSessionRegistry {
        override val activeSessions: StateFlow<Map<Int, AgentShellSession>> = mutableShells.asStateFlow()
    }
    public val history: AgentHistoryViewModel = createAgentHistoryViewModel(
        AgentHistorySource(binding.storage, binding.latestIndex, binding.displayState, binding.storage.index.cacheNonce),
        CoroutineScope(local.coroutineContext + Job(owner)),
        binding.running,
    )
    public val historyIndex: HistoryIndexViewModel = createHistoryIndexViewModel(
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
    public val requestUserInput: RequestUserInputViewModel = createRequestUserInputViewModel(
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
    public val suggestSubagentTask: SuggestSubagentTaskViewModel = createSuggestSubagentTaskViewModel(
        dependencies = object : SuggestSubagentTaskDependencies {
            override val pending = binding.state.map {
                (it as? AgentStateValue.ToolPending)?.events
                    ?.filterIsInstance<PendingSuggestSubagentTaskToolEvent>()?.firstOrNull()
            }
            override val models: StateFlow<List<ModelInfo>> = this@RpcAgentPresentation.models
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
            history.close(); historyIndex.close(); requestUserInput.close(); suggestSubagentTask.close()
        }
        updateShells(binding.shellSessions.value)
        local.launch {
            binding.shellSessions.collect(::updateShells)
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
    public suspend fun submitComposer(expectedRevision: Long): AgentComposerSubmissionResult = submission.withLock {
        binding.ensureActive()
        val captured = composer.state.value
        if (captured.revision != expectedRevision) return AgentComposerSubmissionResult.Stale
        val text = captured.text.trim()
        if (text.isEmpty()) return AgentComposerSubmissionResult.Empty
        val content = listOf(ContentItem.InputText(text))
        if (binding.running.value) {
            binding.pendingSteer.update { it + StableUserMessage(content) }
            composer.clear(expectedRevision)
            AgentComposerSubmissionResult.QueuedAsSteer
        } else {
            binding.appendUserMessage(content)
            composer.clear(expectedRevision)
            resume()
            AgentComposerSubmissionResult.Submitted
        }
    }

    public suspend fun submit(content: List<ContentItem>) {
        binding.appendUserMessage(content)
        resume()
    }

    public fun resume(): Unit = operate { binding.resume() }
    public fun stop(): Unit = operate { binding.stop() }
    public fun forcedCompact(): Unit = operate { binding.forcedCompact() }
    public fun clearPending(): Unit = operate { binding.clearPending() }
    public fun dismissFailure() { mutableFailure.value = null }

    public fun requestHistoryRevert(untilExclusive: Int, selectedCacheNonce: Long): Long {
        validateHistory(untilExclusive, selectedCacheNonce)
        check(nextHistoryRequest < Long.MAX_VALUE)
        val request = nextHistoryRequest++
        mutableHistoryAction.value = AgentHistoryActionState.ConfirmRevert(request, untilExclusive, selectedCacheNonce)
        return request
    }

    public fun dismissHistoryRevert(requestId: Long) {
        val current = mutableHistoryAction.value as? AgentHistoryActionState.ConfirmRevert ?: return
        if (current.requestId == requestId) mutableHistoryAction.compareAndSet(current, AgentHistoryActionState.None)
    }

    public suspend fun confirmHistoryRevert(requestId: Long) {
        val captured = mutableHistoryAction.value as? AgentHistoryActionState.ConfirmRevert
            ?: error("No history confirmation is pending.")
        require(captured.requestId == requestId) { "History confirmation is stale." }
        validateHistory(captured.untilExclusive, captured.expectedGeneration)
        check(mutableHistoryAction.compareAndSet(captured, AgentHistoryActionState.None))
        binding.revertHistory(captured.untilExclusive, captured.expectedGeneration)
    }

    private fun validateHistory(untilExclusive: Int, nonce: Long) {
        binding.ensureActive()
        check(!binding.running.value) { "The Agent is running." }
        check(binding.state.value !is AgentStateValue.RequestResponse &&
            binding.state.value != AgentStateValue.Compacting &&
            binding.state.value != AgentStateValue.ExternalWrite) { "History is not currently editable." }
        if (binding.storage.index.cacheNonce.value != nonce) throw CacheNonceMismatch()
        require(untilExclusive > 0 && untilExclusive.toLong() <= binding.latestIndex.value.toLong() + 1)
    }

    private fun operate(action: suspend () -> Unit) {
        binding.ensureActive()
        local.launch {
            try {
                action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                mutableFailure.value = error
            }
        }
    }

    override fun close() { owner.cancel() }

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
