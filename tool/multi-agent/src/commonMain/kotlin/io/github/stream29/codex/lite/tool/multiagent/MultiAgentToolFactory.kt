package io.github.stream29.codex.lite.tool.multiagent

import io.github.stream29.codex.lite.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentsession.contract.CodexAgentSession
import io.github.stream29.codex.lite.agentsession.contract.listChild
import io.github.stream29.codex.lite.agentsession.contract.pathOf
import io.github.stream29.codex.lite.agentsession.contract.rootSession
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.contract.forkTo
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.initialize
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.openai.AgentMessageInputContent
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.tool.builder.ToolBuilderJson
import io.github.stream29.codex.lite.tool.builder.jsonTool
import io.github.stream29.codex.lite.tool.builder.jsonToolFailure
import io.github.stream29.codex.lite.tool.builder.jsonToolSuccess
import io.github.stream29.codex.lite.tool.builder.textTool
import io.github.stream29.codex.lite.tool.contract.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
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
        try {
            val caller = callerSession()
            validateTaskSegment(args.taskName)
            require(args.message.isNotBlank()) { "Empty message can't be sent to an agent." }
            val callerPath = agentPathResolver.pathOf(caller)
            val childPath = "$callerPath/${args.taskName}"
            require(agentPathResolver.resolveOrNull(childPath) == null) {
                "Agent path already exists: $childPath"
            }
            val forkMode = args.forkTurns
            if (forkMode == SpawnForkMode.All) {
                require(args.model == null && args.reasoningEffort == null) {
                    "A full-history fork must inherit the parent model and reasoning effort."
                }
            }

            val entryIndex = caller.subagents.create()
            val result = try {
                val child = caller.subagents.open(entryIndex)
                initializeSpawnStorage(caller, child, args, childPath, forkMode)
                child.enqueue(
                    agentMessage(
                        author = callerPath,
                        recipient = childPath,
                        payload = args.message,
                        type = AgentMessageType.NewTask,
                    ),
                )
                child.resumeIfIdle()
                SpawnAgentResult(taskName = childPath, nickname = null)
            } catch (failure: Throwable) {
                caller.subagents.delete(entryIndex)
                throw failure
            }
            jsonToolSuccess(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            jsonToolFailure(
                failure.message ?: failure::class.simpleName ?: "Multi-agent operation failed",
            )
        }
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
        try {
            val caller = callerSession()
            require(args.message.isNotBlank()) { "Empty message can't be sent to an agent." }
            val target = requireNotNull(agentPathResolver.resolveOrNull(args.target)) {
                "Agent path not found: ${args.target}"
            }
            target.enqueue(
                agentMessage(
                    author = agentPathResolver.pathOf(caller),
                    recipient = agentPathResolver.pathOf(target),
                    payload = args.message,
                    type = AgentMessageType.Message,
                ),
            )
            jsonToolSuccess("")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            jsonToolFailure(
                failure.message ?: failure::class.simpleName ?: "Multi-agent operation failed",
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
        try {
            val caller = callerSession()
            require(args.message.isNotBlank()) { "Empty message can't be sent to an agent." }
            val target = requireNotNull(agentPathResolver.resolveOrNull(args.target)) {
                "Agent path not found: ${args.target}"
            }
            require(target.storage.id != agentPathResolver.rootSession().storage.id) {
                "Follow-up tasks can't target the root agent."
            }
            target.enqueue(
                agentMessage(
                    author = agentPathResolver.pathOf(caller),
                    recipient = agentPathResolver.pathOf(target),
                    payload = args.message,
                    type = AgentMessageType.NewTask,
                ),
            )
            target.resumeIfIdle()
            jsonToolSuccess("")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            jsonToolFailure(
                failure.message ?: failure::class.simpleName ?: "Multi-agent operation failed",
            )
        }
    }
}

/** Creates a `wait_agent` tool that observes the owning runtime's pending steer. */
public fun waitAgentTool(
    pendingSteer: StateFlow<List<ResponseItem.Steerable>>,
): Tool =
    jsonTool(
        spec = MultiAgentTools.waitAgentSpec,
        inputDeserializer = WaitAgentArgs.serializer(),
        outputSerializer = WaitAgentResult.serializer(),
        json = MultiAgentToolJson,
    ) { args ->
        try {
            val timeoutMillis = args.timeoutMs ?: MultiAgentTools.DefaultWaitTimeoutMillis
            require(timeoutMillis in MultiAgentTools.MinWaitTimeoutMillis..MultiAgentTools.MaxWaitTimeoutMillis) {
                "timeout_ms must be between ${MultiAgentTools.MinWaitTimeoutMillis} and " +
                    "${MultiAgentTools.MaxWaitTimeoutMillis}"
            }
            val received = withTimeoutOrNull(timeoutMillis.milliseconds) {
                pendingSteer.first { content -> content.isNotEmpty() }
            }
            val result = if (received == null) {
                WaitAgentResult("Wait timed out.", timedOut = true)
            } else {
                WaitAgentResult("Wait completed.", timedOut = false)
            }
            jsonToolSuccess(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            jsonToolFailure(
                failure.message ?: failure.toString(),
            )
        }
    }

/** Creates an independent `interrupt_agent` tool bound to this Agent. */
public fun CodexAgentState.interruptAgentTool(
    agentPathResolver: AgentPathResolver,
): Tool {
    return jsonTool(
        spec = MultiAgentTools.interruptAgentSpec,
        inputDeserializer = InterruptAgentArgs.serializer(),
        outputSerializer = InterruptAgentResult.serializer(),
        json = MultiAgentToolJson,
    ) { args ->
        try {
            val target = requireNotNull(agentPathResolver.resolveOrNull(args.target)) {
                "Agent path not found: ${args.target}"
            }
            require(args.target != "/root") {
                "root is not a spawned agent"
            }
            require(target.storage.id != storage.id) {
                "an agent cannot interrupt itself; return your result and let the parent interrupt you if needed"
            }
            val previousStatus = target.status()
            target.runtime.runningTurn.value?.cancelAndJoin()
            jsonToolSuccess(InterruptAgentResult(previousStatus))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            jsonToolFailure(
                failure.message ?: failure::class.simpleName ?: "Multi-agent operation failed",
            )
        }
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
        try {
            val prefix = args.pathPrefix
            if (prefix != null) {
                require(agentPathResolver.resolveOrNull(prefix) != null) {
                    "Agent path not found: $prefix"
                }
            }
            val agents = agentPathResolver.rootSession()
                .listAgents(agentPathResolver)
                .filter { it.agentName.startsWith(prefix ?: "") }
            val result = ListAgentsResult(agents)
            jsonToolSuccess(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            jsonToolFailure(
                failure.message ?: failure::class.simpleName ?: "Multi-agent operation failed",
            )
        }
    }

private suspend fun CodexAgentSession.listAgents(
    agentPathResolver: AgentPathResolver
): List<ListedAgent> = buildList {
    add(
        ListedAgent(
            agentPathResolver.pathOf(this@listAgents),
            this@listAgents.status()
        )
    )
    agentPathResolver.listChild(this@listAgents).forEach {
        addAll(it.listAgents(agentPathResolver))
    }
}

/** Starts exactly one direct runtime collection when no turn is already running. */
private fun CodexAgentSession.resumeIfIdle() {
    if (runtime.runningTurn.value != null) return
    launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            runtime.resume().collect {}
        } catch (_: ConcurrentAgentRuntimeResumeException) {
            // Another follow-up (or direct caller) won the runtime CAS and will consume the steer.
        }
    }
}

private fun CodexAgentSession.enqueue(
    message: ResponseItem.AgentMessage,
) {
    runtime.pendingSteer.update { pending ->
        pending + message
    }
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

private fun CodexAgentSession.status(): MultiAgentStatus =
    if (runtime.runningTurn.value != null) MultiAgentStatus.Running else MultiAgentStatus.Idle

private enum class AgentMessageType(val wireName: String) {
    NewTask("NEW_TASK"),
    Message("MESSAGE"),
}

private fun agentMessage(
    author: String,
    recipient: String,
    payload: String,
    type: AgentMessageType,
): ResponseItem.AgentMessage =
    ResponseItem.AgentMessage(
        author = author,
        recipient = recipient,
        content = listOf(
            AgentMessageInputContent.InputText(
                "Message Type: ${type.wireName}\n" +
                    "Task name: $recipient\n" +
                    "Sender: $author\n" +
                    "Payload:\n$payload",
            ),
        ),
    )

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

private val MultiAgentToolJson: Json = Json(ToolBuilderJson) {
    explicitNulls = true
}
