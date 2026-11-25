@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.migration.KodexHomeVersionException
import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.nextCronTriggerAt
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.host.connectBackend
import io.github.stream29.kodex.backend.host.ensureBackend
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpCatalogTool
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.ReservedMcpServerNames
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.contextWindowTokenStatus
import io.github.stream29.kodex.openai.effectiveContextWindow
import io.github.stream29.kodex.openai.resolvedAutoCompactionTokenLimit
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.rpc.models.GeneratedImageArtifact
import io.github.stream29.kodex.rpc.models.AgentSqlError
import io.github.stream29.kodex.rpc.models.AgentSqlInputBytes
import io.github.stream29.kodex.rpc.models.ImageGenToolArguments
import io.github.stream29.kodex.rpc.models.SessionSpawnRequest
import io.github.stream29.kodex.rpc.models.SessionSpawnSource
import io.github.stream29.kodex.rpc.models.SessionSpawnResult
import io.github.stream29.kodex.rpc.models.SessionSpawnRejection
import io.github.stream29.kodex.rpc.models.SubagentSpawnRejection
import io.github.stream29.kodex.rpc.models.SubagentSpawnRequest
import io.github.stream29.kodex.rpc.models.SubagentSpawnResult
import io.github.stream29.kodex.rpc.models.SubagentSpawnStage
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.KodexAgentIdEnvironmentVariable
import io.github.stream29.kodex.tool.unifiedexec.KodexHomeEnvironmentVariable
import io.github.stream29.kodex.tool.unifiedexec.KodexInvocationIdEnvironmentVariable
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.AskAnswerRejected
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.AgentNotActive
import io.github.stream29.kodex.utils.rpcexception.AgentNotFound
import io.github.stream29.kodex.utils.rpcexception.AgentCompleted
import io.github.stream29.kodex.utils.rpcexception.SubagentCompletionNoticeFailed
import io.github.stream29.kodex.utils.rpcexception.SessionHasRunningSubagents
import io.github.stream29.kodex.utils.rpcexception.ShellSessionNotRegistered
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class CliTerminalSummary(
    @SerialName("session_id")
    val sessionId: Int,
    val command: String,
    val workdir: String? = null,
    val completed: Boolean,
)

@Serializable
internal data class CliTerminalWriteOutput(
    @SerialName("session_id")
    val sessionId: Int,
    @SerialName("bytes_written")
    val bytesWritten: Int,
    val eof: Boolean,
)

@Serializable
internal data class CliTerminalKillOutput(
    @SerialName("session_id")
    val sessionId: Int,
    val terminated: Boolean,
)

@Serializable
internal data class CliScheduleDeleteOutput(
    @SerialName("schedule_id")
    val scheduleId: Long,
    val deleted: Boolean,
)

@Serializable
internal data class CliMcpServerSummary(
    val state: McpManagedServerState,
    @SerialName("reserved_name_conflict")
    val reservedNameConflict: Boolean = false,
)

@Serializable
internal data class CliMcpServerDetail(
    val state: McpManagedServerState,
    @SerialName("reserved_name_conflict")
    val reservedNameConflict: Boolean = false,
    val tools: List<McpCatalogTool> = emptyList(),
)

internal suspend fun resolveExistingOrCandidatePath(path: Path): Path =
    if (SystemCoroutineFileSystem.exists(path)) SystemCoroutineFileSystem.resolve(path) else path

internal suspend fun resolveToolBackend(
    target: CliBackendTarget?,
    defaultHome: Path,
    env: (String) -> String? = ::environmentVariable,
    ensure: suspend (Path) -> BackendEndpoint = ::ensureBackend,
    connect: suspend (Path, Int) -> BackendEndpoint = ::connectBackend,
): Pair<Path, BackendEndpoint> {
    val rawHome = target?.backendHome ?: env(KodexHomeEnvironmentVariable)?.takeIf { it.isNotBlank() }
    val resolvedHome = resolveExistingOrCandidatePath(if (rawHome == null) defaultHome else Path(rawHome))
    val port = target?.port
    val endpoint = if (port != null) connect(resolvedHome, port) else ensure(resolvedHome)
    return resolvedHome to endpoint
}

internal val DefaultCliMcpConnectTimeout: Duration = 30.seconds

private sealed interface DynamicMcpResolution {
    data class Available(val domains: List<McpServerDomainCommand>) : DynamicMcpResolution
    data class Unavailable(val diagnostic: String) : DynamicMcpResolution
}

/**
 * Dispatches [arguments] with at most one lexical RPC connection across dynamic MCP domain
 * discovery and leaf tool command execution, without background jobs or deferred handles.
 */
