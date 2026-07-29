package io.github.stream29.codex.lite.tool.multiagent

import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentruntime.contract.ConcurrentAgentRuntimeResumeException
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
import io.github.stream29.codex.lite.tool.builder.JsonToolHandlerResult
import io.github.stream29.codex.lite.tool.builder.ToolBuilderJson
import io.github.stream29.codex.lite.tool.builder.jsonTool
import io.github.stream29.codex.lite.tool.builder.jsonToolFailure
import io.github.stream29.codex.lite.tool.builder.jsonToolSuccess
import io.github.stream29.codex.lite.tool.builder.textTool
import io.github.stream29.codex.lite.tool.contract.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Creates an independent `spawn_agent` tool bound to this Agent. */
public fun CodexAgentState.spawnAgentTool(
    agentPathResolver: AgentPathResolver,
): Tool {
    val callerSession = callerSessionProvider(agentPathResolver)
    return jsonTool(
        spec = MultiAgentTools.spawnAgentSpec,
        inputDeserializer = SpawnAgentArgs.serializer(),
        outputSerializer = SpawnAgentResult.serializer(),
        json = MultiAgentToolJson,
    ) { args ->
        multiAgentResult { agentPathResolver.spawnAgent(callerSession(), args) }
    }
}

/** Creates an independent `send_message` tool bound to this Agent. */
public fun CodexAgentState.sendMessageTool(
    agentPathResolver: AgentPathResolver,
): Tool {
    val callerSession = callerSessionProvider(agentPathResolver)
    return textTool(
        spec = MultiAgentTools.sendMessageSpec,
        inputDeserializer = SendMessageArgs.serializer(),
        json = MultiAgentToolJson,
    ) { args ->
        multiAgentTextResult {
            agentPathResolver.sendMessage(
                caller = callerSession(),
                targetPath = args.target,
                message = args.message,
                kind = AgentMessageKind.Message,
                resumeIfIdle = false,
            )
        }
    }
}

/** Creates an independent `followup_task` tool bound to this Agent. */
public fun CodexAgentState.followupTaskTool(
    agentPathResolver: AgentPathResolver,
): Tool {
    val callerSession = callerSessionProvider(agentPathResolver)
    return textTool(
        spec = MultiAgentTools.followupTaskSpec,
        inputDeserializer = FollowupTaskArgs.serializer(),
        json = MultiAgentToolJson,
    ) { args ->
        multiAgentTextResult {
            agentPathResolver.sendMessage(
                caller = callerSession(),
                targetPath = args.target,
                message = args.message,
                kind = AgentMessageKind.NewTask,
                resumeIfIdle = true,
            )
        }
    }
}

/** Creates an independent `wait_agent` tool bound to this Agent. */
public fun CodexAgentState.waitAgentTool(
    agentPathResolver: AgentPathResolver,
): Tool {
    val callerSession = callerSessionProvider(agentPathResolver)
    return jsonTool(
        spec = MultiAgentTools.waitAgentSpec,
        inputDeserializer = WaitAgentArgs.serializer(),
        outputSerializer = WaitAgentResult.serializer(),
        json = MultiAgentToolJson,
    ) { args ->
        multiAgentResult { agentPathResolver.waitForSteer(callerSession(), args) }
    }
}

/** Creates an independent `interrupt_agent` tool bound to this Agent. */
public fun CodexAgentState.interruptAgentTool(
    agentPathResolver: AgentPathResolver,
): Tool {
    val callerSession = callerSessionProvider(agentPathResolver)
    return jsonTool(
        spec = MultiAgentTools.interruptAgentSpec,
        inputDeserializer = InterruptAgentArgs.serializer(),
        outputSerializer = InterruptAgentResult.serializer(),
        json = MultiAgentToolJson,
    ) { args ->
        multiAgentResult { agentPathResolver.interruptAgent(callerSession(), args) }
    }
}

/** Creates an independent `list_agents` tool for this Agent's Session tree. */
public fun CodexAgentState.listAgentsTool(
    agentPathResolver: AgentPathResolver,
): Tool =
    jsonTool(
        spec = MultiAgentTools.listAgentsSpec,
        inputDeserializer = ListAgentsArgs.serializer(),
        outputSerializer = ListAgentsResult.serializer(),
        json = MultiAgentToolJson,
    ) { args ->
        multiAgentResult { agentPathResolver.listAgents(args) }
    }

