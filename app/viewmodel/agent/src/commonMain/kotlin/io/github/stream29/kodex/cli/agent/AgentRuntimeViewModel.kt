package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.indexes
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.clearPending
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.app.agent.contract.AgentAddress
import io.github.stream29.kodex.app.agent.contract.AgentChildrenState
import io.github.stream29.kodex.app.agent.contract.AgentChildSlot
import io.github.stream29.kodex.app.agent.contract.AgentComposerSubmissionResult
import io.github.stream29.kodex.app.agent.contract.AgentExecutionCapabilities
import io.github.stream29.kodex.app.agent.contract.AgentExecutionPhase
import io.github.stream29.kodex.app.agent.contract.AgentExecutionState
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentHistoryTarget
import io.github.stream29.kodex.app.agent.contract.AgentLifecycleState
import io.github.stream29.kodex.app.agent.contract.AgentNotification
import io.github.stream29.kodex.app.agent.contract.AgentNotificationLevel
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.agent.contract.AgentStreamKind
import io.github.stream29.kodex.app.agent.contract.AgentStreamState
import io.github.stream29.kodex.app.agent.contract.AgentStreamTail
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerViewModelFactory
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.cli.sessiontitle.AgentTitleGeneration
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecProcessSession
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam

/** Global title controls resolved when a root Agent accepts its first user input. */
public data class AgentAutomaticTitleSettings(
    public val enabled: Boolean,
    public val model: OpenAiModelId?,
    public val reasoningEffort: ReasoningEffort = ReasoningEffort.Low,
)

public class AgentAutomaticTitleConfiguration(
    public val generator: SessionTitleGenerator,
    private val settingsProvider: () -> AgentAutomaticTitleSettings,
) {
    public fun currentSettings(): AgentAutomaticTitleSettings = settingsProvider()
}

