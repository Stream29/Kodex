@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.sessiontitle

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

public actual fun attachSessionTitleGeneration(
    scope: CoroutineScope,
    agentState: KodexAgentState,
    settings: () -> SessionTitleSettings,
    generator: SessionTitleGenerator,
): Job {
    val controller = SessionTitleGenerationController(
        scope = scope,
        agentState = agentState,
        settings = settings,
        generator = generator,
    )
    return controller.attach()
}

private class SessionTitleGenerationController(
    private val scope: CoroutineScope,
    private val agentState: KodexAgentState,
    private val settings: () -> SessionTitleSettings,
    private val generator: SessionTitleGenerator,
) {
    private val observableStorage = agentState.storage as? ObservableKodexAgentStorage
    private val mutex = Mutex()
    private val attachNonce: Long = observableStorage?.cacheNonce?.value ?: 0L
    private val attachIndex: Int = agentState.latestIndex.value
    private var initialized = false
    private var observedNonce: Long = attachNonce
    private var scannedIndex: Int = -1
    private var consumed: Boolean = false
    private var consumedSource: ConsumedTextSource? = null
    private var nextAttemptId: Long = 1L
    private var activeAttemptId: Long? = null
    private var activeJob: Job? = null

    fun attach(): Job {
        val nonceFlow = observableStorage?.cacheNonce ?: flowOf(0L)
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                combine(nonceFlow, agentState.latestIndex) { nonce, latest ->
                    nonce to latest
                }.collect { (nonce, latest) ->
                    onStorageState(nonce, latest)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                currentCoroutineContext().ensureActive()
                // Automatic title generation is optional and never fails the session or turn.
            } finally {
                activeJob?.cancel()
            }
        }
    }

    private suspend fun onStorageState(nonce: Long, latest: Int) {
        mutex.withLock {
            if (!initialized) {
                initialized = true
                observedNonce = attachNonce
                if (nonce == attachNonce) {
                    val existing = findFirstNonblankUserText(0..attachIndex.coerceAtMost(latest))
                    if (existing != null) {
                        consumed = true
                        consumedSource = existing.source
                    }
                    scannedIndex = attachIndex.coerceAtMost(latest)
                    if (latest <= scannedIndex) return
                } else {
                    scannedIndex = attachIndex
                }
            }

            if (nonce != observedNonce || latest < scannedIndex) {
                observedNonce = nonce
                invalidateAttemptLocked()
                val actualLatest = agentState.storage.latestIndex()
                val retainedOrReplacement = findFirstNonblankUserText(0..actualLatest)
                scannedIndex = actualLatest
                if (retainedOrReplacement == null) {
                    consumed = false
                    consumedSource = null
                    return
                }
                if (consumed && retainedOrReplacement.source.matchesRetained(consumedSource)) {
                    return
                }
                consumed = true
                consumedSource = retainedOrReplacement.source
                maybeStartGenerationLocked(
                    userText = retainedOrReplacement.text,
                    expectedNonce = nonce,
                    latestIndex = actualLatest,
                )
                return
            }

            if (latest <= scannedIndex) {
                scannedIndex = latest
                return
            }

            val fromIndex = scannedIndex + 1
            scannedIndex = latest
            if (consumed) {
                if (consumedSource is ConsumedTextSource.Steer) {
                    findFirstCommittedUserMessageText(fromIndex..latest)?.let { committed ->
                        consumedSource = committed.source
                    }
                }
                return
            }

            val candidate = findFirstNonblankUserText(fromIndex..latest) ?: return
            consumed = true
            consumedSource = candidate.source
            maybeStartGenerationLocked(
                userText = candidate.text,
                expectedNonce = nonce,
                latestIndex = latest,
            )
        }
    }

    private suspend fun maybeStartGenerationLocked(
        userText: String,
        expectedNonce: Long,
        latestIndex: Int,
    ) {
        if (latestIndex < 0) return
        val currentSettings = settings()
        if (!currentSettings.enabled) return
        val expectedThreadName = agentState.storage.getSettingsAt(latestIndex).threadName
        if (!expectedThreadName.isDefaultSessionTitle()) return

        val attemptId = nextAttemptId++
        activeAttemptId = attemptId
        activeJob = scope.launch {
            try {
                val result = generator.generateTitle(
                    userText = userText,
                    model = currentSettings.model ?: DefaultSessionTitleModel,
                    reasoningEffort = currentSettings.reasoningEffort,
                )
                val generated = result as? SessionTitleGenerationResult.Generated ?: return@launch
                persistIfCurrent(
                    attemptId = attemptId,
                    expectedNonce = expectedNonce,
                    expectedThreadName = expectedThreadName,
                    title = generated.title,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Automatic title generation is optional and never fails the session or turn.
            } finally {
                mutex.withLock {
                    if (activeAttemptId == attemptId) {
                        activeAttemptId = null
                        activeJob = null
                    }
                }
            }
        }
    }

    private suspend fun persistIfCurrent(
        attemptId: Long,
        expectedNonce: Long,
        expectedThreadName: String,
        title: String,
    ) {
        mutex.withLock {
            if (activeAttemptId != attemptId || observedNonce != expectedNonce) return
            while (true) {
                if (observableStorage != null && observableStorage.cacheNonce.value != expectedNonce) return
                val latest = agentState.latestIndex.value
                if (latest < 0) return
                val current = agentState.storage.getSettingsAt(latest)
                if (current.threadName != expectedThreadName) return
                if (agentState.compareAndSetSettings(current, current.copy(threadName = title))) return
                yield()
            }
        }
    }

    private fun invalidateAttemptLocked() {
        activeAttemptId = null
        activeJob?.cancel()
        activeJob = null
    }

    private suspend fun findFirstCommittedUserMessageText(range: IntRange): FoundUserText? {
        if (range.isEmpty() || range.last < 0) return null
        val bounded = range.first.coerceAtLeast(0)..range.last
        if (bounded.isEmpty()) return null
        val commits = agentState.storage.getCommitsIn(bounded, setOf(AgentCommitType.UserMessage))
        for (indexed in commits) {
            val message = indexed.commit as? StableUserMessage ?: continue
            val text = message.content.firstNonblankInputText() ?: continue
            return FoundUserText(
                text = text,
                source = ConsumedTextSource.CommittedMessage(indexed.index, message),
            )
        }
        return null
    }

    private suspend fun findFirstNonblankUserText(range: IntRange): FoundUserText? {
        if (range.isEmpty() || range.last < 0) return null
        val bounded = range.first.coerceAtLeast(0)..range.last
        if (bounded.isEmpty()) return null
        val commits = agentState.storage.getCommitsIn(
            bounded,
            setOf(AgentCommitType.UserMessage, AgentCommitType.Steer),
        )
        for (indexed in commits) {
            when (val commit = indexed.commit) {
                is StableUserMessage -> {
                    val text = commit.content.firstNonblankInputText() ?: continue
                    return FoundUserText(
                        text = text,
                        source = ConsumedTextSource.CommittedMessage(indexed.index, commit),
                    )
                }

                is SteerSnapshot -> {
                    for (steerable in commit.events) {
                        val message = steerable as? StableUserMessage ?: continue
                        val text = message.content.firstNonblankInputText() ?: continue
                        // Only treat SteerSnapshot as present if the range includes current unconsumed steer
                        // or during incremental observation when steer was just committed.
                        if (bounded.first > 0 || agentState.storage.getSteerAt(bounded.last).contains(message)) {
                            return FoundUserText(
                                text = text,
                                source = ConsumedTextSource.Steer(message),
                            )
                        }
                    }
                }

                else -> Unit
            }
        }
        return null
    }
}

private data class FoundUserText(
    val text: String,
    val source: ConsumedTextSource,
)

private sealed interface ConsumedTextSource {
    data class CommittedMessage(
        val index: Int,
        val message: StableUserMessage,
    ) : ConsumedTextSource

    data class Steer(
        val message: StableUserMessage,
    ) : ConsumedTextSource

    fun matchesRetained(previous: ConsumedTextSource?): Boolean =
        when (this) {
            is CommittedMessage -> previous is CommittedMessage &&
                previous.index == index &&
                previous.message == message

            is Steer -> previous is Steer && previous.message == message
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
