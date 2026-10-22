@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

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
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public actual class AgentTitleGeneration actual constructor(
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val mutex = Mutex()
    private var consumed: Boolean = false
    private var nextAttemptId: Long = 1L
    private var activeAttemptId: Long? = null
    private var activeJob: Job? = null

    public actual suspend fun start(
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

    public actual suspend fun suppress() {
        mutex.withLock { invalidateLocked() }
    }

    public actual suspend fun replaceHistory(replacement: suspend () -> Boolean) {
        mutex.withLock {
            val previouslyConsumed = consumed
            invalidateAttemptLocked()
            consumed = true
            try {
                consumed = replacement()
            } catch (failure: Throwable) {
                consumed = previouslyConsumed
                throw failure
            }
        }
    }

    public actual suspend fun renameThread(
        agentState: KodexAgentState,
        threadName: String,
    ): Int = mutex.withLock {
        invalidateLocked()
        agentState.updateThreadName(threadName)
    }

    public actual suspend fun updateSettings(
        agentState: KodexAgentState,
        settings: KodexAgentSettings,
    ): Int = mutex.withLock {
        invalidateLocked()
        agentState.updateSettings(settings)
    }

    actual override fun close() {
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
            while (true) {
                val current = agentState.storage.settings[agentState.latestIndex.value]
                if (current.threadName != expectedThreadName) return
                if (agentState.compareAndSetSettings(current, current.copy(threadName = title))) return
                // Another field may have changed. Retain this generated title,
                // re-read the actual backend value and do not call the model again.
                yield()
            }
        }
    }

    private fun invalidateLocked() {
        consumed = true
        invalidateAttemptLocked()
    }

    private fun invalidateAttemptLocked() {
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
