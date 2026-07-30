package io.github.stream29.codex.lite.cli.agent

import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.contract.clearPending
import io.github.stream29.codex.lite.cli.sessiontitle.AgentTitleGeneration
import io.github.stream29.codex.lite.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Frontend-facing projection of one open [CodexAgentSession]'s runtime. */
public data class AgentRuntimeViewState(
    public val agentId: String,
    public val latestIndex: Int,
    public val agentState: CodexAgentStateValue,
    public val running: Boolean = false,
    /** Latest UI-originated operation failure. `null` means no failure is currently reported. */
    public val failureMessage: String? = null,
    /** Full stack trace for the latest UI-originated operation failure. */
    public val failureStackTrace: String? = null,
    /** Persisted facts visible at [latestIndex]. */
    public val durable: AgentDurableViewState = AgentDurableViewState(),
)

/** Storage-backed state that remains valid after a streaming output has been committed. */
public data class AgentDurableViewState(
    public val settings: CodexAgentSettings? = null,
    public val tokenCount: Long? = null,
)

/** Semantic kind of the current typed Responses output state. */
public enum class AgentStreamKind {
    Message,
    AgentMessage,
    Reasoning,
    ToolCall,
    Unknown,
}

/** Global title controls resolved at the point an Agent accepts its first user input. */
public data class AgentAutomaticTitleSettings(
    public val enabled: Boolean,
    public val model: OpenAiModelId?,
    public val reasoningEffort: ReasoningEffort = ReasoningEffort.Low,
)

/**
 * Composition supplied only to a root Agent runtime.
 *
 * The configuration is deliberately free of Session identifiers and catalog
 * state. The runtime reads it after accepting input, then delegates all title
 * state and writes to its own [io.github.stream29.codex.lite.agentstate.contract.CodexAgentState].
 */
public class AgentAutomaticTitleConfiguration(
    public val generator: SessionTitleGenerator,
    private val settingsProvider: () -> AgentAutomaticTitleSettings,
) {
    public fun currentSettings(): AgentAutomaticTitleSettings = settingsProvider()
}

/**
 * Owns only frontend projection state for one open Agent runtime.
 *
 * It owns collection of [io.github.stream29.codex.lite.agentruntime.contract.AgentRuntime.resume]
 * only for UI-originated turns. The runtime remains the owner of state transitions, tool routing,
 * continuations, and mutual exclusion. Completed output is read through [latestIndex] by the
 * session/history projection. The history renderer directly subscribes to
 * the active output's replay flow.
 */