internal suspend fun dispatchCliWithToolRpc(
    arguments: List<String>,
    defaultHome: Path,
    output: (message: String, error: Boolean) -> Unit,
    env: (String) -> String? = ::environmentVariable,
    ensure: suspend (Path) -> BackendEndpoint = ::ensureBackend,
    connect: suspend (Path, Int) -> BackendEndpoint = ::connectBackend,
    mcpConnectTimeout: Duration = DefaultCliMcpConnectTimeout,
    openServices: suspend (Path, BackendEndpoint, suspend (RpcServices) -> Int) -> Int =
        { home, endpoint, use ->
            withLocalRpcClient(home, endpoint) { connection ->
                use(RpcServices(RestoringRpcClient(connection)))
            }
        },
    executeTool: suspend (KodexToolLeafCommand, RpcServices) -> Int = { command, services ->
        executeCliToolCommand(command, services, env = env)
    },
    executeLeaf: suspend (
        command: KodexLeafCommand,
        invokeTool: suspend (KodexToolLeafCommand) -> Int,
    ) -> Int,
): Int {
    var preopened: Pair<CliBackendTarget?, RpcServices>? = null
    return dispatchCli(
        arguments = arguments,
        output = output,
        withMcpDomains = { target, block ->
            val serverName = checkNotNull(arguments.firstOrNull()) {
                "Dynamic MCP dispatch requires a target server name."
            }
            var enteredBlock = false
            try {
                val (resolvedHome, endpoint) = resolveToolBackend(target, defaultHome, env, ensure, connect)
                openServices(resolvedHome, endpoint) { services ->
                    when (val resolution = resolveDynamicMcpDomains(services, serverName, mcpConnectTimeout)) {
                        is DynamicMcpResolution.Available -> {
                            enteredBlock = true
                            preopened = target to services
                            try {
                                block(resolution.domains)
                            } finally {
                                preopened = null
                            }
                        }
                        is DynamicMcpResolution.Unavailable -> {
                            output("Unable to run Kodex: ${resolution.diagnostic}", true)
                            1
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure is Error || enteredBlock) throw failure
                currentCoroutineContext().ensureActive()
                output("Unable to run Kodex: ${failure.message ?: "operation failed"}", true)
                1
            }
        },
        execute = { leaf ->
            executeLeaf(leaf) invokeTool@{ toolLeaf ->
                val target = toolLeaf.backend.resolve()
                val current = preopened
                if (current != null && current.first == target) {
                    executeTool(toolLeaf, current.second)
                } else {
                    var entered = false
                    var exitCode: Int? = null
                    try {
                        val (resolvedHome, endpoint) = resolveToolBackend(target, defaultHome, env, ensure, connect)
                        openServices(resolvedHome, endpoint) { services ->
                            entered = true
                            executeTool(toolLeaf, services).also { exitCode = it }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        if (toolLeaf !is SessionAgentOperation && toolLeaf !is AgentSqlCommand) throw failure
                        if (failure is Error) throw failure
                        currentCoroutineContext().ensureActive()
                        exitCode?.let { return@invokeTool it } // A known reply survives connection teardown failure.
                        if (entered) throw failure
                        val reason = (failure as? KodexHomeVersionException)?.message
                            ?: "Could not connect to the selected backend."
                        if (toolLeaf is AgentSqlCommand) return@invokeTool writeAgentSqlError(
                            AgentSqlError.UNAVAILABLE, reason,
                        ) { output(it, false) }
                        writeOperationFailure("connection_failed", reason,
                            "connect", CliOperationOutcome.Failed,
                            (toolLeaf as? ExplicitAgentOperation)?.agentId ?: (toolLeaf as? AgentCompleteCommand)?.agentId,
                            (toolLeaf as? ExplicitSessionOperation)?.sessionId, write = { output(it, false) })
                    }
                }
            }
        },
    )
}

private suspend fun resolveDynamicMcpDomains(
    services: RpcServices,
    serverName: String,
    mcpConnectTimeout: Duration,
): DynamicMcpResolution {
    val initialDomains = loadDynamicMcpDomains(services)
    if (initialDomains.any { it.serverName == serverName }) {
        return DynamicMcpResolution.Available(initialDomains)
    }

    var servers = services.global.getMcpServers()
    var state = servers.firstOrNull { it.serverName == serverName }
    val configuredInSettings = if (state == null) {
        try {
            services.global.getSettings().mcpServers[serverName]
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }

    val shouldWait = when {
        state != null -> state.enabled && (state.connection == null || state.connection == McpClientState.Connecting)
        configuredInSettings != null -> true
        else -> false
    }
    if (shouldWait) {
        val sawInManager = state != null
        val awaited = withTimeoutOrNull(mcpConnectTimeout) {
            services.global.getMcpServersFlow().first { list ->
                val candidate = list.firstOrNull { it.serverName == serverName }
                if (candidate == null) {
                    sawInManager
                } else {
                    !candidate.enabled ||
                        (candidate.connection != null && candidate.connection != McpClientState.Connecting)
                }
            }
        }
        servers = awaited ?: services.global.getMcpServers()
        state = servers.firstOrNull { it.serverName == serverName }
        val refreshedDomains = loadDynamicMcpDomains(services)
        if (refreshedDomains.any { it.serverName == serverName }) {
            return DynamicMcpResolution.Available(refreshedDomains)
        }
    }

    return DynamicMcpResolution.Unavailable(
        formatUnavailableMcpServerDiagnostic(serverName, state, servers, mcpConnectTimeout),
    )
}

private fun formatUnavailableMcpServerDiagnostic(
    serverName: String,
    state: McpManagedServerState?,
    allServers: List<McpManagedServerState>,
    mcpConnectTimeout: Duration,
): String {
    if (state == null) {
        val available = allServers.map { it.serverName }.filter { it !in ReservedMcpServerNames }.sorted()
        return if (available.isNotEmpty()) {
            "MCP server '$serverName' is not configured. Configured MCP servers: ${available.joinToString(", ")}. " +
                "Run 'kodex mcp list' or 'kodex --help' for available commands."
        } else {
            "MCP server '$serverName' is not configured (no MCP servers are configured). " +
                "Run 'kodex --help' for built-in commands."
        }
    }
    if (!state.enabled) {
        return "MCP server '$serverName' is disabled. Run 'kodex mcp enable $serverName' to enable it."
    }
    return when (val connection = state.connection) {
        McpClientState.AuthenticationBlocked ->
            "MCP server '$serverName' requires authentication (${state.authentication.describeForCli()}). " +
                "Run 'kodex mcp status $serverName' to inspect it."
        null, McpClientState.Connecting ->
            "MCP server '$serverName' is still connecting and did not become ready within $mcpConnectTimeout. " +
                "Run 'kodex mcp status $serverName' to inspect it."
        is McpClientState.Failed ->
            "MCP server '$serverName' failed to connect (${connection.reason}). " +
                "Run 'kodex mcp status $serverName' or 'kodex mcp reconnect $serverName'."
        McpClientState.Closed ->
            "MCP server '$serverName' is closed and unavailable. " +
                "Run 'kodex mcp status $serverName' or 'kodex mcp reconnect $serverName'."
        McpClientState.Healthy ->
            "MCP server '$serverName' is connected but publishes no tools."
    }
}

private fun McpAuthenticationState.describeForCli(): String = when (this) {
    McpAuthenticationState.NotConfigured -> "not configured"
    McpAuthenticationState.LoginRequired -> "login required"
    McpAuthenticationState.ReauthorizationRequired -> "reauthorization required"
    McpAuthenticationState.Authorizing -> "authorizing"
    McpAuthenticationState.Authorized -> "authorized"
    McpAuthenticationState.Refreshing -> "refreshing"
    is McpAuthenticationState.Failed -> "failed: $message"
}

internal fun resolveTargetAgentId(
    options: AgentTargetOptions,
    env: (String) -> String? = ::environmentVariable,
): Int {
    options.agentId?.let { return it }
    val raw = env(KodexAgentIdEnvironmentVariable)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("Missing target Agent ID: pass --agent <id> or set $KodexAgentIdEnvironmentVariable.")
    val parsed = raw.toIntOrNull()
        ?: error("Invalid $KodexAgentIdEnvironmentVariable '$raw': expected a non-negative integer.")
    require(parsed >= 0) { "Invalid $KodexAgentIdEnvironmentVariable '$raw': Agent ID must be non-negative." }
    return parsed
}

internal suspend fun loadDynamicMcpDomains(services: RpcServices): List<McpServerDomainCommand> {
    val toolsByServer = services.global.listMcpTools().groupBy { it.serverName }
    return toolsByServer.entries
        .sortedBy { it.key }
        .map { (serverName, tools) ->
            McpServerDomainCommand(
                serverName = serverName,
                serverInstructions = tools.first().serverInstructions,
                tools = tools,
            )
        }
}

internal suspend fun executeCliToolCommand(
    command: KodexToolLeafCommand,
    services: RpcServices,
    env: (String) -> String? = ::environmentVariable,
    readStdin: () -> String = when (command) {
        is AgentSqlCommand -> { { readCliStdinTextStrict(AgentSqlInputBytes) } }
        is SessionAgentOperation -> { { readCliStdinTextStrict() } }
        else -> ::readCliStdinText
    },
    writeStdout: (String) -> Unit = ::println,
    now: () -> Instant = Clock.System::now,
): Int {
    when (command) {
        is TerminalListCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            services.runtime.keepAgentAlive(agentId)
            val sessions = services.runtime.getShellSessions(agentId)
                .entries
                .sortedBy { it.key }
                .map { (id, state) ->
                    CliTerminalSummary(
                        sessionId = id,
                        command = state.arguments.command,
                        workdir = state.arguments.workdir,
                        completed = state.completed,
                    )
                }
            writeStdout(CliToolJson.encodeToString(ListSerializer(CliTerminalSummary.serializer()), sessions))
        }
        is TerminalReadCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            services.runtime.keepAgentAlive(agentId)
            val slice = withTerminalRegistration {
                services.runtime.readShellSessionOutput(
                    sessionIndex = agentId,
                    shellSessionId = command.sessionId,
                    offset = command.offset,
                    limit = command.limit,
                )
            }
            writeStdout(CliToolJson.encodeToString(StdoutSlice.serializer(), slice))
        }
        is TerminalWriteCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            val text = when {
                command.useStdin -> readStdin()
                command.chars != null -> command.chars!!
                else -> ""
            }
            services.runtime.keepAgentAlive(agentId)
            services.runtime.writeShellSessionStdin(
                sessionIndex = agentId,
                shellSessionId = command.sessionId,
                chars = text,
                closeInput = command.closeInput,
            )
            writeStdout(
                CliToolJson.encodeToString(
                    CliTerminalWriteOutput.serializer(),
                    CliTerminalWriteOutput(
                        sessionId = command.sessionId,
                        bytesWritten = text.encodeToByteArray().size,
                        eof = command.closeInput,
                    ),
                ),
            )
        }
        is TerminalKillCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            services.runtime.keepAgentAlive(agentId)
            withTerminalRegistration { services.runtime.closeShellSession(agentId, command.sessionId) }
            writeStdout(
                CliToolJson.encodeToString(
                    CliTerminalKillOutput.serializer(),
                    CliTerminalKillOutput(sessionId = command.sessionId, terminated = true),
                ),
            )
        }
        is AskCreateCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            val args = command.arguments.resolve(RequestUserInputArgs.serializer(), readStdin)
            services.runtime.keepAgentAlive(agentId)
            val created = services.runtime.createAskUser(agentId, args)
            writeStdout(CliToolJson.encodeToString(AskUserEntry.serializer(), created))
        }
        is AskListCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            services.runtime.keepAgentAlive(agentId)
            val entries = readAskUsers(services.runtime, agentId)
            writeStdout(CliToolJson.encodeToString(ListSerializer(AskUserEntry.serializer()), entries))
        }
        is AskGetCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            services.runtime.keepAgentAlive(agentId)
            val entry = readAskUsers(services.runtime, agentId).firstOrNull { it.askId == command.askId }
                ?: error("Ask entry ${command.askId} not found in Agent $agentId.")
            writeStdout(CliToolJson.encodeToString(AskUserEntry.serializer(), entry))
        }
        is ScheduleCreateCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            val currentNow = now()
            val (triggerAt, cron) = when (val trigger = command.trigger) {
                is CliScheduleTrigger.DelaySeconds -> (currentNow + trigger.seconds.seconds) to null
                is CliScheduleTrigger.At -> trigger.triggerAt to null
                is CliScheduleTrigger.Cron -> nextCronTriggerAt(trigger.expression, currentNow) to trigger.expression
            }
            val created = services.global.createSchedule(
                agentId = agentId,
                triggerAt = triggerAt,
                cron = cron,
                description = command.description,
            )
            writeStdout(CliToolJson.encodeToString(ScheduleRecord.serializer(), created))
        }
        is ScheduleListCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            val records = services.global.listSchedules(agentId)
            writeStdout(CliToolJson.encodeToString(ListSerializer(ScheduleRecord.serializer()), records))
        }
        is ScheduleDeleteCommand -> {
            val deleted = services.global.deleteSchedule(command.scheduleId)
            if (!deleted) error("Schedule ${command.scheduleId} not found.")
            writeStdout(
                CliToolJson.encodeToString(
                    CliScheduleDeleteOutput.serializer(),
                    CliScheduleDeleteOutput(scheduleId = command.scheduleId, deleted = true),
                ),
            )
        }
        is SessionAgentOperation -> return executeSessionAgentOperation(command, services, writeStdout, now, env, readStdin)
        is AgentSqlCommand -> return executeAgentSql(command, services, readStdin, writeStdout)
        is SearchCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            val commands = command.resolveCommands().resolve(SearchCommands.serializer(), readStdin)
            services.runtime.keepAgentAlive(agentId)
            val response = services.runtime.runWebSearch(agentId, commands)
            writeStdout(response.output)
        }
        is ImageCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            services.runtime.keepAgentAlive(agentId)
            val artifact = services.runtime.generateImage(
                agentId,
                ImageGenToolArguments(
                    prompt = command.prompt,
                    referencedImagePaths = command.referencedImagePaths,
                ),
            )
            writeStdout(CliToolJson.encodeToString(GeneratedImageArtifact.serializer(), artifact))
        }
        is McpListCommand -> {
            val summaries = services.global.getMcpServers().map { state ->
                CliMcpServerSummary(
                    state = state,
                    reservedNameConflict = state.serverName in ReservedMcpServerNames,
                )
            }
            writeStdout(CliToolJson.encodeToString(ListSerializer(CliMcpServerSummary.serializer()), summaries))
        }
        is McpStatusCommand -> {
            val state = services.global.getMcpServers().firstOrNull { it.serverName == command.serverName }
                ?: error("MCP server '${command.serverName}' is not configured.")
            val tools = services.global.listMcpTools().filter { it.serverName == command.serverName }
            writeStdout(
                CliToolJson.encodeToString(
                    CliMcpServerDetail.serializer(),
                    CliMcpServerDetail(
                        state = state,
                        reservedNameConflict = state.serverName in ReservedMcpServerNames,
                        tools = tools,
                    ),
                ),
            )
        }
        is McpReconnectCommand -> {
            services.global.reconnectMcpServer(command.serverName)
            val state = services.global.getMcpServers().firstOrNull { it.serverName == command.serverName }
                ?: error("MCP server '${command.serverName}' is not configured.")
            writeStdout(CliToolJson.encodeToString(McpManagedServerState.serializer(), state))
        }
        is McpSetEnabledCommand -> {
            val state = setMcpServerEnabled(services, command.serverName, enabled = command.enabled)
            writeStdout(CliToolJson.encodeToString(McpManagedServerState.serializer(), state))
        }
        is McpServerToolCommand -> {
            val arguments = command.arguments.resolve(JsonObject.serializer(), readStdin)
            val result = services.global.callMcpTool(command.tool.serverName, command.tool.toolName, arguments)
            writeStdout(CliToolJson.encodeToString(CallToolResult.serializer(), result))
        }
        is ToolListCommand -> {
            val mcpDomains = loadDynamicMcpDomains(services)
            collectToolListLines(KodexCommand(mcpDomains)).forEach(writeStdout)
        }
    }
    return 0
}

