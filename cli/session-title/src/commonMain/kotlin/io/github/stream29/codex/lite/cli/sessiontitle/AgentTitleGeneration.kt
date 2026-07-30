package io.github.stream29.codex.lite.cli.sessiontitle

import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ReasoningEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory one-shot title generation for one Agent runtime.
 *
 * This object only addresses [CodexAgentState]. It has no Session identifier,
 * catalog, or UI selection state.
 */
public class AgentTitleGeneration(
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val mutex = Mutex()
    private var consumed: Boolean = false
    private var nextAttemptId: Long = 1L
    private var activeAttemptId: Long? = null
    private var activeJob: Job? = null

    /**
     * Starts one best-effort request for the first nonblank text input.
     *
     * A disabled setting or a non-default thread name consumes the first text
     * without starting a request. Image-only input remains eligible.
     */
    public suspend fun start(
        agentState: CodexAgentState,
        content: List<ContentItem>,
        enabled: Boolean,
        model: OpenAiModelId?,
        reasoningEffort: ReasoningEffort,
        generator: SessionTitleGenerator,
    ): Boolean {
        val userText = content.firstNonblankInputText() ?: return false
        return mutex.withLock {
            if (consumed) return@withLock false
            consumed = true
            if (!enabled) return@withLock false

            val latestIndex = agentState.latestIndex.value
            if (latestIndex < 0) return@withLock false
            val expectedThreadName = agentState.storage.settings[latestIndex].threadName
            if (!expectedThreadName.isDefaultSessionTitle()) return@withLock false

            val attemptId = nextAttemptId++
            activeAttemptId = attemptId
            activeJob = scope.launch {
                try {
                    val result = generator.generateTitle(
                        userText = userText,
                        model = model ?: DefaultSessionTitleModel,
                        reasoningEffort = reasoningEffort,
                    )
                    val generated = result as? SessionTitleGenerationResult.Generated ?: return@launch
                    persistIfCurrent(
                        agentState = agentState,
                        attemptId = attemptId,
                        expectedThreadName = expectedThreadName,
                        title = generated.title,
                    )
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Throwable) {
                    // A title is optional and must never fail the user turn.
                } finally {
                    mutex.withLock {
                        if (activeAttemptId == attemptId) {
                            activeAttemptId = null
                            activeJob = null
                        }
                    }
                }
            }
            true
        }
    }

    /** Cancels any pending automatic result and permanently consumes this Agent's chance. */
    public suspend fun suppress() {
        mutex.withLock { invalidateLocked() }
    }

    /** Serializes an explicit rename ahead of any generated title. */
    public suspend fun renameThread(
        agentState: CodexAgentState,
        threadName: String,
    ): Int = mutex.withLock {
        invalidateLocked()
        agentState.updateSettings(agentState.latestSettings().copy(threadName = threadName))
    }

    /** Serializes an explicit thread-name settings update ahead of generated output. */
    public suspend fun updateSettings(
        agentState: CodexAgentState,
        settings: CodexAgentSettings,
    ): Int = mutex.withLock {
        invalidateLocked()
        agentState.updateSettings(settings)
    }

    override fun close() {
        activeJob?.cancel()
    }

    private suspend fun persistIfCurrent(
        agentState: CodexAgentState,
        attemptId: Long,
        expectedThreadName: String,
        title: String,
    ) {
        agentState.state.first(CodexAgentStateValue::allowsTitleUpdate)
        mutex.withLock {
            if (activeAttemptId != attemptId) return
            val currentSettings = agentState.latestSettings()
            if (currentSettings.threadName != expectedThreadName) return
            agentState.updateSettings(currentSettings.copy(threadName = title))
        }
    }

    private fun invalidateLocked() {
        consumed = true
        activeAttemptId = null
        activeJob?.cancel()
        activeJob = null
    }
}

private suspend fun CodexAgentState.latestSettings(): CodexAgentSettings {
    val latestIndex = latestIndex.value
    require(latestIndex >= 0) { "An uninitialized Agent has no settings." }
    return storage.settings[latestIndex]
}

private fun List<ContentItem>.firstNonblankInputText(): String? {
    for (item in this) {
        val text = (item as? ContentItem.InputText)?.text
        if (!text.isNullOrBlank()) return text
    }
    return null
}

private fun String.isDefaultSessionTitle(): Boolean = DefaultSessionTitlePattern.matches(this)

private fun CodexAgentStateValue.allowsTitleUpdate(): Boolean = when (this) {
    CodexAgentStateValue.ExternalWrite,
    is CodexAgentStateValue.RequestResponse,
    CodexAgentStateValue.Compacting,
    -> false

    CodexAgentStateValue.Empty,
    CodexAgentStateValue.UserMessage,
    CodexAgentStateValue.AssistantMessage,
    is CodexAgentStateValue.ToolPending,
    CodexAgentStateValue.ToolCompleted,
    -> true
}

private val DefaultSessionTitlePattern: Regex = Regex("Session [0-9]+")