public class AgentRuntimeViewModel internal constructor(
    public val session: CodexAgentSession,
    private val scope: CoroutineScope,
    private val automaticTitleConfiguration: AgentAutomaticTitleConfiguration? = null,
) : AutoCloseable {
    /** Draft editor state for this exact Agent. It is never persisted into the Agent timeline. */
    public val composer: ComposerViewModel = ComposerViewModel()
    private val automaticTitle = AgentTitleGeneration(scope)

    /** Answer draft for this Agent's one host-owned pending `request_user_input` call. */
    public val requestUserInput: RequestUserInputViewModel = RequestUserInputViewModel()

    private val mutableState = MutableStateFlow(
        AgentRuntimeViewState(
            agentId = session.storage.id,
            latestIndex = session.runtime.latestIndex.value,
            agentState = session.runtime.state.value,
            running = session.runtime.runningTurn.value != null,
        ),
    )

    public val state: StateFlow<AgentRuntimeViewState> = mutableState.asStateFlow()

    init {
        scope.launch {
            session.runtime.latestIndex.collect { latestIndex ->
                mutableState.update { current -> current.copy(latestIndex = latestIndex) }
                refreshDurableState(latestIndex)
            }
        }
        scope.launch {
            session.runtime.state.collect(::acceptAgentState)
        }
        scope.launch {
            session.runtime.runningTurn.collect { turn ->
                mutableState.update { current -> current.copy(running = turn != null) }
            }
        }
    }

    override fun close() {
        automaticTitle.close()
        scope.cancel()
    }

    /** Appends a new user turn, then starts this Agent's runtime directly. */
    public suspend fun submit(content: List<ContentItem>) {
        require(content.isNotEmpty()) { "A submitted turn must contain content." }
        clearFailure()
        session.runtime.markNewTurn()
        session.runtime.appendUserMessage(content)
        resume()
        startAutomaticTitle(content)
    }

    /** Consumes this Agent's text draft and starts a turn for this exact Agent. */
    public suspend fun submitComposer(): Boolean {
        val text = composer.takeText() ?: return false
        submit(listOf(ContentItem.InputText(text)))
        return true
    }

    /** Starts or continues this Agent's runtime. Runtime-level concurrency checks remain authoritative. */
    public fun resume(): Job = scope.launch {
        clearFailure()
        session.runtime.resume().collect()
    }

    /** Cancels this Agent's active runtime turn when one exists. */
    public fun cancel() {
        session.runtime.runningTurn.value?.cancel()
    }

    /** Fails every pending local tool call so the persisted conversation can continue safely. */
    public fun clearPending(): Job = scope.launch {
        clearFailure()
        try {
            session.runtime.clearPending()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            recordFailure(failure)
        }
    }

    /** Completes this Agent's pending `request_user_input` call, then resumes the same runtime. */
    public fun submitRequestUserInput(): Job? {
        val submission = requestUserInput.beginSubmission() ?: return null
        clearFailure()
        return scope.launch {
            try {
                session.runtime.completeToolCall(
                    StableRequestUserInputToolEvent(
                        callId = submission.pending.callId,
                        itemId = submission.pending.itemId,
                        arguments = submission.pending.arguments,
                        result = StableRequestUserInputResult.Answered(submission.response),
                    ),
                )
                requestUserInput.completeSubmission(submission.pending.callId)
                resume()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                requestUserInput.failSubmission(submission.pending.callId, failure)
                recordFailure(failure)
            }
        }
    }

    /** Commits a complete settings snapshot through the AgentState atomic update API. */
    public suspend fun updateSettings(
        transform: (CodexAgentSettings) -> CodexAgentSettings,
    ): Int {
        val current = requireNotNull(state.value.durable.settings) {
            "An uninitialized Agent has no settings to update."
        }
        val updated = transform(current)
        return if (updated.threadName != current.threadName) {
            automaticTitle.updateSettings(session.runtime, updated)
        } else {
            session.runtime.updateSettings(updated)
        }
    }

    /** Persists one nonblank title for this exact Agent runtime. */
    public suspend fun renameThread(threadName: String): Int {
        val normalized = threadName.trim()
        require(normalized.isNotEmpty()) { "An Agent thread name cannot be blank." }
        val renamedAt = automaticTitle.renameThread(session.runtime, normalized)
        val latestIndex = session.runtime.latestIndex.value
        mutableState.update { current ->
            if (current.latestIndex <= latestIndex) current.copy(latestIndex = latestIndex) else current
        }
        refreshDurableState(latestIndex)
        return renamedAt
    }

    /** Prevents a virtual or explicit user title from being overwritten later. */
    public suspend fun suppressAutomaticTitle() {
        automaticTitle.suppress()
    }

    private fun clearFailure() {
        mutableState.update { current -> current.copy(failureMessage = null, failureStackTrace = null) }
    }

    private suspend fun startAutomaticTitle(content: List<ContentItem>) {
        val configuration = automaticTitleConfiguration ?: return
        try {
            val settings = configuration.currentSettings()
            automaticTitle.start(
                agentState = session.runtime,
                content = content,
                enabled = settings.enabled,
                model = settings.model,
                reasoningEffort = settings.reasoningEffort,
                generator = configuration.generator,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            // Title generation is optional; an auxiliary setup failure must not reject the user turn.
        }
    }

    private fun recordFailure(failure: Throwable) {
        mutableState.update { current ->
            current.copy(
                failureMessage = failure.message ?: failure.toString(),
                failureStackTrace = failure.stackTraceToString(),
            )
        }
    }

    /** Re-reads durable storage at the runtime's currently published snapshot boundary. */
    public suspend fun refreshDurableState() {
        refreshDurableState(session.runtime.latestIndex.value)
    }

    private fun acceptAgentState(agentState: CodexAgentStateValue) {
        requestUserInput.synchronize(agentState.singleRequestUserInputOrNull())
        mutableState.update { current ->
            current.copy(agentState = agentState)
        }
    }

    private suspend fun refreshDurableState(latestIndex: Int) {
        val durable = if (latestIndex < 0) {
            AgentDurableViewState()
        } else {
            AgentDurableViewState(
                settings = session.storage.settings[latestIndex],
                tokenCount = session.storage.tokenCount[latestIndex],
            )
        }
        mutableState.update { current ->
            if (current.latestIndex == latestIndex) current.copy(durable = durable) else current
        }
    }
}

/** Creates an [AgentRuntimeViewModel] whose collection lifecycle is a child of this scope. */
public fun CoroutineScope.AgentRuntimeViewModel(
    session: CodexAgentSession,
    automaticTitleConfiguration: AgentAutomaticTitleConfiguration? = null,
): AgentRuntimeViewModel =
    AgentRuntimeViewModel(
        session = session,
        scope = supervisorChildScope(),
        automaticTitleConfiguration = automaticTitleConfiguration,
    )

private fun CodexAgentStateValue.singleRequestUserInputOrNull(): PendingRequestUserInputToolEvent? =
    (this as? CodexAgentStateValue.ToolPending)
        ?.events
        ?.singleOrNull() as? PendingRequestUserInputToolEvent