/** Contract implementation for one exact open Agent session. */
internal class AgentRuntimeViewModel(
    private val session: KodexAgentSession,
    override val address: AgentAddress,
    override val parentAddress: AgentAddress?,
    private val scope: CoroutineScope,
    initialSettings: KodexAgentSettings,
    override val models: StateFlow<List<ModelInfo>>,
    override val composer: ComposerViewModel,
    override val history: AgentHistoryViewModel,
    private val automaticTitleConfiguration: AgentAutomaticTitleConfiguration? = null,
) : AgentViewModel {
    private val requestUserInputImpl = RequestUserInputViewModelImpl(
        runtime = session.runtime,
        ownerScope = scope,
        resumeRuntime = {
            launchRuntimeOperation("Unable to resume Agent.") {
                session.runtime.resume()
            }
        },
    )
    private val automaticTitle = AgentTitleGeneration(scope)
    private val commandMutex = Mutex()
    private val mutableSettings = MutableStateFlow(initialSettings)
    private val mutableTokenCount = MutableStateFlow<Long?>(null)
    private val mutableRuntimeOperation = MutableStateFlow<Job?>(null)
    private val mutableHistoryOperation = MutableStateFlow<Job?>(null)
    private val mutableExecution = MutableStateFlow(projectExecution(activityVersion = 0))
    private val mutableStream = MutableStateFlow(AgentStreamState())
    private val mutableChildren = MutableStateFlow<AgentChildrenState>(AgentChildrenState.Unloaded)
    private val mutableHistoryAction =
        MutableStateFlow<AgentHistoryActionState>(AgentHistoryActionState.None)
    private val mutableNotification = MutableStateFlow<AgentNotification?>(null)
    private val mutableLifecycle = MutableStateFlow<AgentLifecycleState>(AgentLifecycleState.Open)
    private var nextHistoryRequestId: Long = 1
    private var nextNotificationId: Long = 1
    private var streamRevision: Long = 0
    private var childrenRevision: Long = 0
    private var closed: Boolean = false

    override val requestUserInput: RequestUserInputViewModel = requestUserInputImpl
    override val shellSessions: AgentShellSessionRegistry =
        UnifiedExecShellSessionRegistry(session.runtime.unifiedExecToolClient.activeSessions, scope)
    override val settings: StateFlow<KodexAgentSettings> = mutableSettings.asStateFlow()
    override val tokenCount: StateFlow<Long?> = mutableTokenCount.asStateFlow()
    override val execution: StateFlow<AgentExecutionState> = mutableExecution.asStateFlow()
    override val stream: StateFlow<AgentStreamState> = mutableStream.asStateFlow()
    override val directChildren: StateFlow<AgentChildrenState> = mutableChildren.asStateFlow()
    override val historyAction: StateFlow<AgentHistoryActionState> =
        mutableHistoryAction.asStateFlow()
    override val notification: StateFlow<AgentNotification?> = mutableNotification.asStateFlow()
    override val lifecycle: StateFlow<AgentLifecycleState> = mutableLifecycle.asStateFlow()

    init {
        scope.launch {
            session.runtime.latestIndex.collect { index ->
                refreshDurableState(index)
                publishExecution()
                publishStream()
            }
        }
        scope.launch {
            session.runtime.state.collect { value ->
                requestUserInputImpl.synchronize(value.singleRequestUserInputOrNull())
                publishExecution()
                publishStream()
            }
        }
        scope.launch {
            session.runtime.runningTurn.collect {
                publishExecution()
            }
        }
        scope.launch {
            session.runtime.pendingSteer.collect {
                publishStream()
            }
        }
        scope.launch {
            session.subagents.entries.collect {
                val current = mutableChildren.value
                if (current !is AgentChildrenState.Unloaded) loadDirectChildren()
            }
        }
    }

    override suspend fun submit(content: List<ContentItem>) = runInOwnerScope {
        try {
            acceptNewTurn(content, "Unable to submit Agent content.")
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            publishFailure("Unable to submit Agent content.", failure)
            throw failure
        }
    }

    override suspend fun submitComposer(
        expectedRevision: Long,
    ): AgentComposerSubmissionResult = runInOwnerScope {
        ensureOpen()
        val current = composer.state.value
        if (current.revision != expectedRevision) {
            return@runInOwnerScope AgentComposerSubmissionResult.Stale
        }
        val text = current.text.trim()
        if (text.isEmpty()) return@runInOwnerScope AgentComposerSubmissionResult.Empty
        if (!composer.clear(expectedRevision)) {
            return@runInOwnerScope AgentComposerSubmissionResult.Stale
        }
        val content = listOf(ContentItem.InputText(text))
        try {
            if (execution.value.running) {
                session.runtime.pendingSteer.update { pending ->
                    pending + StableCleanEvent.UserMessage(content)
                }
                AgentComposerSubmissionResult.QueuedAsSteer
            } else {
                acceptNewTurn(content, "Unable to submit Agent composer.")
                AgentComposerSubmissionResult.Submitted
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            publishFailure("Unable to submit Agent composer.", failure)
            throw failure
        }
    }

    override fun resume() {
        ensureOpen()
        launchRuntimeOperation("Unable to resume Agent.") {
            session.runtime.resume()
        }
    }

    override fun cancel() {
        if (closed) return
        mutableRuntimeOperation.value?.cancel()
        session.runtime.runningTurn.value?.cancel()
    }

    override fun clearPending() {
        ensureOpen()
        launchOwnedOperation("Unable to clear pending tool calls.") {
            session.runtime.clearPending()
        }
    }

    override fun forceCompact() {
        ensureOpen()
        launchRuntimeOperation("Unable to compact Agent context.") {
            session.runtime.forcedCompact()
        }
    }

    override suspend fun updateModel(model: OpenAiModelId) {
        updateSettings { current -> current.copy(model = model) }
    }

    override suspend fun updateWorkingDirectory(workingDirectory: Path) {
        updateSettings { current -> current.copy(cwd = workingDirectory) }
    }

    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort) {
        updateSettings { current ->
            current.copy(reasoning = current.reasoning.copy(effort = reasoningEffort))
        }
    }

    override suspend fun updateServiceTier(serviceTier: ServiceTier) {
        updateSettings { current -> current.copy(serviceTier = serviceTier) }
    }

    override suspend fun updateAgentMode(agentMode: AgentMode) {
        updateSettings { current -> current.copy(agentMode = agentMode) }
    }

    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode) {
        updateSettings { current -> current.copy(requestUserInputMode = mode) }
    }

    override suspend fun updateModelConfiguration(
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
        serviceTier: ServiceTier,
    ) {
        updateSettings { current ->
            current.copy(
                model = model,
                reasoning = current.reasoning.copy(effort = reasoningEffort),
                serviceTier = serviceTier,
            )
        }
    }

    override suspend fun renameThread(threadName: String) {
        val normalized = threadName.trim()
        require(normalized.isNotEmpty()) { "An Agent thread name cannot be blank." }
        commandMutex.withLock {
            ensureOpen()
            automaticTitle.renameThread(session.runtime, normalized)
            refreshDurableState(session.runtime.latestIndex.value)
        }
    }

    override suspend fun loadDirectChildren() {
        ensureOpen()
        check(childrenRevision < Long.MAX_VALUE) { "Agent children revisions are exhausted." }
        childrenRevision += 1
        val revision = childrenRevision
        mutableChildren.value = AgentChildrenState.Loading(revision)
        try {
            val entries = session.subagents.listEntries()
            val slots = entries.map { entry ->
                val child = session.subagents.open(entry.entryIndex)
                val childState = child.runtime.state.value
                AgentChildSlot(
                    address = AgentAddress(address.sessionIndex, child.storage.id),
                    threadName = entry.threadName,
                    phase = childState.toExecutionPhase(),
                    running = child.runtime.runningTurn.value != null,
                    activityVersion = child.runtime.latestIndex.value.coerceAtLeast(0).toLong(),
                    hasChildren = child.subagents.entries.value.isNotEmpty(),
                )
            }
            mutableChildren.value = AgentChildrenState.Loaded(slots, revision)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            mutableChildren.value = AgentChildrenState.Failed(
                revision = revision,
                message = failure.message ?: failure.toString(),
            )
        }
    }

    override fun requestHistoryRevert(target: AgentHistoryTarget): Long {
        ensureOpen()
        val state = projectExecution(execution.value.activityVersion)
        require(!state.running && state.capabilities.canReplaceHistory) {
            "Cannot request a history revert in Agent phase ${state.phase}."
        }
        val window = history.window.value
        require(
            window.generation == target.generation &&
                window.entries.any { entry ->
                    entry.key.primaryStorageIndex == target.storageIndex
                },
        ) {
            "History revert target is no longer present in the current window."
        }
        val requestId = nextHistoryRequestId
        check(requestId < Long.MAX_VALUE) { "Agent history request ids are exhausted." }
        nextHistoryRequestId += 1
        mutableHistoryAction.value = AgentHistoryActionState.ConfirmRevert(requestId, target)
        return requestId
    }

    override fun dismissHistoryRevert(requestId: Long) {
        val current = mutableHistoryAction.value as? AgentHistoryActionState.ConfirmRevert ?: return
        if (current.requestId == requestId) {
            mutableHistoryAction.compareAndSet(current, AgentHistoryActionState.None)
        }
    }

    override fun confirmHistoryRevert(requestId: Long) {
        ensureOpen()
        val request = mutableHistoryAction.value as? AgentHistoryActionState.ConfirmRevert
            ?: return
        require(request.requestId == requestId) { "History revert request is stale." }
        val state = projectExecution(execution.value.activityVersion)
        require(!state.running && state.capabilities.canReplaceHistory) {
            "Cannot revert history in Agent phase ${state.phase}."
        }
        val window = history.window.value
        require(
            window.generation == request.target.generation &&
                window.entries.any { entry ->
                    entry.key.primaryStorageIndex == request.target.storageIndex
                },
        ) {
            "History revert target is no longer present in the current window."
        }
        val operation = scope.launch(start = CoroutineStart.LAZY) {
            try {
                executeHistoryRevert(request.target)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                publishFailure("Unable to revert Agent history.", failure)
            }
        }
        if (!mutableHistoryOperation.compareAndSet(null, operation)) {
            operation.cancel()
            throw IllegalStateException("This Agent already has a history operation.")
        }
        if (!mutableHistoryAction.compareAndSet(request, AgentHistoryActionState.None)) {
            mutableHistoryOperation.compareAndSet(operation, null)
            operation.cancel()
            return
        }
        clearNotification()
        operation.invokeOnCompletion {
            mutableHistoryOperation.compareAndSet(operation, null)
            publishExecution()
        }
        publishExecution()
        operation.start()
    }

    private suspend fun executeHistoryRevert(target: AgentHistoryTarget) {
        commandMutex.withLock {
            ensureOpen()
            require(target.generation == history.window.value.generation) {
                "History revert target generation is stale."
            }
            val value = session.runtime.state.value
            val runtimeRunning =
                session.runtime.runningTurn.value != null || mutableRuntimeOperation.value != null
            require(!runtimeRunning && value.canReplaceHistory) {
                "Cannot revert history in Agent phase ${value.toExecutionPhase()}."
            }
            automaticTitle.replaceHistory {
                session.runtime.modify { storage ->
                    require(
                        storage.stable.floorToIndex(target.storageIndex) == target.storageIndex,
                    ) {
                        "History entry ${target.storageIndex} is no longer committed."
                    }
                    val retainsAcceptedUserText =
                        storage.hasNonblankUserTextBefore(target.untilExclusive)
                    storage.revert(target.untilExclusive)
                    retainsAcceptedUserText
                }
            }
            session.runtime.pendingSteer.value = emptyList()
        }
    }

    override fun dismissNotification(notificationId: Long) {
        val current = mutableNotification.value ?: return
        if (current.id == notificationId) mutableNotification.compareAndSet(current, null)
    }

    override fun close() {
        if (closed) return
        closed = true
        mutableLifecycle.value = AgentLifecycleState.Closing
        requestUserInputImpl.close()
        composer.close()
        history.close()
        automaticTitle.close()
        mutableLifecycle.value = AgentLifecycleState.Closed
        scope.cancel()
    }

    private suspend fun updateSettings(
        transform: (KodexAgentSettings) -> KodexAgentSettings,
    ) {
        commandMutex.withLock {
            ensureOpen()
            val current = settings.value
            val updated = transform(current)
            if (updated == current) return@withLock
            if (updated.threadName != current.threadName) {
                automaticTitle.updateSettings(session.runtime, updated)
            } else {
                session.runtime.updateSettings(updated)
            }
            mutableSettings.value = updated
        }
    }

    private suspend fun refreshDurableState(index: Int) {
        if (index < 0) return
        mutableSettings.value = session.storage.settings[index]
        mutableTokenCount.value = session.storage.tokenCount[index]
    }

    private fun publishExecution() {
        val current = mutableExecution.value
        val projected = projectExecution(current.activityVersion)
        mutableExecution.value = if (
            projected.copy(activityVersion = current.activityVersion) ==
            current.copy(activityVersion = current.activityVersion)
        ) {
            current
        } else {
            projected.copy(activityVersion = current.activityVersion + 1)
        }
        if (projected.running) mutableHistoryAction.value = AgentHistoryActionState.None
    }

    private fun projectExecution(activityVersion: Long): AgentExecutionState {
        val value = session.runtime.state.value
        val cancelable =
            session.runtime.runningTurn.value != null || mutableRuntimeOperation.value != null
        val running = cancelable || mutableHistoryOperation.value != null
        return AgentExecutionState(
            phase = value.toExecutionPhase(),
            running = running,
            latestStorageIndex = session.runtime.latestIndex.value,
            activityVersion = activityVersion,
            capabilities = value.toCapabilities(running, cancelable),
        )
    }

    private suspend fun publishStream() {
        check(streamRevision < Long.MAX_VALUE) { "Agent stream revisions are exhausted." }
        val latestIndex = session.runtime.latestIndex.value
        val pending = if (
            latestIndex >= 0 &&
            session.storage.unstable.floorToIndex(latestIndex) != null
        ) {
            session.storage.unstable[latestIndex]
        } else {
            emptyList()
        }
        val projected = AgentStreamState(
            tail = session.runtime.state.value.toStreamTail(),
            pendingEvents = pending,
            pendingSteer = session.runtime.pendingSteer.value,
            revision = streamRevision,
        )
        if (projected.copy(revision = 0) != mutableStream.value.copy(revision = 0)) {
            streamRevision += 1
            mutableStream.value = projected.copy(revision = streamRevision)
        }
    }

    private suspend fun startAutomaticTitle(content: List<ContentItem>) {
        val configuration = automaticTitleConfiguration ?: return
        try {
            val titleSettings = configuration.currentSettings()
            automaticTitle.start(
                agentState = session.runtime,
                content = content,
                enabled = titleSettings.enabled,
                model = titleSettings.model,
                reasoningEffort = titleSettings.reasoningEffort,
                generator = configuration.generator,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            // Auxiliary title generation cannot reject an accepted user turn.
        }
    }

    private suspend fun acceptNewTurn(
        content: List<ContentItem>,
        failureMessage: String,
    ) {
        ensureOpen()
        require(content.isNotEmpty()) { "A submitted turn must contain content." }
        commandMutex.withLock {
            ensureOpen()
            require(!execution.value.running) {
                "Cannot submit a new turn while this Agent is running."
            }
            clearNotification()
            session.runtime.markNewTurn()
            session.runtime.appendUserMessage(content)
            launchOwnedOperation(
                failureMessage = failureMessage,
                runtimeOperation = true,
            ) {
                session.runtime.resume()
            }
            startAutomaticTitle(content)
        }
    }

    private fun launchRuntimeOperation(
        failureMessage: String,
        block: suspend () -> Unit,
    ) {
        launchOwnedOperation(
            failureMessage = failureMessage,
            runtimeOperation = true,
            block = block,
        )
    }

    private fun launchOwnedOperation(
        failureMessage: String,
        runtimeOperation: Boolean = false,
        block: suspend () -> Unit,
    ) {
        clearNotification()
        val operation = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                publishFailure(failureMessage, failure)
            }
        }
        if (runtimeOperation && !mutableRuntimeOperation.compareAndSet(null, operation)) {
            operation.cancel()
            throw IllegalStateException("This Agent already has a running operation.")
        }
        operation.invokeOnCompletion {
            if (runtimeOperation) {
                mutableRuntimeOperation.compareAndSet(operation, null)
                publishExecution()
            }
        }
        if (runtimeOperation) publishExecution()
        operation.start()
    }

    private suspend fun <T> runInOwnerScope(block: suspend () -> T): T =
        scope.async(start = CoroutineStart.UNDISPATCHED) { block() }.await()

    private fun clearNotification() {
        mutableNotification.value = null
    }

    private fun publishFailure(message: String, failure: Throwable) {
        val id = nextNotificationId
        check(id < Long.MAX_VALUE) { "Agent notification ids are exhausted." }
        nextNotificationId += 1
        mutableNotification.value = AgentNotification(
            id = id,
            level = AgentNotificationLevel.Error,
            message = message,
            detail = failure.stackTraceToString(),
        )
    }

    private fun ensureOpen() {
        check(!closed) { "Agent ViewModel is closed." }
    }
}

