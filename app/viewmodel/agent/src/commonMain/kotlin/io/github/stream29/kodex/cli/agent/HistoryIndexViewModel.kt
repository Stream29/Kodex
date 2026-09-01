package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.IndexVersioned
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntry
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryDetail
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryKind
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.StepStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class HistoryIndexViewModelImpl(
    private val timeline: IndexVersioned<CleanIndexEntry>,
    latestIndex: StateFlow<Int>,
    agentState: StateFlow<KodexAgentStateValue>,
    scope: CoroutineScope,
) : HistoryIndexViewModel {
    private val scanMutex = Mutex()
    private var scannedThrough = -1
    private val mutableWindow = MutableStateFlow(
        HistoryIndexWindow(
            generation = 0,
            indexes = emptyList(),
        ),
    )

    override val window: StateFlow<HistoryIndexWindow> = mutableWindow.asStateFlow()

    init {
        scope.launch {
            latestIndex.collect { latest ->
                sync(latest = latest, forceInvalidate = false)
            }
        }
        scope.launch {
            var externalWriteStart: Int? = null
            agentState.collect { state ->
                if (state == KodexAgentStateValue.ExternalWrite) {
                    if (externalWriteStart == null) {
                        externalWriteStart = latestIndex.value
                    }
                } else {
                    externalWriteStart?.let { start ->
                        val end = latestIndex.value
                        sync(
                            latest = end,
                            forceInvalidate = end <= start,
                        )
                    }
                    externalWriteStart = null
                }
            }
        }
    }

    override fun contains(generation: Long, index: Int): Boolean {
        val current = mutableWindow.value
        return current.generation == generation && current.indexes.binarySearch(index) >= 0
    }

    override suspend fun load(generation: Long, index: Int): HistoryIndexEntry =
        loadExact(generation, index) { entry ->
            HistoryIndexEntry(
                index = index,
                kind = entry.toHistoryIndexEntryKind(),
                summary = entry.toHistoryIndexSummary(),
            )
        }

    override suspend fun loadDetail(
        generation: Long,
        index: Int,
    ): HistoryIndexEntryDetail = loadExact(generation, index) { entry ->
        HistoryIndexEntryDetail(
            kind = entry.toHistoryIndexEntryKind(),
            content = entry.toHistoryIndexDetail(),
            requestUserInput = entry as? StableRequestUserInputToolEvent,
        )
    }

    private suspend fun sync(latest: Int, forceInvalidate: Boolean) {
        scanMutex.withLock {
            val current = mutableWindow.value
            when {
                forceInvalidate || latest < scannedThrough -> {
                    val replacement = loadVisibleIndexes(0, latest)
                    mutableWindow.value = HistoryIndexWindow(
                        generation = current.generation + 1,
                        indexes = replacement,
                    )
                }

                latest > scannedThrough -> {
                    val appended = loadVisibleIndexes(scannedThrough + 1, latest)
                    if (appended.isNotEmpty()) {
                        mutableWindow.value = current.copy(
                            indexes = current.indexes + appended,
                        )
                    }
                }
            }
            scannedThrough = latest
        }
    }

    private suspend fun loadVisibleIndexes(fromInclusive: Int, toInclusive: Int): List<Int> =
        withContext(Dispatchers.Default) {
            if (toInclusive < fromInclusive || toInclusive < 0) {
                emptyList()
            } else {
                timeline.indexesIn(fromInclusive.coerceAtLeast(0)..toInclusive)
                    .filterNot { index ->
                        index == 0 && timeline.getExact(index) == CleanCompactionPoint
                    }
            }
        }

    private suspend fun <T> loadExact(
        generation: Long,
        index: Int,
        transform: (CleanIndexEntry) -> T,
    ): T = withContext(Dispatchers.Default) {
        ensureCurrent(generation, index)
        val entry = try {
            timeline.getExact(index)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw HistoryIndexLoadException(cause = failure)
        }
        if (entry == null) throw HistoryIndexLoadException()
        ensureCurrent(generation, index)
        transform(entry)
    }

    private fun ensureCurrent(generation: Long, index: Int) {
        if (!contains(generation, index)) {
            throw HistoryIndexLoadException()
        }
    }
}

internal class HistoryIndexLoadException(
    cause: Throwable? = null,
) : IllegalStateException("Unable to read or decode the history entry.", cause)

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
    is StablePlanUpdate -> HistoryIndexEntryKind.PlanUpdate
}

private fun CleanIndexEntry.toHistoryIndexSummary(): String = when (this) {
    CleanCompactionPoint -> "Context compacted"
    is StableUserMessage -> content.toDisplayContent().toSummary()
    is StableAssistantMessage -> content.toDisplayContent().toSummary()
    is StableDeveloperMessage -> content.toDisplayContent().toSummary()
    is StableAgentMessage -> content.toAgentDisplayContent().toSummary()
    is StableRequestUserInputToolEvent ->
        arguments.questions.joinToString(separator = " ") { question -> question.question }.toSummary()

    is StablePlanUpdate -> {
        val selected = arguments.plan.lastOrNull { item -> item.status != StepStatus.Pending }
            ?: arguments.plan.firstOrNull()
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
    }.joinToString(separator = "\n")

    is StableRequestUserInputToolEvent -> toRequestUserInputDetail()
    is StablePlanUpdate -> toPlanDetail()
}

private fun List<ContentItem>.toDisplayContent(): String = joinToString(separator = "") { part ->
    when (part) {
        is ContentItem.InputText -> part.text
        is ContentItem.OutputText -> part.text
        is ContentItem.InputImage -> "[image]"
    }
}

private fun List<AgentMessageInputContent>.toAgentDisplayContent(): String =
    joinToString(separator = "") { part ->
        when (part) {
            is AgentMessageInputContent.InputText -> part.text
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
            question.options?.takeIf { options -> options.isNotEmpty() }?.let { options ->
                add("")
                add("Options:")
                options.forEach { option ->
                    val description = option.description.takeIf { it.isNotBlank() }
                    add(
                        if (description == null) {
                            "- ${option.label}"
                        } else {
                            "- ${option.label} — $description"
                        },
                    )
                }
            }
            answered?.get(question.id)?.let { answer ->
                add("")
                add("Answer:")
                if (question.isSecret) {
                    add("[hidden]")
                } else {
                    val values = answer.answers.ifEmpty { listOf("[empty]") }
                    values.forEach { value -> add(value.ifBlank { "[empty]" }) }
                }
            }
        }.joinToString(separator = "\n")
    }
    return buildList {
        addAll(questions)
        if (completedResult is StableRequestUserInputResult.Failure) {
            if (isNotEmpty()) add("")
            add("Failed: ${completedResult.message.ifBlank { "[empty]" }}")
        }
    }.joinToString(separator = "\n\n").toDetail()
}

private fun StablePlanUpdate.toPlanDetail(): String = buildList {
    arguments.explanation?.takeIf { it.isNotBlank() }?.let(::add)
    if (arguments.plan.isNotEmpty()) {
        if (isNotEmpty()) add("")
        arguments.plan.forEach { item ->
            val marker = when (item.status) {
                StepStatus.Pending -> "[ ]"
                StepStatus.InProgress -> "[>]"
                StepStatus.Completed -> "[x]"
            }
            add("$marker ${item.step.ifBlank { "[empty]" }}")
        }
    }
}.joinToString(separator = "\n").toDetail()

private fun String.toSummary(): String =
    trim()
        .replace(Regex("\\s+"), " ")
        .ifEmpty { "[empty]" }

private fun String.toDetail(): String = trim().ifEmpty { "[empty]" }
