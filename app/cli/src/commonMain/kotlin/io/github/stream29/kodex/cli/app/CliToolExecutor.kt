@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.nextCronTriggerAt
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.backend.host.connectBackend
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.mcp.contract.McpCatalogTool
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.ReservedMcpServerNames
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.contextWindowTokenStatus
import io.github.stream29.kodex.openai.effectiveContextWindow
import io.github.stream29.kodex.openai.resolvedAutoCompactionTokenLimit
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.rpc.models.GeneratedImageArtifact
import io.github.stream29.kodex.rpc.models.ImageGenToolArguments
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.unifiedexec.KodexAgentIdEnvironmentVariable
import io.github.stream29.kodex.tool.unifiedexec.KodexHomeEnvironmentVariable
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

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

internal suspend fun resolveToolBackendOrNull(
    target: CliBackendTarget?,
    defaultHome: Path,
    env: (String) -> String? = ::environmentVariable,
    discover: suspend (Path) -> BackendEndpoint? = BackendInstance::discover,
    connect: suspend (Path, Int) -> BackendEndpoint = ::connectBackend,
): Pair<Path, BackendEndpoint?> {
    val rawHome = target?.backendHome ?: env(KodexHomeEnvironmentVariable)?.takeIf { it.isNotBlank() }
    val resolvedHome = resolveExistingOrCandidatePath(if (rawHome == null) defaultHome else Path(rawHome))
    val port = target?.port
    val endpoint = if (port != null) connect(resolvedHome, port) else discover(resolvedHome)
    return resolvedHome to endpoint
}

internal suspend fun resolveToolBackend(
    target: CliBackendTarget?,
    defaultHome: Path,
    env: (String) -> String? = ::environmentVariable,
    discover: suspend (Path) -> BackendEndpoint? = BackendInstance::discover,
    connect: suspend (Path, Int) -> BackendEndpoint = ::connectBackend,
): Pair<Path, BackendEndpoint> {
    val (resolvedHome, endpoint) = resolveToolBackendOrNull(target, defaultHome, env, discover, connect)
    return resolvedHome to (
        endpoint ?: error(
            "No running Kodex backend found in $resolvedHome. Start 'kodex backend start' or open a Kodex frontend first.",
        )
    )
}

/**
 * Holds at most one open RPC connection across dynamic MCP domain discovery and subsequent leaf
 * command execution within a single CLI invocation, closing it deterministically on exit.
 */