private fun writeOperationFailure(
    code: String, message: String, phase: String, outcome: CliOperationOutcome, agentId: Int? = null,
    sessionId: Int? = null,
    write: (String) -> Unit,
    parentAgentId: Int? = null,
): Int {
    val failure = CliOperationFailure(
        code, message, phase, outcome, sessionId = sessionId, agentId = agentId, parentAgentId = parentAgentId,
    )
    write(CliToolJson.encodeToString(CliOperationEnvelope.serializer(), CliOperationEnvelope(ok = false, error = failure)))
    return 1
}

private class OperationRejected(val code: String, val diagnostic: String) : Exception(diagnostic)

private suspend fun executeSessionAgentOperation(
    command: SessionAgentOperation, services: RpcServices, write: (String) -> Unit, now: () -> Instant,
    env: (String) -> String?, readStdin: () -> String,
): Int {
    var agentId = (command as? ExplicitAgentOperation)?.agentId ?: (command as? AgentCompleteCommand)?.agentId
    var sessionId = (command as? ExplicitSessionOperation)?.sessionId
    var parentAgentId: Int? = null
    var phase = "input"
    var mutationStarted = false
    suspend fun activate(id: Int) {
        phase = "activate"
        services.runtime.keepAgentAlive(id)
    }
    suspend fun submit(id: Int, text: String, invocationId: String? = null) {
        if (invocationId == null) activate(id)
        phase = "submit"
        mutationStarted = true
        services.runtime.submit(id, listOf(ContentItem.InputText(text)), invocationId)
    }
    fun agentReceipt(id: Int, accepted: Boolean = false): JsonObject = buildJsonObject {
        put("agent_id", id)
        if (accepted) put("accepted", true)
    }
    fun fail(code: String, message: String, outcome: CliOperationOutcome): Int =
        writeOperationFailure(code, message, phase, outcome, agentId, sessionId, write, parentAgentId)
    val result: JsonElement = try {
        when (command) {
            is SessionListCommand -> {
                phase = "list"
                val rows = services.global.getSessionCatalog(command.includeArchived).map { entry ->
                    CliSessionCatalogRow(
                        entry.sessionIndex, entry.rootAgentId, entry.name, entry.archived, entry.running,
                        entry.isActive, entry.createdAt?.toString(), entry.updatedAt?.toString(),
                    )
                }
                CliToolJson.encodeToJsonElement(CliSessionCatalogOutput.serializer(), CliSessionCatalogOutput(rows))
            }
            is AgentStatusCommand -> {
                phase = "status"
                CliToolJson.encodeToJsonElement(CliAgentStatusOutput.serializer(), readAgentStatus(services, command.agentId, now))
            }
            is AgentSpawnCommand -> {
                val text = command.message.readText(readStdin)
                if (text.isBlank()) throw OperationRejected("invalid_message", "A nonblank task is required.")
                val source = readInvocationSource(env)
                    ?: throw OperationRejected("invalid_source", "Run this command from the calling Agent's tool context.")
                val directory = command.cwd?.let { raw ->
                    require('\u0000' !in raw)
                    val path = Path(raw)
                    if (path.isAbsolute) path else Path(SystemCoroutineFileSystem.resolve(Path(".")), raw)
                }
                phase = "spawn"
                mutationStarted = true
                when (val reply = services.runtime.spawnSubagent(
                    SubagentSpawnRequest(source, text, command.name, command.model, command.reasoningEffort, directory?.toString()),
                )) {
                    is SubagentSpawnResult.Created -> buildJsonObject {
                        put("session_id", reply.sessionId)
                        put("parent_agent_id", reply.parentAgentId)
                        put("agent_id", reply.agentId)
                        put("submitted", true)
                    }
                    is SubagentSpawnResult.Rejected -> throw when (reply.reason) {
                        SubagentSpawnRejection.InvalidSource ->
                            OperationRejected("invalid_source", "The calling Agent is not live on the selected backend.")
                        SubagentSpawnRejection.CreationRejected ->
                            OperationRejected("creation_rejected", "The calling Agent's settings do not allow creating children.")
                        SubagentSpawnRejection.InvalidDirectory ->
                            OperationRejected("invalid_directory", "The child's working directory is not a valid directory.")
                        SubagentSpawnRejection.InvalidConfiguration ->
                            OperationRejected("invalid_configuration", "The child's name or model configuration is invalid.")
                        SubagentSpawnRejection.InvalidMessage ->
                            OperationRejected("invalid_message", "A nonblank task is required.")
                    }
                    is SubagentSpawnResult.Partial -> {
                        sessionId = reply.sessionId
                        parentAgentId = reply.parentAgentId
                        agentId = reply.agentId
                        phase = when (reply.stage) {
                            SubagentSpawnStage.Record -> "record"
                            SubagentSpawnStage.Submit -> "submit"
                        }
                        return fail("subagent_partial", "The child exists, but the operation did not finish. Do not replay spawn.", CliOperationOutcome.Failed)
                    }
                }
            }
            is SessionSpawnCommand -> {
                val message = command.message?.readText(readStdin)
                val source = readInvocationSource(env)
                val launch = SystemCoroutineFileSystem.resolve(Path("."))
                val absoluteOverride = command.cwd?.let { raw ->
                    require('\u0000' !in raw)
                    val path = Path(raw)
                    if (path.isAbsolute) path else Path(launch, raw)
                }
                phase = "create"
                mutationStarted = true
                val response = services.global.spawnSession(SessionSpawnRequest(
                    launch.toString(), absoluteOverride?.toString(), source, command.model, command.reasoningEffort,
                    command.serviceTier, command.requestUserInputMode,
                ))
                val created = when (response) {
                    is SessionSpawnResult.Created -> response
                    is SessionSpawnResult.Rejected -> when (response.reason) {
                        SessionSpawnRejection.InvalidSource -> throw OperationRejected("invalid_source", "The calling Agent is not live on the selected backend.")
                        SessionSpawnRejection.InvalidDirectory -> throw OperationRejected("invalid_directory", "The initial working directory is not a valid directory.")
                        SessionSpawnRejection.InvalidConfiguration -> throw OperationRejected("invalid_configuration", "The selected model does not support the requested configuration.")
                    }
                }
                sessionId = created.sessionId
                agentId = created.rootAgentId
                mutationStarted = false
                if (message != null) submit(created.rootAgentId, message, created.invocationId)
                buildJsonObject {
                    put("session_id", created.sessionId)
                    put("root_agent_id", created.rootAgentId)
                    put("submitted", message != null)
                }
            }
            is AgentSubmitCommand -> {
                submit(command.agentId, command.message.readText(readStdin))
                agentReceipt(command.agentId, accepted = true)
            }
            is AgentResumeCommand -> {
                activate(command.agentId)
                phase = "resume"
                mutationStarted = true
                if (!services.runtime.requestResume(command.agentId)) {
                    throw OperationRejected("resume_not_admitted", "The Agent cannot accept a new turn now.")
                }
                agentReceipt(command.agentId, accepted = true)
            }
            is AgentStopCommand -> {
                activate(command.agentId)
                phase = "stop"
                mutationStarted = true
                services.runtime.cancelRunningTurn(command.agentId)
                agentReceipt(command.agentId)
            }
            is AgentCompleteCommand -> {
                val source = if (command.agentId == null) {
                    readInvocationSource(env) ?: throw OperationRejected(
                        "invalid_source", "Complete requires a calling Agent context or --agent <id>.",
                    )
                } else {
                    null
                }
                val target = command.agentId ?: checkNotNull(source).agentId
                agentId = target
                if (source == null) activate(target)
                phase = "complete"
                mutationStarted = true
                try {
                    services.runtime.completeAgent(target, source?.invocationId)
                } catch (failure: AgentNotActive) {
                    if (source == null) throw failure
                    throw OperationRejected("invalid_source", "The calling Agent is not live on the selected backend.")
                } catch (failure: AgentNotFound) {
                    if (source == null) throw failure
                    throw OperationRejected("invalid_source", "The calling Agent is not live on the selected backend.")
                }
                buildJsonObject {
                    put("agent_id", target)
                    put("completed", true)
                }
            }
            is SessionRenameCommand -> {
                val rootId = services.global.getRootAgentId(command.sessionId)
                activate(rootId)
                phase = "rename"
                renameSessionTitle(services.runtime, rootId, command.name) { mutationStarted = true }
                buildJsonObject {
                    put("session_id", command.sessionId)
                    put("name", command.name)
                }
            }
            is AgentForkCommand -> {
                phase = "fork"
                mutationStarted = true
                val forked = services.global.forkAgent(command.agentId)
                sessionId = forked
                val rootId = services.global.getRootAgentId(forked)
                buildJsonObject {
                    put("session_id", forked)
                    put("root_agent_id", rootId)
                }
            }
            is SessionArchiveCommand, is SessionUnarchiveCommand -> {
                val target = checkNotNull(sessionId)
                val archive = command is SessionArchiveCommand
                phase = if (archive) "archive" else "unarchive"
                mutationStarted = true
                if (archive) services.global.archiveSession(target) else services.global.unarchiveSession(target)
                buildJsonObject {
                    put("session_id", target)
                    put("archived", archive)
                }
            }
            is SessionDeleteCommand -> {
                phase = "delete"
                mutationStarted = true
                val deleted = services.global.deleteSession(command.sessionId)
                buildJsonObject {
                    put("session_id", command.sessionId)
                    put("deleted", deleted)
                }
            }
            is AgentCompactCommand, is AgentClearPendingCommand -> {
                val target = checkNotNull(agentId)
                val compact = command is AgentCompactCommand
                activate(target)
                phase = if (compact) "compact" else "clear_pending"
                mutationStarted = true
                val latest = if (compact) services.runtime.forcedCompact(target) else services.runtime.clearPending(target)
                buildJsonObject {
                    put("agent_id", target)
                    put("latest_index", latest)
                }
            }
            is AskAnswerCommand -> {
                val response = CliToolJson.decodeFromString(RequestUserInputResponse.serializer(), command.response.readText(readStdin))
                activate(command.agentId)
                phase = "answer"
                mutationStarted = true
                services.runtime.submitAskUserAnswer(command.agentId, command.askId, response)
                buildJsonObject {
                    put("agent_id", command.agentId)
                    put("ask_id", command.askId)
                    put("accepted", true)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: OperationRejected) {
        return fail(rejected.code, rejected.diagnostic, CliOperationOutcome.Rejected)
    } catch (rejected: AskAnswerRejected) {
        return if (rejected.alreadyAnswered) {
            fail("already_answered", "The question has already been answered.", CliOperationOutcome.Rejected)
        } else {
            fail("ask_not_found", "The question does not exist on the target Agent.", CliOperationOutcome.Rejected)
        }
    } catch (_: SessionNotFound) {
        return fail("target_not_found", "The target does not exist.", CliOperationOutcome.Rejected)
    } catch (_: AgentNotFound) {
        return fail("target_not_found", "The target Agent does not exist.", CliOperationOutcome.Rejected)
    } catch (_: SessionHasRunningSubagents) {
        return fail("session_has_running_subagents", "The Session has running subagents and was not deleted.", CliOperationOutcome.Rejected)
    } catch (_: AgentNotActive) {
        return fail("agent_not_active", "The target Agent is no longer active.", CliOperationOutcome.Rejected)
    } catch (_: AgentCompleted) {
        return fail("agent_completed", "The completed Agent does not accept new work.", CliOperationOutcome.Rejected)
    } catch (_: SubagentCompletionNoticeFailed) {
        phase = "notify"
        return fail("completion_notice_failed", "Completion was recorded, but notifying the parent failed.", CliOperationOutcome.Failed)
    } catch (_: SessionNotActive) {
        return fail("agent_not_active", "The target Agent is no longer active.", CliOperationOutcome.Rejected)
    } catch (failure: Throwable) {
        // kRPC on Native represents an untyped remote failure as a bare Throwable, not an Exception.
        if (failure is Error) throw failure
        currentCoroutineContext().ensureActive()
        return when {
            phase == "input" -> fail("invalid_input", "Input must be valid, nonempty UTF-8 in the required format.", CliOperationOutcome.Rejected)
            mutationStarted -> fail("result_unknown", "The selected backend did not confirm the operation. Do not replay it blindly.", CliOperationOutcome.Unknown)
            else -> fail("operation_failed", "The selected backend did not complete this operation.", CliOperationOutcome.Failed)
        }
    }
    write(CliToolJson.encodeToString(CliOperationEnvelope.serializer(), CliOperationEnvelope(ok = true, result = result)))
    return 0
}

private suspend fun renameSessionTitle(
    runtime: AgentRuntimeRpc, id: Int, name: String, onCompareAndSet: (Boolean) -> Unit,
) {
    fun conflict(): Nothing = throw OperationRejected("rename_conflict", "The Session title or history changed concurrently.")
    val nonce = runtime.getCacheNonce(id)
    suspend fun read(): KodexAgentSettings {
        val top = runtime.getLatestIndex(id)
        if (top < 0) throw OperationRejected("uninitialized_session", "The Session does not have initial settings.")
        return try {
            runtime.getLastCommitsIn(id, nonce, 0, top, AgentCommitType.SettingsTypes, limit = 1)
                .lastOrNull()?.commit as? KodexAgentSettings
                ?: throw OperationRejected("uninitialized_session", "The Session does not have initial settings.")
        } catch (_: CacheNonceMismatch) {
            conflict()
        }
    }
    var current = read()
    val originalTitle = current.name
    repeat(12) {
        if (current.name != originalTitle) conflict()
        if (current.name == name) return
        onCompareAndSet(true)
        if (runtime.compareAndSetSettings(id, current, current.copy(name = name))) return
        onCompareAndSet(false) // A false CAS attests no title write by this attempt.
        current = read()
    }
    conflict()
}

private fun CliPayloadInput<String>.readText(readStdin: () -> String): String =
    (when (this) {
        is CliPayloadInput.Inline -> value
        CliPayloadInput.Stdin -> readStdin()
    }).also { require(it.isNotEmpty()) }

private fun readInvocationSource(env: (String) -> String?): SessionSpawnSource? {
    val id = env(KodexAgentIdEnvironmentVariable)
    val owner = env(KodexInvocationIdEnvironmentVariable)
    if (id == null && owner == null) return null
    val index = id?.toIntOrNull()
    if (index == null || index < 0 || owner.isNullOrBlank()) {
        throw OperationRejected("invalid_source", "The calling Agent context could not be verified.")
    }
    return SessionSpawnSource(index, owner)
}

private suspend fun readAgentStatus(services: RpcServices, agentId: Int, now: () -> Instant): CliAgentStatusOutput {
    services.runtime.keepAgentAlive(agentId)
    val (latestIndex, settings, tokenMeta) = readAgentStatusSnapshot(services.runtime, agentId)
    val running = services.runtime.getRunningTurn(agentId)
    val modelInfo = services.global.getModels().firstOrNull { it.slug == settings.model }
    val contextWindow = modelInfo?.effectiveContextWindow()?.let { effectiveWindow ->
        if (tokenMeta != null) {
            val status = modelInfo.contextWindowTokenStatus(
                activeContextTokens = tokenMeta.totalTokens,
                configuredAutoCompactionTokenLimit = settings.autoCompactionTokenLimit,
            )
            val budgetLimit = status.autoCompactionTokenLimit ?: effectiveWindow
            val remaining = status.tokensUntilCompaction
            val percent = if (budgetLimit > 0L && remaining != null) {
                ((remaining * 100L) / budgetLimit).toInt().coerceIn(0, 100)
            } else null
            CliContextWindowSummary(effectiveWindow, tokenMeta.totalTokens, remaining, percent)
        } else {
            val autoLimit = modelInfo.resolvedAutoCompactionTokenLimit(settings.autoCompactionTokenLimit)
            val initialRemaining = if (autoLimit != null) minOf(autoLimit, effectiveWindow) else effectiveWindow
            CliContextWindowSummary(effectiveWindow, remainingTokens = initialRemaining, remainingPercent = 100)
        }
    }
    return CliAgentStatusOutput(
        agentId, settings.name, settings.cwd.toString(), settings.model, settings.reasoning.effort,
        settings.serviceTier, settings.requestUserInputMode, running, latestIndex, now().toString(), contextWindow,
    )
}

private fun <T> CliPayloadInput<T>.resolve(
    serializer: KSerializer<T>,
    readStdin: () -> String,
): T = when (this) {
    is CliPayloadInput.Inline -> value
    is CliPayloadInput.Stdin -> CliToolJson.decodeFromString(serializer, readStdin())
}

private suspend fun setMcpServerEnabled(
    services: RpcServices,
    serverName: String,
    enabled: Boolean,
): McpManagedServerState {
    while (true) {
        val current = services.global.getSettings()
        val existing = current.mcpServers[serverName]
            ?: error("MCP server '$serverName' is not configured.")
        if (existing.enabled != enabled) {
            val updatedConfig = when (existing) {
                is McpServerConfiguration.StreamableHttp -> existing.copy(enabled = enabled)
                is McpServerConfiguration.Stdio -> existing.copy(enabled = enabled)
            }
            val updated = current.copy(mcpServers = current.mcpServers + (serverName to updatedConfig))
            if (!services.global.compareAndSetSettings(current, updated)) {
                delay(25)
                continue
            }
        }
        return services.global.getMcpServers().firstOrNull { it.serverName == serverName }
            ?: error("MCP server '$serverName' is not configured.")
    }
}

private suspend fun <T> withConsistentStorageSnapshot(
    runtime: AgentRuntimeRpc,
    sessionIndex: Int,
    block: suspend (cacheNonce: Long, latestIndex: Int) -> T,
): T {
    while (true) {
        val nonce = runtime.getCacheNonce(sessionIndex)
        val latestIndex = runtime.getLatestIndex(sessionIndex)
        try {
            return block(nonce, latestIndex)
        } catch (_: CacheNonceMismatch) {
            delay(10)
        }
    }
}

internal suspend fun <R> withTerminalRegistration(action: suspend () -> R): R = try {
    action()
} catch (_: ShellSessionNotRegistered) {
    error("Shell session is no longer registered.")
}

private suspend fun readAskUsers(
    runtime: AgentRuntimeRpc,
    sessionIndex: Int,
): List<AskUserEntry> {
    val commit = readLatestCommitOrNull(runtime, sessionIndex, AgentCommitType.AskUserTypes)?.commit as? AskUserSnapshot
    return commit?.entries ?: emptyList()
}

private suspend fun readAgentStatusSnapshot(
    runtime: AgentRuntimeRpc,
    sessionIndex: Int,
): Triple<Int, KodexAgentSettings, LlmRequestMeta?> =
    withConsistentStorageSnapshot(runtime, sessionIndex) { nonce, latestIndex ->
        if (latestIndex < 0) error("Agent $sessionIndex storage is not initialized.")
        val settingsCommit = runtime.getLastCommitsIn(
            sessionIndex = sessionIndex,
            cacheNonce = nonce,
            fromInclusive = 0,
            toInclusive = latestIndex,
            types = AgentCommitType.SettingsTypes,
            limit = 1,
        ).lastOrNull()?.commit as? KodexAgentSettings
            ?: error("Agent $sessionIndex has no committed settings.")
        val tokenMeta = runtime.getLastCommitsIn(
            sessionIndex = sessionIndex,
            cacheNonce = nonce,
            fromInclusive = 0,
            toInclusive = latestIndex,
            types = AgentCommitType.LlmRequestMetaTypes,
            limit = 1,
        ).lastOrNull()?.commit as? LlmRequestMeta
        Triple(latestIndex, settingsCommit, tokenMeta)
    }

private suspend fun readLatestCommitOrNull(
    runtime: AgentRuntimeRpc,
    sessionIndex: Int,
    types: Set<AgentCommitType>,
): IndexedAgentCommit? =
    withConsistentStorageSnapshot(runtime, sessionIndex) { nonce, latestIndex ->
        if (latestIndex < 0) null
        else runtime.getLastCommitsIn(
            sessionIndex = sessionIndex,
            cacheNonce = nonce,
            fromInclusive = 0,
            toInclusive = latestIndex,
            types = types,
            limit = 1,
        ).lastOrNull()
    }