private suspend fun AgentPathResolver.spawnAgent(
    caller: CodexAgentSession,
    args: SpawnAgentArgs,
): SpawnAgentResult {
    validateTaskSegment(args.taskName)
    require(args.message.isNotBlank()) { "Empty message can't be sent to an agent." }
    val callerPath = pathOf(caller)
    val childPath = "$callerPath/${args.taskName}"
    require(resolveOrNull(childPath) == null) {
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
        child.enqueue(
            AgentCommunication(
                author = callerPath,
                recipient = childPath,
                message = args.message,
                kind = AgentMessageKind.NewTask,
            ),
        )
        child.resumeIfIdle(this)
        return SpawnAgentResult(taskName = childPath, nickname = null)
    } catch (failure: Throwable) {
        caller.subagents.delete(entryIndex)
        throw failure
    }
}

private suspend fun AgentPathResolver.sendMessage(
    caller: CodexAgentSession,
    targetPath: String,
    message: String,
    kind: AgentMessageKind,
    resumeIfIdle: Boolean,
) {
    require(message.isNotBlank()) { "Empty message can't be sent to an agent." }
    val target = requireNotNull(resolveOrNull(targetPath)) {
        "Agent path not found: $targetPath"
    }
    if (resumeIfIdle) {
        require(target.storage.id != rootSession().storage.id) {
            "Follow-up tasks can't target the root agent."
        }
    }
    target.enqueue(
        AgentCommunication(
            author = pathOf(caller),
            recipient = pathOf(target),
            message = message,
            kind = kind,
        ),
    )
    if (resumeIfIdle) {
        target.resumeIfIdle(this)
    }
}

private suspend fun AgentPathResolver.waitForSteer(
    caller: CodexAgentSession,
    args: WaitAgentArgs,
): WaitAgentResult {
    val timeoutMillis = args.timeoutMs ?: MultiAgentTools.DefaultWaitTimeoutMillis
    require(timeoutMillis in MultiAgentTools.MinWaitTimeoutMillis..MultiAgentTools.MaxWaitTimeoutMillis) {
        "timeout_ms must be between ${MultiAgentTools.MinWaitTimeoutMillis} and " +
            "${MultiAgentTools.MaxWaitTimeoutMillis}"
    }
    val pendingSteer = caller.runtime.pendingSteer
    if (pendingSteer.value.isNotEmpty()) {
        return WaitAgentResult("Wait completed.", timedOut = false)
    }
    val received = withTimeoutOrNull(timeoutMillis) {
        pendingSteer.first { content -> content.isNotEmpty() }
    }
    return if (received == null) {
        WaitAgentResult("Wait timed out.", timedOut = true)
    } else {
        WaitAgentResult("Wait completed.", timedOut = false)
    }
}

private suspend fun AgentPathResolver.interruptAgent(
    caller: CodexAgentSession,
    args: InterruptAgentArgs,
): InterruptAgentResult {
    val target = requireNotNull(resolveOrNull(args.target)) {
        "Agent path not found: ${args.target}"
    }
    require(target.storage.id != rootSession().storage.id) { "root is not a spawned agent" }
    require(target.storage.id != caller.storage.id) {
        "an agent cannot interrupt itself; return your result and let the parent interrupt you if needed"
    }
    val previousStatus = target.status()
    target.runtime.runningTurn.value?.cancelAndJoin()
    return InterruptAgentResult(previousStatus)
}

private suspend fun AgentPathResolver.listAgents(
    args: ListAgentsArgs,
): ListAgentsResult {
    val prefix = args.pathPrefix
    if (prefix != null) {
        require(resolveOrNull(prefix) != null) {
            "Agent path not found: $prefix"
        }
    }
    val agents = mutableListOf<ListedAgent>()
    suspend fun collect(
        path: String,
        session: CodexAgentSession,
    ) {
        if (prefix == null || path == prefix || path.startsWith("$prefix/")) {
            agents += ListedAgent(
                agentName = path,
                agentStatus = session.status(),
                lastTaskMessage = session.pendingTaskMessage() ?: session.recoverLastTaskMessage(),
            )
        }
        session.subagents.list().forEach { entryIndex ->
            val child = session.subagents.open(entryIndex)
            if (child.storage.settings.latestIndex() < 0) return@forEach
            val segment = child.storage.settings.latestValue().threadName.substringAfterLast('/')
            collect("$path/$segment", child)
        }
    }
    collect(RootAgentPath, rootSession())
    return ListAgentsResult(agents)
}

/** Starts exactly one direct runtime collection when no turn is already running. */
private fun CodexAgentSession.resumeIfIdle(
    agentPathResolver: AgentPathResolver,
) {
    if (runtime.runningTurn.value != null) return
    launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            runtime.resume().collect {}
            if (runtime.state.value !is CodexAgentStateValue.ToolPending) {
                notifyParent(agentPathResolver, latestAssistantText())
            }
        } catch (_: ConcurrentAgentRuntimeResumeException) {
            // Another follow-up (or direct caller) won the runtime CAS and will consume the steer.
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            notifyParent(
                agentPathResolver,
                "Agent errored: ${failure.message ?: failure::class.simpleName ?: "Agent turn failed"}\n\n" +
                    "This agent's turn failed. If you still need this agent, give it another task.",
            )
        }
    }
}