internal class ReusableToolRpcSession(
    private val scope: CoroutineScope,
    private val defaultHome: Path,
    private val env: (String) -> String? = ::environmentVariable,
    private val discover: suspend (Path) -> BackendEndpoint? = BackendInstance::discover,
    private val connect: suspend (Path, Int) -> BackendEndpoint = ::connectBackend,
    private val openServices: suspend (Path, BackendEndpoint, suspend (RpcServices) -> Unit) -> Unit =
        { home, endpoint, use ->
            withLocalRpcClient(home, endpoint) { connection ->
                use(RpcServices(RestoringRpcClient(connection)))
            }
        },
) {
    private class ActiveConnection(
        val target: CliBackendTarget?,
        val services: Deferred<RpcServices>,
        val release: CompletableDeferred<Unit>,
        val job: Job,
    )

    private var active: ActiveConnection? = null

    suspend fun discoverMcpDomains(target: CliBackendTarget?): List<McpServerDomainCommand> = try {
        val services = servicesOrNull(target) ?: return emptyList()
        loadDynamicMcpDomains(services)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun servicesOrNull(target: CliBackendTarget?): RpcServices? {
        existingServices(target)?.let { return it }
        val (resolvedHome, endpoint) = resolveToolBackendOrNull(target, defaultHome, env, discover, connect)
        return if (endpoint == null) null else attach(target, resolvedHome, endpoint)
    }

    suspend fun requireServices(target: CliBackendTarget?): RpcServices {
        existingServices(target)?.let { return it }
        val (resolvedHome, endpoint) = resolveToolBackend(target, defaultHome, env, discover, connect)
        return attach(target, resolvedHome, endpoint)
    }

    private suspend fun existingServices(target: CliBackendTarget?): RpcServices? {
        val current = active ?: return null
        if (current.target == target && current.job.isActive) return current.services.await()
        close()
        return null
    }

    private suspend fun attach(
        target: CliBackendTarget?,
        resolvedHome: Path,
        endpoint: BackendEndpoint,
    ): RpcServices {
        val ready = CompletableDeferred<RpcServices>()
        val release = CompletableDeferred<Unit>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                openServices(resolvedHome, endpoint) { services ->
                    ready.complete(services)
                    release.await()
                }
            } catch (failure: Throwable) {
                val delivered = !ready.completeExceptionally(failure)
                if (delivered && failure !is CancellationException) throw failure
            }
        }
        active = ActiveConnection(target, ready, release, job)
        return try {
            ready.await()
        } catch (failure: Throwable) {
            active = null
            release.complete(Unit)
            withContext(NonCancellable) { job.cancelAndJoin() }
            throw failure
        }
    }

    suspend fun close() {
        val current = active ?: return
        active = null
        current.release.complete(Unit)
        withContext(NonCancellable) { current.job.join() }
    }
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
    readStdin: () -> String = ::readCliStdinText,
    writeStdout: (String) -> Unit = ::println,
    now: () -> Instant = Clock.System::now,
): Int {
    when (command) {
        is TerminalListCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            services.global.keepSessionAlive(agentId)
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
            services.global.keepSessionAlive(agentId)
            val slice = services.runtime.readShellSessionOutput(
                sessionIndex = agentId,
                shellSessionId = command.sessionId,
                offset = command.offset,
                limit = command.limit,
            )
            writeStdout(CliToolJson.encodeToString(StdoutSlice.serializer(), slice))
        }
        is TerminalWriteCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            val text = when {
                command.useStdin -> readStdin()
                command.chars != null -> command.chars!!
                else -> ""
            }
            services.global.keepSessionAlive(agentId)
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
            services.global.keepSessionAlive(agentId)
            services.runtime.closeShellSession(agentId, command.sessionId)
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
            services.global.keepSessionAlive(agentId)
            val created = services.runtime.createAskUser(agentId, args)
            writeStdout(CliToolJson.encodeToString(AskUserEntry.serializer(), created))
        }
        is AskListCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            services.global.keepSessionAlive(agentId)
            val entries = readAskUsers(services.runtime, agentId)
            writeStdout(CliToolJson.encodeToString(ListSerializer(AskUserEntry.serializer()), entries))
        }
        is AskGetCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            services.global.keepSessionAlive(agentId)
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
        is SessionStatusCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            services.global.keepSessionAlive(agentId)
            val (latestIndex, settings, tokenMeta) = readSessionStatusSnapshot(services.runtime, agentId)
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
                    } else {
                        null
                    }
                    CliContextWindowSummary(
                        effectiveWindowTokens = effectiveWindow,
                        usedTokens = tokenMeta.totalTokens,
                        remainingTokens = remaining,
                        remainingPercent = percent,
                    )
                } else {
                    val autoLimit = modelInfo.resolvedAutoCompactionTokenLimit(settings.autoCompactionTokenLimit)
                    val initialRemaining = if (autoLimit != null) minOf(autoLimit, effectiveWindow) else effectiveWindow
                    CliContextWindowSummary(
                        effectiveWindowTokens = effectiveWindow,
                        usedTokens = null,
                        remainingTokens = initialRemaining,
                        remainingPercent = 100,
                    )
                }
            }
            val statusOutput = CliSessionStatusOutput(
                agentId = agentId,
                threadName = settings.threadName,
                cwd = settings.cwd.toString(),
                model = settings.model,
                reasoningEffort = settings.reasoning.effort,
                serviceTier = settings.serviceTier,
                requestUserInputMode = settings.requestUserInputMode,
                running = running,
                latestIndex = latestIndex,
                now = now().toString(),
                contextWindow = contextWindow,
            )
            writeStdout(CliToolJson.encodeToString(CliSessionStatusOutput.serializer(), statusOutput))
        }
        is SearchCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            val commands = command.resolveCommands().resolve(SearchCommands.serializer(), readStdin)
            services.global.keepSessionAlive(agentId)
            val response = services.runtime.runWebSearch(agentId, commands)
            writeStdout(response.output)
        }
        is ImageCommand -> {
            val agentId = resolveTargetAgentId(command.agent, env)
            services.global.keepSessionAlive(agentId)
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

private suspend fun readAskUsers(
    runtime: AgentRuntimeRpc,
    sessionIndex: Int,
): List<AskUserEntry> {
    val commit = readLatestCommitOrNull(runtime, sessionIndex, AgentCommitType.AskUserTypes)?.commit as? AskUserSnapshot
    return commit?.entries ?: emptyList()
}

private suspend fun readSessionStatusSnapshot(
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
