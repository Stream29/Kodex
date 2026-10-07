package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.HistoryIndexDependencies
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntry
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryDetail
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryKind
import io.github.stream29.kodex.app.agent.contract.HistoryIndexReadHandle
import io.github.stream29.kodex.app.agent.contract.HistoryIndexReadState
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModelFactory
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Instant

/** Actual dependency-only factory; no Agent/RPC/storage-owner access. */
public val historyIndexViewModelFactory: HistoryIndexViewModelFactory =
    HistoryIndexViewModelFactory(::createHistoryIndexViewModel)

public fun createHistoryIndexViewModel(
    dependencies: HistoryIndexDependencies,
    ownerScope: CoroutineScope,
): HistoryIndexViewModel = HistoryIndexViewModelImpl(dependencies, ownerScope)

internal class HistoryIndexViewModelImpl(
    private val dependencies: HistoryIndexDependencies,
    ownerScope: CoroutineScope,
) : HistoryIndexViewModel {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val scanMutex = Mutex()
    private var scannedThrough = -1
    // Local invalidation guard, never sent as or substituted for a backend cache nonce.
    private val readRevision = MutableStateFlow(0L)
    private val handles = MutableStateFlow<Set<ReadHandle<*>>>(emptySet())
    private val mutableWindow = MutableStateFlow(
        HistoryIndexWindow(dependencies.cacheNonce?.value ?: 0, emptyList()),
    )

    override val window: StateFlow<HistoryIndexWindow> = mutableWindow.asStateFlow()
    override val isActive: Boolean get() = job.isActive

    init {
        // Cancellation cleanup must not wait for a non-cooperative sibling read to complete.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { releaseHandles() }
        }
        dependencies.cacheNonce?.let { nonce ->
            scope.launch { nonce.collect { sync(dependencies.latestIndex.value, forceInvalidate = true) } }
        }
        scope.launch {
            dependencies.latestIndex.collect { latest -> sync(latest, forceInvalidate = false) }
        }
        scope.launch {
            var externalWriteStart: Int? = null
            dependencies.externalWrite.collect { writing ->
                if (writing) {
                    if (externalWriteStart == null) externalWriteStart = dependencies.latestIndex.value
                } else {
                    externalWriteStart?.let { start ->
                        val end = dependencies.latestIndex.value
                        sync(end, forceInvalidate = end <= start)
                    }
                    externalWriteStart = null
                }
            }
        }
    }

    override fun contains(generation: Long, index: Int): Boolean {
        val current = mutableWindow.value
        return isActive && current.generation == generation && current.revision == readRevision.value &&
            (dependencies.cacheNonce?.value?.let { it == generation } != false) &&
            current.indexes.binarySearch(index) >= 0
    }

    override suspend fun load(generation: Long, index: Int): HistoryIndexEntry =
        loadExact(generation, index) { entry ->
            HistoryIndexEntry(index, entry.toHistoryIndexEntryKind(), entry.toHistoryIndexSummary())
        }

    override suspend fun loadDetail(generation: Long, index: Int): HistoryIndexEntryDetail =
        loadExact(generation, index) { entry ->
            HistoryIndexEntryDetail(
                entry.toHistoryIndexEntryKind(),
                entry.toHistoryIndexDetail(),
                entry as? StableRequestUserInputToolEvent,
            )
        }

    override suspend fun readMessageTimestamp(generation: Long, index: Int): Instant? =
        withContext(Dispatchers.Default) {
            val revision = readRevision.value
            val isMessage = loadExact(generation, index) { it is StableIndexEvent.Steerable }
            val value = if (isMessage) dependencies.timestamp.getExact(index) else null
            coroutineContext.ensureActive()
            ensureCurrent(generation, index, revision)
            value
        }

    override fun acquireRow(generation: Long, index: Int): HistoryIndexReadHandle<HistoryIndexEntry> =
        acquire(generation, index) { load(generation, index) }

    override fun acquireDetail(generation: Long, index: Int): HistoryIndexReadHandle<HistoryIndexEntryDetail> =
        acquire(generation, index) { loadDetail(generation, index) }

    override fun acquireTimestamp(generation: Long, index: Int): HistoryIndexReadHandle<Instant?> =
        acquire(generation, index) { readMessageTimestamp(generation, index) }

    override fun checkOut(generation: Long, index: Int): Boolean {
        if (!contains(generation, index)) return false
        dependencies.requestScrollToStorageIndex(index)
        return true
    }

    override fun close() {
        releaseHandles()
        job.cancel()
    }

    private fun releaseHandles() {
        readRevision.update { it + 1 }
        var current: Set<ReadHandle<*>>
        do {
            current = handles.value
        } while (!handles.compareAndSet(current, emptySet()))
        current.forEach { it.release() }
    }

    private fun <T> acquire(generation: Long, index: Int, read: suspend () -> T): ReadHandle<T> {
        val handle = ReadHandle<T>(generation, index)
        if (!contains(generation, index)) {
            handle.release()
            return handle
        }
        val revision = readRevision.value
        handles.update { it + handle }
        handle.start(revision, read)
        return handle
    }

    private inner class ReadHandle<T>(
        override val generation: Long,
        override val index: Int,
    ) : HistoryIndexReadHandle<T> {
        private val mutableState = MutableStateFlow<HistoryIndexReadState<T>>(HistoryIndexReadState.Loading)
        private val readJob = Job(job)
        override val state = mutableState.asStateFlow()

        fun start(revision: Long, read: suspend () -> T) {
            val readScope = CoroutineScope(scope.coroutineContext + readJob)
            readScope.launch {
                try {
                    val value = read()
                    coroutineContext.ensureActive()
                    if (contains(generation, index) && readRevision.value == revision) {
                        mutableState.compareAndSet(HistoryIndexReadState.Loading, HistoryIndexReadState.Ready(value))
                    } else {
                        release()
                    }
                } catch (cancelled: CancellationException) {
                    release()
                    throw cancelled
                } catch (_: Throwable) {
                    if (contains(generation, index) && readRevision.value == revision && readJob.isActive) {
                        mutableState.compareAndSet(HistoryIndexReadState.Loading, HistoryIndexReadState.Failed)
                    } else {
                        release()
                    }
                } finally {
                    // A completed read keeps its immutable value until release/invalidation, but
                    // does not keep a live Job attached to the component owner.
                    readJob.complete()
                }
            }
            readJob.invokeOnCompletion { cause -> if (cause != null) release() }
        }

        override fun release() {
            mutableState.value = HistoryIndexReadState.Closed
            handles.update { it - this }
            readJob.cancel()
        }
    }

    private suspend fun sync(latest: Int, forceInvalidate: Boolean) {
        scanMutex.withLock {
            val current = mutableWindow.value
            val nonce = dependencies.cacheNonce?.value
            val target = if (dependencies.cacheNonce != null) dependencies.latestIndex.value else latest
            try {
                when {
                    forceInvalidate || target < scannedThrough || (nonce != null && nonce != current.generation) -> {
                        releaseHandles()
                        val replacement = loadVisibleIndexes(0, target)
                        if (nonce != dependencies.cacheNonce?.value) return
                        mutableWindow.value = HistoryIndexWindow(
                            generation = nonce ?: (current.generation + 1),
                            indexes = replacement,
                            revision = readRevision.value,
                        )
                    }
                    target > scannedThrough -> {
                        val appended = loadVisibleIndexes(scannedThrough + 1, target)
                        if (nonce != dependencies.cacheNonce?.value) return
                        if (appended.isNotEmpty()) {
                            mutableWindow.value = current.copy(indexes = current.indexes + appended)
                        }
                    }
                }
                scannedThrough = target
            } catch (failure: CacheNonceMismatch) {
                if (dependencies.cacheNonce == null) throw failure
                // Wait for subscribed nonce metadata; do not replay the stale query.
            }
        }
    }

    private suspend fun loadVisibleIndexes(fromInclusive: Int, toInclusive: Int): List<Int> =
        withContext(Dispatchers.Default) {
            if (toInclusive < fromInclusive || toInclusive < 0) emptyList()
            else dependencies.timeline.indexesIn(fromInclusive.coerceAtLeast(0)..toInclusive)
                .filterNot { index -> index == 0 && dependencies.timeline.getExact(index) == CleanCompactionPoint }
        }

    private suspend fun <T> loadExact(
        generation: Long,
        index: Int,
        transform: (CleanIndexEntry) -> T,
    ): T = withContext(Dispatchers.Default) {
        val revision = readRevision.value
        ensureCurrent(generation, index, revision)
        val entry = try {
            dependencies.timeline.getExact(index)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw HistoryIndexLoadException(cause = failure)
        }
        coroutineContext.ensureActive()
        if (entry == null) throw HistoryIndexLoadException()
        ensureCurrent(generation, index, revision)
        transform(entry)
    }

    private fun ensureCurrent(generation: Long, index: Int, revision: Long) {
        if (!contains(generation, index) || readRevision.value != revision) throw HistoryIndexLoadException()
    }
}