public suspend fun createAgentRuntimeViewModel(
    session: KodexAgentSession,
    address: AgentAddress,
    parentAddress: AgentAddress?,
    ownerScope: CoroutineScope,
    composerFactory: ComposerViewModelFactory,
    historyFactory: AgentRuntimeHistoryViewModelFactory,
    models: StateFlow<List<ModelInfo>> = MutableStateFlow(emptyList()),
    automaticTitleConfiguration: AgentAutomaticTitleConfiguration? = null,
): AgentViewModel {
    val latestIndex = session.runtime.latestIndex.value
    require(latestIndex >= 0) { "An Agent ViewModel requires an initialized Agent." }
    val childScope = ownerScope.supervisorChildScope()
    val composer = composerFactory.create()
    val history = try {
        historyFactory.create(session, childScope)
    } catch (failure: Throwable) {
        composer.close()
        childScope.cancel()
        throw failure
    }
    return AgentRuntimeViewModel(
        session = session,
        address = address,
        parentAddress = parentAddress,
        scope = childScope,
        initialSettings = session.storage.settings[latestIndex],
        models = models,
        composer = composer,
        history = history,
        automaticTitleConfiguration = automaticTitleConfiguration,
    )
}

/** History-child creator consumed by the Agent implementation boundary. */
public fun interface AgentRuntimeHistoryViewModelFactory {
    public fun create(
        session: KodexAgentSession,
        ownerScope: CoroutineScope,
    ): AgentHistoryViewModel
}

