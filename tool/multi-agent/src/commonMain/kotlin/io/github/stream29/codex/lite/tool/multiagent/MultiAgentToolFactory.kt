package io.github.stream29.codex.lite.tool.multiagent

import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.contract.forkTo
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.agentstorage.contract.prevIndex
import io.github.stream29.codex.lite.openai.AgentMessageInputContent
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.tool.contract.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * State-bound client behind the six Multi-agent tools.
 *
 * Sessions own Agent runtimes and persisted storage. This client receives only
 * the current Agent state and the path resolver needed to find those sessions.
 */
private class MultiAgentToolClientImpl(
    private val state: CodexAgentState,
    private val pathResolver: AgentPathResolver,
) {
    private val stateMutex: Mutex = Mutex()
    private val activities: MutableMap<String, AgentActivity> = mutableMapOf()
    private val turnPermits: Semaphore = Semaphore(MaxConcurrentTurns)

    init {
        state.coroutineContext.job.invokeOnCompletion {
            activities.clear()
        }
    }

    val tools: List<Tool> = createMultiAgentTools(BoundToolClient())

    private suspend fun spawnAgent(
        caller: CodexAgentSession,
        args: SpawnAgentArgs,
    ): SpawnAgentResult {
        validateTaskSegment(args.taskName)
        require(args.message.isNotBlank()) { "Empty message can't be sent to an agent." }
        val callerPath = pathOf(caller)
        val childPath = "$callerPath/${args.taskName}"
        require(pathResolver.resolveOrNull(childPath) == null) {
            "Agent path already exists: $childPath"
        }
        val forkMode = args.forkTurns.toForkMode()
        if (forkMode == SpawnForkMode.All) {
            require(args.model == null && args.reasoningEffort == null) {
                "A full-history fork must inherit the parent model and reasoning effort."
            }
        }

        val entryIndex = caller.subagents.create()
        try {
            val child = caller.subagents.open(entryIndex)
            initializeSpawnStorage(caller, child, args, childPath, forkMode)
            child.runtime.injectHistory(
                listOf(
                    AgentCommunication(
                        author = callerPath,
                        recipient = childPath,
                        message = args.message,
                        kind = AgentMessageKind.NewTask,
                        triggerTurn = true,
                    ).historyItem,
                ),
            )
            activityFor(child).apply {
                status = MultiAgentStatus.Interrupted
                lastTaskMessage = args.message
            }
            launchTurn(child)
            return SpawnAgentResult(taskName = childPath, nickname = null)
        } catch (failure: Throwable) {
            caller.subagents.delete(entryIndex)
            throw failure
        }
    }

    private suspend fun sendMessage(
        caller: CodexAgentSession,
        targetPath: String,
        message: String,
        triggerTurn: Boolean,
    ) {
        require(message.isNotBlank()) { "Empty message can't be sent to an agent." }
        val target = requireNotNull(pathResolver.resolveOrNull(targetPath)) {
            "Agent path not found: $targetPath"
        }
        if (triggerTurn) {
            require(target.storage.id != rootSession().storage.id) {
                "Follow-up tasks can't target the root agent."
            }
        }
        deliver(
            target = target,
            communication = AgentCommunication(
                author = pathOf(caller),
                recipient = pathOf(target),
                message = message,
                kind = if (triggerTurn) AgentMessageKind.NewTask else AgentMessageKind.Message,
                triggerTurn = triggerTurn,
            ),
        )
    }

    private suspend fun deliver(
        target: CodexAgentSession,
        communication: AgentCommunication,
    ) {
        val activity = activityFor(target)
        activity.deliveryMutex.withLock {
            val injectNow = stateMutex.withLock {
                activity.lastTaskMessage = communication.message
                activity.version.value += 1
                activity.turnJob?.isActive != true && target.runtime.state.value.canInjectAgentMessage
            }
            if (injectNow) {
                target.runtime.injectHistory(listOf(communication.historyItem))
                if (communication.triggerTurn) {
                    launchTurn(target)
                }
            } else {
                stateMutex.withLock {
                    activity.mailbox += communication
                }
            }
        }
    }

    private suspend fun waitAgent(
        caller: CodexAgentSession,
        args: WaitAgentArgs,
    ): WaitAgentResult {
        flushMailbox(caller)
        val timeoutMillis = args.timeoutMs ?: MultiAgentTools.DefaultWaitTimeoutMillis
        require(timeoutMillis in MultiAgentTools.MinWaitTimeoutMillis..MultiAgentTools.MaxWaitTimeoutMillis) {
            "timeout_ms must be between ${MultiAgentTools.MinWaitTimeoutMillis} and " +
                "${MultiAgentTools.MaxWaitTimeoutMillis}"
        }
        val activity = activityFor(caller)
        val baseline = stateMutex.withLock {
            val current = activity.version.value
            if (current > activity.lastWaitVersion) {
                activity.lastWaitVersion = current
                return WaitAgentResult("Wait completed.", timedOut = false)
            }
            current
        }
        val turnPermit = currentCoroutineContext()[TurnPermit]
        turnPermit?.release()
        val updatedVersion = try {
            withTimeoutOrNull(timeoutMillis) {
                activity.version.first { version -> version > baseline }
            }
        } finally {
            if (turnPermit != null && currentCoroutineContext().isActive) {
                turnPermit.acquire()
            }
        }
        if (updatedVersion == null) {
            return WaitAgentResult("Wait timed out.", timedOut = true)
        }
        stateMutex.withLock {
            activity.lastWaitVersion = maxOf(activity.lastWaitVersion, updatedVersion)
        }
        flushMailbox(caller)
        return WaitAgentResult("Wait completed.", timedOut = false)
    }

    private suspend fun interruptAgent(
        caller: CodexAgentSession,
        args: InterruptAgentArgs,
    ): InterruptAgentResult {
        val target = requireNotNull(pathResolver.resolveOrNull(args.target)) {
            "Agent path not found: ${args.target}"
        }
        require(target.storage.id != rootSession().storage.id) { "root is not a spawned agent" }
        require(target.storage.id != caller.storage.id) {
            "an agent cannot interrupt itself; return your result and let the parent interrupt you if needed"
        }
        val activity = activityFor(target)
        val previousStatus = statusOf(target, activity)
        val turnJob = stateMutex.withLock { activity.turnJob }
        turnJob?.cancelAndJoin()
        return InterruptAgentResult(previousStatus)
    }

    private suspend fun listAgents(args: ListAgentsArgs): ListAgentsResult {
        val prefix = args.pathPrefix
        if (prefix != null) {
            require(pathResolver.resolveOrNull(prefix) != null) {
                "Agent path not found: $prefix"
            }
        }
        val agents = mutableListOf<ListedAgent>()
        for ((path, session) in collectSessions()) {
            if (prefix != null && path != prefix && !path.startsWith("$prefix/")) continue
            val activity = activityFor(session)
            agents += ListedAgent(
                agentName = path,
                agentStatus = statusOf(session, activity),
                lastTaskMessage = activity.lastTaskMessage
                    ?: session.recoverLastTaskMessage(),
            )
        }
        return ListAgentsResult(agents)
    }

    private suspend fun launchTurn(session: CodexAgentSession) {
        val activity = activityFor(session)
        val job = session.launch(start = CoroutineStart.LAZY) {
            runTurn(session, activity)
        }
        val shouldStart = stateMutex.withLock {
            if (activity.turnJob?.isActive == true) {
                false
            } else {
                activity.turnJob = job
                activity.status = MultiAgentStatus.Running
                true
            }
        }
        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private suspend fun runTurn(
        session: CodexAgentSession,
        activity: AgentActivity,
    ) {
        val turnJob = currentCoroutineContext().job
        var status: MultiAgentStatus = MultiAgentStatus.Interrupted
        try {
            turnPermits.acquire()
            val permit = TurnPermit(turnPermits)
            try {
                withContext(permit) {
                    while (true) {
                        flushMailbox(session)
                        session.runtime.resume().collect {}
                        if (!flushMailbox(session)) break
                    }
                }
            } finally {
                permit.release()
            }
            status = if (session.runtime.state.value is CodexAgentStateValue.ToolPending) {
                MultiAgentStatus.Interrupted
            } else {
                MultiAgentStatus.Completed(session.latestAssistantText())
            }
        } catch (cancellation: CancellationException) {
            status = if (session.coroutineContext.job.isActive) {
                MultiAgentStatus.Interrupted
            } else {
                MultiAgentStatus.Shutdown
            }
            throw cancellation
        } catch (failure: Throwable) {
            status = MultiAgentStatus.Errored(
                failure.message ?: failure::class.simpleName ?: "Agent turn failed",
            )
        } finally {
            val relaunch = stateMutex.withLock {
                if (activity.turnJob === turnJob) {
                    activity.turnJob = null
                }
                activity.status = status
                activity.mailbox.any(AgentCommunication::triggerTurn) &&
                    session.coroutineContext.job.isActive
            }
            if (session.isActive) {
                try {
                    notifyParent(session, status)
                } catch (failure: Throwable) {
                    if (session.isActive) throw failure
                }
            }
            if (relaunch) {
                launchTurn(session)
            }
        }
    }

    /**
     * Claims and persists the current mailbox.
     *
     * @return whether the claimed messages request another Agent turn.
     */
    private suspend fun flushMailbox(session: CodexAgentSession): Boolean {
        val activity = activityFor(session)
        return activity.deliveryMutex.withLock {
            if (!session.runtime.state.value.canInjectAgentMessage) return@withLock false
            val messages = stateMutex.withLock {
                activity.mailbox.toList().also { activity.mailbox.clear() }
            }
            if (messages.isEmpty()) return@withLock false
            try {
                session.runtime.injectHistory(messages.map(AgentCommunication::historyItem))
            } catch (failure: Throwable) {
                stateMutex.withLock {
                    activity.mailbox.addAll(0, messages)
                }
                throw failure
            }
            messages.any(AgentCommunication::triggerTurn)
        }
    }

    private suspend fun notifyParent(
        session: CodexAgentSession,
        status: MultiAgentStatus,
    ) {
        val path = pathOf(session)
        if (path == RootAgentPath) return
        val parentPath = path.substringBeforeLast('/')
        val parent = pathResolver.resolveOrNull(parentPath) ?: return
        val payload = when (status) {
            is MultiAgentStatus.Completed -> status.message.orEmpty()
            is MultiAgentStatus.Errored ->
                "Agent errored: ${status.message}\n\n" +
                    "This agent's turn failed. If you still need this agent, give it another task."

            else -> return
        }
        deliver(
            target = parent,
            communication = AgentCommunication(
                author = path,
                recipient = parentPath,
                message = payload,
                kind = AgentMessageKind.FinalAnswer,
                triggerTurn = false,
            ),
        )
    }

    private suspend fun activityFor(session: CodexAgentSession): AgentActivity =
        stateMutex.withLock {
            activities.getOrPut(session.storage.id) {
                AgentActivity(status = session.initialStatus())
            }
        }

    private suspend fun statusOf(
        session: CodexAgentSession,
        activity: AgentActivity,
    ): MultiAgentStatus {
        if (stateMutex.withLock { activity.turnJob?.isActive == true }) {
            return MultiAgentStatus.Running
        }
        return when (val status = activity.status) {
            is MultiAgentStatus.Errored,
            MultiAgentStatus.Shutdown,
            -> status

            else -> session.initialStatus()
        }
    }

    private suspend fun collectSessions(): List<Pair<String, CodexAgentSession>> {
        val result = mutableListOf<Pair<String, CodexAgentSession>>()
        suspend fun collect(
            path: String,
            session: CodexAgentSession,
        ) {
            result += path to session
            session.subagents.list().forEach { entryIndex ->
                val child = session.subagents.open(entryIndex)
                if (child.storage.settings.latestIndex() < 0) return@forEach
                val segment = child.storage.settings.latestValue().threadName.substringAfterLast('/')
                collect("$path/$segment", child)
            }
        }
        collect(RootAgentPath, rootSession())
        return result
    }

    private suspend fun pathOf(session: CodexAgentSession): String =
        if (session.storage.id == rootSession().storage.id) {
            RootAgentPath
        } else {
            session.storage.settings.latestValue().threadName.also { path ->
                require(path.startsWith("$RootAgentPath/")) {
                    "Subagent thread name must be a canonical Agent path: $path"
                }
            }
        }

    private suspend fun rootSession(): CodexAgentSession =
        requireNotNull(pathResolver.resolveOrNull(RootAgentPath)) {
            "Agent path resolver does not expose $RootAgentPath."
        }

    private suspend fun callerSession(): CodexAgentSession {
        val root = rootSession()
        if (root.storage.id == state.storage.id) return root
        val path = state.storage.settings.latestValue().threadName
        return requireNotNull(pathResolver.resolveOrNull(path)) {
            "Current Agent path not found: $path"
        }.also { session ->
            require(session.storage.id == state.storage.id) {
                "Current Agent state and resolved Session identify different Agents."
            }
        }
    }

    private inner class BoundToolClient : MultiAgentToolClient {
        override suspend fun spawnAgent(args: SpawnAgentArgs): MultiAgentToolResult<SpawnAgentResult> {
            val caller = callerSession()
            return toolResult(caller) { spawnAgent(caller, args) }
        }

        override suspend fun sendMessage(args: SendMessageArgs): MultiAgentToolResult<Unit> {
            val caller = callerSession()
            return toolResult(caller) {
                sendMessage(caller, args.target, args.message, triggerTurn = false)
            }
        }

        override suspend fun followupTask(args: FollowupTaskArgs): MultiAgentToolResult<Unit> {
            val caller = callerSession()
            return toolResult(caller) {
                sendMessage(caller, args.target, args.message, triggerTurn = true)
            }
        }

        override suspend fun waitAgent(args: WaitAgentArgs): MultiAgentToolResult<WaitAgentResult> {
            val caller = callerSession()
            return toolResult(caller) { waitAgent(caller, args) }
        }

        override suspend fun interruptAgent(
            args: InterruptAgentArgs,
        ): MultiAgentToolResult<InterruptAgentResult> {
            val caller = callerSession()
            return toolResult(caller) { interruptAgent(caller, args) }
        }

        override suspend fun listAgents(args: ListAgentsArgs): MultiAgentToolResult<ListAgentsResult> {
            val caller = callerSession()
            return toolResult(caller) { this@MultiAgentToolClientImpl.listAgents(args) }
        }
    }

    private suspend fun <Value> toolResult(
        caller: CodexAgentSession,
        operation: suspend () -> Value,
    ): MultiAgentToolResult<Value> = try {
        currentCoroutineContext().ensureActive()
        flushMailbox(caller)
        MultiAgentToolResult.Success(operation())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        MultiAgentToolResult.Failure(
            failure.message ?: failure::class.simpleName ?: "Multi-agent operation failed",
        )
    }
}

/** Creates the six ordinary Multi-agent tools bound to this Agent. */
public fun CodexAgentState.multiAgentTools(
    agentPathResolver: AgentPathResolver,
): List<Tool> =
    MultiAgentToolClientImpl(
        state = this,
        pathResolver = agentPathResolver,
    ).tools

private class AgentActivity(
    var status: MultiAgentStatus,
) {
    val deliveryMutex: Mutex = Mutex()
    val mailbox: MutableList<AgentCommunication> = mutableListOf()
    val version: MutableStateFlow<Long> = MutableStateFlow(0)

    /** `null` means this Agent has no active background turn. */
    var turnJob: Job? = null

    /** `null` means no task or inter-Agent message has been observed. */
    var lastTaskMessage: String? = null

    var lastWaitVersion: Long = 0
}

private sealed interface SpawnForkMode {
    data object None : SpawnForkMode
    data object All : SpawnForkMode
    data class Recent(val turns: Int) : SpawnForkMode
}

private enum class AgentMessageKind(val wireName: String) {
    NewTask("NEW_TASK"),
    Message("MESSAGE"),
    FinalAnswer("FINAL_ANSWER"),
}

private data class AgentCommunication(
    val author: String,
    val recipient: String,
    val message: String,
    val kind: AgentMessageKind,
    val triggerTurn: Boolean,
) {
    val historyItem: ResponseItem.AgentMessage
        get() = ResponseItem.AgentMessage(
            author = author,
            recipient = recipient,
            content = listOf(
                AgentMessageInputContent.InputText(
                    "Message Type: ${kind.wireName}\n" +
                        "Task name: $recipient\n" +
                        "Sender: $author\n" +
                        "Payload:\n$message",
                ),
            ),
        )
}

private class TurnPermit(
    private val semaphore: Semaphore,
) : AbstractCoroutineContextElement(TurnPermit) {
    private var held: Boolean = true

    suspend fun acquire() {
        check(!held) { "Turn permit is already held." }
        semaphore.acquire()
        held = true
    }

    fun release() {
        if (!held) return
        held = false
        semaphore.release()
    }

    companion object : CoroutineContext.Key<TurnPermit>
}

private suspend fun initializeSpawnStorage(
    caller: CodexAgentSession,
    child: CodexAgentSession,
    args: SpawnAgentArgs,
    childPath: String,
    forkMode: SpawnForkMode,
) {
    val sourceStorage = caller.storage
    val childSettings = sourceStorage.settings.latestValue().forSpawn(args, childPath)
    val forkBoundary = caller.forkBoundary()
    when (forkMode) {
        SpawnForkMode.None -> child.runtime.modify { storage ->
            storage.initialize(childSettings)
        }
        SpawnForkMode.All -> {
            child.runtime.modify { storage ->
                sourceStorage.forkTo(forkBoundary, storage)
            }
            child.runtime.updateSettings(childSettings)
        }

        is SpawnForkMode.Recent -> {
            child.runtime.modify { storage ->
                storage.initialize(childSettings)
            }
            child.runtime.injectHistory(caller.activeHistory(forkBoundary, forkMode.turns))
        }
    }
}

private suspend fun CodexAgentSession.forkBoundary(): Int {
    val pendingCallIds = (runtime.state.value as? CodexAgentStateValue.ToolPending)
        ?.calls
        ?.mapTo(mutableSetOf(), ResponseItem.ToolCall::callId)
        .orEmpty()
    if (pendingCallIds.isEmpty()) return storage.latestIndex() + 1
    val pendingIndexes = storage.history.indexes().toList().filter { index ->
        val item = storage.history[index]
        item is ResponseItem.ToolCall && item.callId in pendingCallIds
    }
    return pendingIndexes.minOrNull() ?: (storage.latestIndex() + 1)
}

private suspend fun CodexAgentSession.activeHistory(
    untilExclusive: Int,
    turns: Int,
): List<ResponseItem.HistoryItem> {
    val index = untilExclusive - 1
    if (index < 0) return emptyList()
    val checkpoint = storage.compaction[index]
    val items = checkpoint.prefix.toMutableList()
    storage.history.indexes(checkpoint.historyBaseIndex).toList().forEach { historyIndex ->
        if (historyIndex < untilExclusive) items += storage.history[historyIndex]
    }
    val boundaries = items.indices.filter { itemIndex -> items[itemIndex].startsTurn }
    if (boundaries.isEmpty()) return items
    return items.drop(boundaries.takeLast(turns).first())
}

private suspend fun CodexAgentSession.initialStatus(): MultiAgentStatus =
    when (runtime.state.value) {
        CodexAgentStateValue.Empty -> MultiAgentStatus.PendingInit
        CodexAgentStateValue.AssistantMessage -> MultiAgentStatus.Completed(latestAssistantText())
        CodexAgentStateValue.UserMessage,
        is CodexAgentStateValue.ToolPending,
        CodexAgentStateValue.ToolCompleted,
        CodexAgentStateValue.ExternalWrite,
        CodexAgentStateValue.RequestResponse,
        CodexAgentStateValue.Compacting,
        -> MultiAgentStatus.Interrupted
    }

private suspend fun CodexAgentSession.latestAssistantText(): String? {
    var index: Int? = storage.history.latestIndex().takeIf { it >= 0 }
    while (index != null) {
        val item = storage.history[index]
        if (item is ResponseItem.Message && item.role == MessageRole.Assistant) {
            return item.content.joinToString("") { content ->
                when (content) {
                    is ContentItem.InputText -> content.text
                    is ContentItem.OutputText -> content.text
                    is ContentItem.InputImage -> ""
                }
            }.ifEmpty { null }
        }
        index = storage.history.prevIndex(index)
    }
    return null
}

private suspend fun CodexAgentSession.recoverLastTaskMessage(): String? {
    var index: Int? = storage.history.latestIndex().takeIf { it >= 0 }
    while (index != null) {
        when (val item = storage.history[index]) {
            is ResponseItem.AgentMessage -> item.content
                .filterIsInstance<AgentMessageInputContent.InputText>()
                .firstNotNullOfOrNull { content ->
                    content.text.substringAfter(PayloadMarker, "").ifEmpty { null }
                }
                ?.let { return it }

            is ResponseItem.Message -> if (item.role == MessageRole.User) {
                return item.content
                    .filterIsInstance<ContentItem.InputText>()
                    .joinToString("") { content -> content.text }
                    .ifEmpty { null }
            }

            else -> Unit
        }
        index = storage.history.prevIndex(index)
    }
    return null
}

@OptIn(ExperimentalUuidApi::class)
private fun CodexAgentSettings.forSpawn(
    args: SpawnAgentArgs,
    childPath: String,
): CodexAgentSettings = copy(
    model = args.model ?: model,
    threadName = childPath,
    turnId = Uuid.generateV7().toString(),
    previousResponseId = null,
    promptCacheKey = null,
    reasoning = args.reasoningEffort?.let { effort -> reasoning.copy(effort = effort) } ?: reasoning,
    serviceTier = args.serviceTier ?: serviceTier,
)

private fun String?.toForkMode(): SpawnForkMode {
    val value = this?.trim().orEmpty().ifEmpty { "all" }
    if (value.equals("none", ignoreCase = true)) return SpawnForkMode.None
    if (value.equals("all", ignoreCase = true)) return SpawnForkMode.All
    val turns = value.toIntOrNull()
    require(turns != null && turns > 0) {
        "fork_turns must be `none`, `all`, or a positive integer string"
    }
    return SpawnForkMode.Recent(turns)
}

private fun validateTaskSegment(value: String) {
    require(value.isNotEmpty()) { "task_name must not be empty" }
    require(value != "root" && value != "." && value != "..") {
        "task_name `$value` is reserved"
    }
    require(value.all { character ->
        character in 'a'..'z' || character in '0'..'9' || character == '_'
    }) {
        "task_name must use only lowercase letters, digits, and underscores"
    }
}

private val CodexAgentStateValue.canInjectAgentMessage: Boolean
    get() = when (this) {
        CodexAgentStateValue.Empty,
        CodexAgentStateValue.UserMessage,
        CodexAgentStateValue.AssistantMessage,
        is CodexAgentStateValue.ToolPending,
        CodexAgentStateValue.ToolCompleted,
        -> true

        CodexAgentStateValue.ExternalWrite,
        CodexAgentStateValue.RequestResponse,
        CodexAgentStateValue.Compacting,
        -> false
    }

private val ResponseItem.HistoryItem.startsTurn: Boolean
    get() = when (this) {
        is ResponseItem.Message -> role == MessageRole.User
        is ResponseItem.AgentMessage -> content
            .filterIsInstance<AgentMessageInputContent.InputText>()
            .any { content -> content.text.startsWith("Message Type: NEW_TASK\n") }

        else -> false
    }

private const val RootAgentPath: String = "/root"
private const val PayloadMarker: String = "Payload:\n"
private const val MaxConcurrentTurns: Int = 4