internal class HistoryIndexLoadException(cause: Throwable? = null) :
    IllegalStateException("Unable to read or decode the history entry.", cause)

private fun CleanIndexEntry.toHistoryIndexEntryKind(): HistoryIndexEntryKind = when (this) {
    CleanCompactionPoint -> HistoryIndexEntryKind.CompactionPoint
    is StableUserMessage -> HistoryIndexEntryKind.UserMessage
    is StableAssistantMessage -> when (phase) {
        MessagePhase.Commentary -> HistoryIndexEntryKind.AssistantCommentary
        MessagePhase.FinalAnswer -> HistoryIndexEntryKind.AssistantFinal
        null -> HistoryIndexEntryKind.AssistantMessage
    }
    is StableDeveloperMessage -> HistoryIndexEntryKind.DeveloperMessage
    is StableAgentMessage -> HistoryIndexEntryKind.AgentMessage
    is StableRequestUserInputToolEvent -> HistoryIndexEntryKind.RequestUserInput
    is StableSuggestSubagentTaskToolEvent -> HistoryIndexEntryKind.SuggestSubagents
    is StablePlanUpdate -> HistoryIndexEntryKind.PlanUpdate
}

private fun CleanIndexEntry.toHistoryIndexSummary(): String = when (this) {
    CleanCompactionPoint -> "Context compacted"
    is StableUserMessage -> content.toDisplayContent().toSummary()
    is StableAssistantMessage -> content.toDisplayContent().toSummary()
    is StableDeveloperMessage -> content.toDisplayContent().toSummary()
    is StableAgentMessage -> content.toAgentDisplayContent().toSummary()
    is StableRequestUserInputToolEvent ->
        arguments.questions.joinToString(separator = " ") { it.question }.toSummary()
    is StableSuggestSubagentTaskToolEvent -> "suggest subagents"
    is StablePlanUpdate -> {
        val selected = arguments.plan.lastOrNull { it.status != StepStatus.Pending } ?: arguments.plan.firstOrNull()
        selected?.step.orEmpty().toSummary()
    }
}