/** Exact runtime values for one materialized Agent definition. */
public data class AgentRuntimeViewModelArguments(
    public val session: KodexAgentSession,
    public val address: AgentAddress,
    public val parentAddress: AgentAddress?,
    public val ownerScope: CoroutineScope,
    public val models: StateFlow<List<ModelInfo>>,
    public val automaticTitleConfiguration: AgentAutomaticTitleConfiguration?,
)

/** Koin-resolved creator for one materialized Agent contract. */
@Factory
public class DefaultAgentRuntimeViewModelFactory(
    @InjectedParam private val arguments: AgentRuntimeViewModelArguments,
    private val composerFactory: ComposerViewModelFactory,
    @InjectedParam private val historyFactory: AgentRuntimeHistoryViewModelFactory,
) {
    public suspend fun create(): AgentViewModel =
        createAgentRuntimeViewModel(
            session = arguments.session,
            address = arguments.address,
            parentAddress = arguments.parentAddress,
            ownerScope = arguments.ownerScope,
            composerFactory = composerFactory,
            historyFactory = historyFactory,
            models = arguments.models,
            automaticTitleConfiguration = arguments.automaticTitleConfiguration,
        )
}

private class UnifiedExecShellSessionRegistry(
    source: StateFlow<Map<Int, UnifiedExecProcessSession>>,
    scope: CoroutineScope,
) : AgentShellSessionRegistry {
    override val activeSessions: StateFlow<Map<Int, AgentShellSession>> = source
        .map { sessions -> sessions.mapValues { (_, session) -> ShellSessionAdapter(session) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
}

private class ShellSessionAdapter(
    private val delegate: UnifiedExecProcessSession,
) : AgentShellSession {
    override val sessionId: Int get() = delegate.sessionId
    override val arguments get() = delegate.arguments
    override val completed: StateFlow<Boolean> get() = delegate.completed
    override fun close() = delegate.close()
}

private fun KodexAgentStateValue.toExecutionPhase(): AgentExecutionPhase = when (this) {
    KodexAgentStateValue.Empty -> AgentExecutionPhase.Empty
    KodexAgentStateValue.UserMessage -> AgentExecutionPhase.UserMessage
    is KodexAgentStateValue.RequestResponse -> AgentExecutionPhase.Responding
    KodexAgentStateValue.AssistantMessage -> AgentExecutionPhase.AssistantMessage
    is KodexAgentStateValue.ToolPending -> AgentExecutionPhase.ToolPending
    KodexAgentStateValue.ToolCompleted -> AgentExecutionPhase.ToolCompleted
    KodexAgentStateValue.ExternalWrite -> AgentExecutionPhase.ExternalWrite
    KodexAgentStateValue.Compacting -> AgentExecutionPhase.Compacting
}

private fun KodexAgentStateValue.toCapabilities(
    running: Boolean,
    cancelable: Boolean,
): AgentExecutionCapabilities = AgentExecutionCapabilities(
    canSubmit = !running && (
        this == KodexAgentStateValue.Empty ||
            this == KodexAgentStateValue.UserMessage ||
            this == KodexAgentStateValue.AssistantMessage ||
            this == KodexAgentStateValue.ToolCompleted
        ),
    canResume = !running && (
        this == KodexAgentStateValue.UserMessage ||
            this == KodexAgentStateValue.AssistantMessage ||
            this == KodexAgentStateValue.ToolCompleted ||
            this is KodexAgentStateValue.ToolPending
        ),
    canCancel = cancelable,
    canClearPending = !running && this is KodexAgentStateValue.ToolPending,
    canCompact = !running && (
        this == KodexAgentStateValue.UserMessage ||
            this == KodexAgentStateValue.AssistantMessage ||
            this == KodexAgentStateValue.ToolCompleted
        ),
    canReplaceHistory = !running && canReplaceHistory,
    canForkHistory = !running && canReplaceHistory,
)

private val KodexAgentStateValue.canReplaceHistory: Boolean
    get() = when (this) {
        KodexAgentStateValue.Empty,
        KodexAgentStateValue.UserMessage,
        KodexAgentStateValue.AssistantMessage,
        is KodexAgentStateValue.ToolPending,
        KodexAgentStateValue.ToolCompleted,
            -> true

        KodexAgentStateValue.ExternalWrite,
        is KodexAgentStateValue.RequestResponse,
        KodexAgentStateValue.Compacting,
            -> false
    }

private fun KodexAgentStateValue.toStreamTail(): AgentStreamTail? = when (this) {
    KodexAgentStateValue.RequestResponse.Started -> AgentStreamTail.Started
    is KodexAgentStateValue.RequestResponse.Message ->
        AgentStreamTail.Output(AgentStreamKind.Message, events)

    is KodexAgentStateValue.RequestResponse.AgentMessage ->
        AgentStreamTail.Output(AgentStreamKind.AgentMessage, events)

    is KodexAgentStateValue.RequestResponse.Reasoning ->
        AgentStreamTail.Output(AgentStreamKind.Reasoning, events)

    is KodexAgentStateValue.RequestResponse.ToolCall ->
        AgentStreamTail.Output(AgentStreamKind.ToolCall, events)

    is KodexAgentStateValue.RequestResponse.Unknown ->
        AgentStreamTail.Output(AgentStreamKind.Unknown, events)

    KodexAgentStateValue.Compacting -> AgentStreamTail.Compacting
    else -> null
}

private fun KodexAgentStateValue.singleRequestUserInputOrNull(): PendingRequestUserInputToolEvent? =
    (this as? KodexAgentStateValue.ToolPending)
        ?.events
        ?.singleOrNull() as? PendingRequestUserInputToolEvent

private suspend fun MutableKodexAgentStorage.hasNonblankUserTextBefore(
    untilExclusive: Int,
): Boolean = stable.indexes()
    .takeWhile { index -> index < untilExclusive }
    .firstOrNull { index ->
        val event = stable[index] as? StableCleanEvent.UserMessage
        event?.content?.any { content ->
            (content as? ContentItem.InputText)?.text?.isNotBlank() == true
        } == true
    } != null
