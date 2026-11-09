package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
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
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModelFactory
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Instant

public actual val historyIndexViewModelFactory: HistoryIndexViewModelFactory =
    HistoryIndexViewModelFactory(::createHistoryIndexViewModel)

public actual fun createHistoryIndexViewModel(
    dependencies: HistoryIndexDependencies,
    ownerScope: CoroutineScope,
): HistoryIndexViewModel = HistoryIndexViewModelImpl(dependencies, ownerScope)

internal const val HistoryIndexPageSize = 64
internal const val HistoryIndexPrefetchDistance = 16

internal class HistoryIndexViewModelImpl(
    private val dependencies: HistoryIndexDependencies,
    ownerScope: CoroutineScope,
) : HistoryIndexViewModel {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val scanMutex = Mutex()
    private var scannedThrough = -1
    private var oldestScannedFrom = 0
    private val olderScanInProgress = MutableStateFlow(false)
    // Local invalidation guard, never sent as or substituted for a backend cache nonce.
    private val readRevision = MutableStateFlow(0L)
    private val mutableWindow = MutableStateFlow(
        HistoryIndexWindow(dependencies.cacheNonce?.value ?: 0, emptyList()),
    )

    override val window: StateFlow<HistoryIndexWindow> = mutableWindow.asStateFlow()
    override val isActive: Boolean get() = job.isActive

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { advanceRevision() }
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

    override fun onRowVisible(generation: Long, index: Int) {
        val current = mutableWindow.value
        if (oldestScannedFrom > 0 && current.generation == generation) {
            val position = current.indexes.binarySearch(index)
            if (position in 0 until HistoryIndexPrefetchDistance) {
                requestOlder(generation)
            }
        }
    }

    override fun checkOut(generation: Long, index: Int): Boolean {
        if (!contains(generation, index)) return false
        dependencies.requestScrollToStorageIndex(index)
        return true
    }

    override fun close() {
        advanceRevision()
        job.cancel()
    }

    private fun advanceRevision() {
        readRevision.update { it + 1 }
    }

    private suspend fun sync(latest: Int, forceInvalidate: Boolean) {
        scanMutex.withLock {
            val current = mutableWindow.value
            val nonce = dependencies.cacheNonce?.value
            val target = if (dependencies.cacheNonce != null) dependencies.latestIndex.value else latest
            try {
                when {
                    forceInvalidate || target < scannedThrough || (nonce != null && nonce != current.generation) -> {
                        advanceRevision()
                        val page = loadLatestPage(target)
                        if (nonce != dependencies.cacheNonce?.value) return
                        oldestScannedFrom = page.oldestScannedFrom
                        mutableWindow.value = HistoryIndexWindow(
                            generation = nonce ?: (current.generation + 1),
                            entries = page.entries,
                            revision = readRevision.value,
                        )
                    }
                    target > scannedThrough -> {
                        if (scannedThrough < 0) {
                            val page = loadLatestPage(target)
                            if (nonce != dependencies.cacheNonce?.value) return
                            oldestScannedFrom = page.oldestScannedFrom
                            if (page.entries.isNotEmpty()) {
                                mutableWindow.value = current.copy(entries = page.entries)
                            }
                        } else {
                            val appended = loadAppendedEntries(scannedThrough + 1, target)
                            if (nonce != dependencies.cacheNonce?.value) return
                            if (appended.isNotEmpty()) {
                                val latestWindow = mutableWindow.value
                                mutableWindow.value = latestWindow.copy(entries = latestWindow.entries + appended)
                            }
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

    private fun requestOlder(generation: Long) {
        if (!isActive || oldestScannedFrom <= 0) return
        if (!olderScanInProgress.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                loadOlder(generation)
            } finally {
                olderScanInProgress.value = false
            }
        }
    }

    private suspend fun loadOlder(expectedGeneration: Long) {
        scanMutex.withLock {
            val current = mutableWindow.value
            val nonce = dependencies.cacheNonce?.value
            val upperExclusive = oldestScannedFrom
            if (
                !isActive ||
                upperExclusive <= 0 ||
                current.generation != expectedGeneration ||
                current.revision != readRevision.value ||
                (nonce != null && nonce != current.generation)
            ) {
                return
            }
            try {
                val page = loadLatestPage(upperExclusive - 1)
                if (
                    !isActive ||
                    nonce != dependencies.cacheNonce?.value ||
                    mutableWindow.value.generation != current.generation ||
                    mutableWindow.value.revision != current.revision
                ) {
                    return
                }
                oldestScannedFrom = page.oldestScannedFrom
                if (page.entries.isNotEmpty()) {
                    val latestWindow = mutableWindow.value
                    mutableWindow.value = latestWindow.copy(
                        entries = page.entries + latestWindow.entries,
                    )
                }
            } catch (failure: CacheNonceMismatch) {
                if (dependencies.cacheNonce == null) throw failure
            }
        }
    }

    private data class LoadedIndexPage(
        val entries: List<HistoryIndexEntry>,
        val oldestScannedFrom: Int,
    )

    private suspend fun loadLatestPage(toInclusive: Int): LoadedIndexPage =
        withContext(Dispatchers.Default) {
            if (toInclusive < 0) {
                LoadedIndexPage(emptyList(), 0)
            } else {
                val commits = dependencies.storage.getLastCommitsIn(
                    range = 0..toInclusive,
                    types = AgentCommitType.IndexTypes,
                    limit = HistoryIndexPageSize,
                )
                val oldestFrom = if (commits.size < HistoryIndexPageSize) 0 else commits.first().index
                val entries = commits.mapNotNull { it.toHistoryIndexEntryOrNull() }
                LoadedIndexPage(entries, oldestFrom)
            }
        }

    private suspend fun loadAppendedEntries(fromInclusive: Int, toInclusive: Int): List<HistoryIndexEntry> =
        withContext(Dispatchers.Default) {
            if (toInclusive < fromInclusive || toInclusive < 0) {
                emptyList()
            } else {
                dependencies.storage.getLastCommitsIn(
                    range = fromInclusive.coerceAtLeast(0)..toInclusive,
                    types = AgentCommitType.IndexTypes,
                ).mapNotNull { it.toHistoryIndexEntryOrNull() }
            }
        }
}

private fun IndexedAgentCommit.toHistoryIndexEntryOrNull(): HistoryIndexEntry? {
    val event = commit as? CleanIndexEntry ?: return null
    val kind = event.toHistoryIndexEntryKind()
    val summary = event.toHistoryIndexSummary()
    val detail = HistoryIndexEntryDetail(
        kind = kind,
        content = event.toHistoryIndexDetail(),
        requestUserInput = event as? StableRequestUserInputToolEvent,
        plan = event as? StablePlanUpdate,
    )
    val messageTimestamp = if (event is StableIndexEvent.Steerable) {
        createdAt.takeIf { it != Instant.DISTANT_PAST }
    } else {
        null
    }
    return HistoryIndexEntry(
        index = index,
        kind = kind,
        summary = summary,
        detail = detail,
        messageTimestamp = messageTimestamp,
    )
}

private fun CleanIndexEntry.toHistoryIndexEntryKind(): HistoryIndexEntryKind = when (this) {
    is StableContextCompaction -> HistoryIndexEntryKind.CompactionPoint
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
    is StableContextCompaction -> "Context compacted"
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
    is StableContextCompaction -> "Context compacted"
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
