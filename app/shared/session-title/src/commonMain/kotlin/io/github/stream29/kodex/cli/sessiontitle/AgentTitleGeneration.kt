package io.github.stream29.kodex.cli.sessiontitle

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.updateThreadName
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory one-shot title generation for one Agent runtime.
 *
 * This object only addresses [KodexAgentState]. It has no Session identifier,
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
        agentState: KodexAgentState,
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
        agentState: KodexAgentState,
        threadName: String,
    ): Int = mutex.withLock {
        invalidateLocked()
        agentState.updateThreadName(threadName)
    }

    /** Serializes an explicit thread-name settings update ahead of generated output. */
    public suspend fun updateSettings(
        agentState: KodexAgentState,
        settings: KodexAgentSettings,
    ): Int = mutex.withLock {
        invalidateLocked()
        agentState.updateSettings(settings)
    }

    override fun close() {
        activeJob?.cancel()
    }

    private suspend fun persistIfCurrent(
        agentState: KodexAgentState,
        attemptId: Long,
        expectedThreadName: String,
        title: String,
    ) {
        mutex.withLock {
            if (activeAttemptId != attemptId) return
            val currentIndex = agentState.latestIndex.value
            if (agentState.storage.settings[currentIndex].threadName != expectedThreadName) return
            agentState.updateThreadName(title)
        }
    }

    private fun invalidateLocked() {
        consumed = true
        activeAttemptId = null
        activeJob?.cancel()
        activeJob = null
    }
}

private fun List<ContentItem>.firstNonblankInputText(): String? {
    for (item in this) {
        val text = (item as? ContentItem.InputText)?.text
        if (!text.isNullOrBlank()) return text
    }
    return null
}

private fun String.isDefaultSessionTitle(): Boolean = DefaultSessionTitlePattern.matches(this)

private val DefaultSessionTitlePattern: Regex = Regex("Session [0-9]+")