private suspend fun CodexAgentSession.notifyParent(
    agentPathResolver: AgentPathResolver,
    message: String?,
) {
    val path = agentPathResolver.pathOf(this)
    if (path == RootAgentPath) return
    val parentPath = path.substringBeforeLast('/')
    val parent = agentPathResolver.resolveOrNull(parentPath) ?: return
    parent.enqueue(
        AgentCommunication(
            author = path,
            recipient = parentPath,
            message = message.orEmpty(),
            kind = AgentMessageKind.FinalAnswer,
        ),
    )
}

private fun CodexAgentSession.enqueue(
    communication: AgentCommunication,
) {
    runtime.pendingSteer.update { pending ->
        pending + communication.steerContent
    }
}

private suspend fun AgentPathResolver.rootSession(): CodexAgentSession =
    requireNotNull(resolveOrNull(RootAgentPath)) {
        "Agent path resolver does not expose $RootAgentPath."
    }

private fun CodexAgentState.callerSessionProvider(
    agentPathResolver: AgentPathResolver,
): suspend () -> CodexAgentSession {
    val callerStorageId = storage.id
    return {
        val root = agentPathResolver.rootSession()
        if (root.storage.id == callerStorageId) {
            root
        } else {
            val callerPath = storage.settings.latestValue().threadName
            requireNotNull(agentPathResolver.resolveOrNull(callerPath)) {
                "Current Agent path is not exposed by the Agent path resolver: $callerPath"
            }.also { session ->
                require(session.storage.id == callerStorageId) {
                    "Current Agent state and resolved Session identify different Agents."
                }
            }
        }
    }
}

private suspend fun AgentPathResolver.pathOf(
    session: CodexAgentSession,
): String =
    if (session.storage.id == rootSession().storage.id) {
        RootAgentPath
    } else {
        session.storage.settings.latestValue().threadName.also { path ->
            require(path.startsWith("$RootAgentPath/")) {
                "Subagent thread name must be a canonical Agent path: $path"
            }
        }
    }

private suspend fun CodexAgentSession.status(): MultiAgentStatus =
    when {
        !coroutineContext.job.isActive -> MultiAgentStatus.Shutdown
        runtime.runningTurn.value != null -> MultiAgentStatus.Running
        else -> when (runtime.state.value) {
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
    }

private fun CodexAgentSession.pendingTaskMessage(): String? =
    runtime.pendingSteer.value
        .filterIsInstance<ContentItem.InputText>()
        .lastOrNull()
        ?.text
        ?.payloadOrSelf()

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
                .firstNotNullOfOrNull { content -> content.text.payloadOrSelf() }
                ?.let { return it }

            is ResponseItem.Message -> if (item.role == MessageRole.User) {
                return item.content
                    .filterIsInstance<ContentItem.InputText>()
                    .joinToString("") { content -> content.text }
                    .payloadOrSelf()
                    .ifEmpty { null }
            }

            else -> Unit
        }
        index = storage.history.prevIndex(index)
    }
    return null
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
) {
    val steerContent: List<ContentItem>
        get() = listOf(
            ContentItem.InputText(
                "Message Type: ${kind.wireName}\n" +
                    "Task name: $recipient\n" +
                    "Sender: $author\n" +
                    "Payload:\n$message",
            ),
        )
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

private val ResponseItem.HistoryItem.startsTurn: Boolean
    get() = when (this) {
        is ResponseItem.Message -> role == MessageRole.User
        is ResponseItem.AgentMessage -> content
            .filterIsInstance<AgentMessageInputContent.InputText>()
            .any { content -> content.text.startsWith("Message Type: NEW_TASK\n") }

        else -> false
    }

private fun String.payloadOrSelf(): String =
    substringAfter(PayloadMarker, missingDelimiterValue = this).ifEmpty { this }

private suspend fun <Value> multiAgentResult(
    operation: suspend () -> Value,
): JsonToolHandlerResult<Value> = try {
    jsonToolSuccess(operation())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    jsonToolFailure(
        failure.message ?: failure::class.simpleName ?: "Multi-agent operation failed",
    )
}

private suspend fun multiAgentTextResult(
    operation: suspend () -> Unit,
): JsonToolHandlerResult<String> =
    multiAgentResult {
        operation()
        ""
    }

private val MultiAgentToolJson: Json = Json(ToolBuilderJson) {
    explicitNulls = true
}

private const val RootAgentPath: String = "/root"
private const val PayloadMarker: String = "Payload:\n"