private fun CleanIndexEntry.toHistoryIndexDetail(): String = when (this) {
    CleanCompactionPoint -> "Context compacted"
    is StableUserMessage -> content.toDisplayContent().toDetail()
    is StableAssistantMessage -> content.toDisplayContent().toDetail()
    is StableDeveloperMessage -> content.toDisplayContent().toDetail()
    is StableAgentMessage -> buildList {
        add("Author: $author")
        add("Recipient: $recipient")
        add("")
        add(content.toAgentDisplayContent().toDetail())
    }.joinToString("\n")
    is StableRequestUserInputToolEvent -> toRequestUserInputDetail()
    is StableSuggestSubagentTaskToolEvent -> "suggest subagents"
    is StablePlanUpdate -> toPlanDetail()
}

private fun List<ContentItem>.toDisplayContent(): String = joinToString("") {
    when (it) {
        is ContentItem.InputText -> it.text
        is ContentItem.OutputText -> it.text
        is ContentItem.InputImage -> "[image]"
    }
}

private fun List<AgentMessageInputContent>.toAgentDisplayContent(): String = joinToString("") {
    when (it) {
        is AgentMessageInputContent.InputText -> it.text
        is AgentMessageInputContent.EncryptedContent -> "[encrypted content]"
    }
}

private fun StableRequestUserInputToolEvent.toRequestUserInputDetail(): String {
    val completedResult = result
    val answered = (completedResult as? StableRequestUserInputResult.Answered)?.response?.answers
    val questions = arguments.questions.map { question ->
        buildList {
            if (question.header.isNotBlank()) add(question.header)
            add(question.question.ifBlank { "[empty]" })
            question.options?.takeIf { it.isNotEmpty() }?.let { options ->
                add("")
                add("Options:")
                options.forEach { option ->
                    val description = option.description.takeIf { it.isNotBlank() }
                    add(if (description == null) "- ${option.label}" else "- ${option.label} — $description")
                }
            }
            answered?.get(question.id)?.let { answer ->
                add("")
                add("Answer:")
                if (question.isSecret) add("[hidden]")
                else answer.answers.ifEmpty { listOf("[empty]") }.forEach { add(it.ifBlank { "[empty]" }) }
            }
        }.joinToString("\n")
    }
    return buildList {
        addAll(questions)
        if (completedResult is StableRequestUserInputResult.Failure) {
            if (isNotEmpty()) add("")
            add("Failed: ${completedResult.message.ifBlank { "[empty]" }}")
        }
    }.joinToString("\n\n").toDetail()
}

private fun StablePlanUpdate.toPlanDetail(): String = buildList {
    arguments.explanation?.takeIf { it.isNotBlank() }?.let(::add)
    if (arguments.plan.isNotEmpty()) {
        if (isNotEmpty()) add("")
        arguments.plan.forEach {
            val marker = when (it.status) {
                StepStatus.Pending -> "[ ]"
                StepStatus.InProgress -> "[>]"
                StepStatus.Completed -> "[x]"
            }
            add("$marker ${it.step.ifBlank { "[empty]" }}")
        }
    }
}.joinToString("\n").toDetail()

private fun String.toSummary(): String = trim().replace(Regex("\\s+"), " ").ifEmpty { "[empty]" }
private fun String.toDetail(): String = trim().ifEmpty { "[empty]" }
