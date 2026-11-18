@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.agentschedule.contract.ScheduleRepository
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.mcp.contract.McpCatalogTool
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Complete frozen GlobalRpc, delegating to the same owned backend graph. */
internal class BackendGlobalRpc(
    private val scope: CoroutineScope,
    private val settings: BackendGlobalState,
    private val account: BackendAccountState,
    private val effects: BackendFrontendEffects,
    private val sessions: BackendSessionManagement,
    private val schedules: ScheduleRepository? = null,
    private val home: Path? = null,
    private val checkAdmission: () -> Unit = {},
) : GlobalRpc {
    private val operations: CoroutineScope = scope.supervisorChildScope()
    private val paths by lazy {
        val roots = settings.contextSettings.value
        BackendPathProvider(
            SystemCoroutineFileSystem,
            userHomeDirectory(),
            roots.agentsHome,
            roots.kodexHome,
            roots.codexHome,
        )
    }

    private fun ensureAdmission() {
        scope.ensureActive()
        checkAdmission()
    }

    private inline fun <T> observation(read: () -> T): T {
        ensureAdmission()
        return read()
    }

    private suspend fun <T> inBackend(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        ensureAdmission()
        return operations.async {
            ensureAdmission()
            block()
        }.await()
    }

    override suspend fun validateDirectory(query: DirectoryQuery): DirectoryValidationResult {
        ensureAdmission()
        return paths.validateDirectory(query)
    }

    override suspend fun listDirectories(query: DirectoryQuery): DirectoryListResult {
        ensureAdmission()
        return paths.listDirectories(query)
    }

    override suspend fun evaluateContextPaths(query: ContextPathQuery): ContextPathEvaluation {
        ensureAdmission()
        return paths.evaluateContextPaths(query)
    }

    override suspend fun getSettings(): BackendSettings = observation { settings.settings.value }
    override suspend fun getFrontendSettings(): CliFrontendSettings = observation { settings.unified.current.frontend }
    override fun getFrontendSettingsFlow(): Flow<CliFrontendSettings> = observation { settings.unified.frontendSettings }
    override suspend fun compareAndSetFrontendSettings(
        expect: CliFrontendSettings,
        update: CliFrontendSettings,
    ): Boolean = inBackend { settings.unified.compareAndSetFrontend(expect, update) }

    override fun getSettingsFlow(): Flow<BackendSettings> = observation { settings.settings }
    override suspend fun compareAndSetSettings(expect: BackendSettings, update: BackendSettings): Boolean =
        inBackend { settings.compareAndSetSettings(expect, update) }

    override suspend fun getModels(): List<ModelInfo> = observation { settings.models.value }
    override fun getModelsFlow(): Flow<List<ModelInfo>> = observation { settings.models }
    override suspend fun getAuthentication(): SettingsAuthenticationState = observation { account.authentication.value }
    override fun getAuthenticationFlow(): Flow<SettingsAuthenticationState> = observation { account.authentication }
    override suspend fun getAccountUsage(): SettingsAccountUsageState = observation { account.accountUsage.value }
    override fun getAccountUsageFlow(): Flow<SettingsAccountUsageState> = observation { account.accountUsage }
    override suspend fun refreshAccountUsage(): Unit = inBackend { account.refreshAccountUsage() }
    override suspend fun consumeUsageReset(creditId: String): CodexRateLimitResetOutcome =
        inBackend { account.consumeUsageReset(creditId) }

    override fun startOAuthLogin(target: OAuthTarget): Flow<OAuthLoginEvent> = flow {
        ensureAdmission()
        emitAll(effects.startOAuth(target))
    }

    override suspend fun reportFrontendError(message: String?): FrontendErrorAdmission =
        inBackend { effects.reportFrontendError(message) }

    override suspend fun cancelOAuthLogin(attemptId: Long): Unit =
        inBackend { effects.cancelOAuth(attemptId) }

    override suspend fun removeAuthentication(source: KodexAuthSource): Unit =
        inBackend { effects.removeAuthentication(source) }

    override suspend fun getMcpServers(): List<McpManagedServerState> = observation { settings.mcpServers.value }
    override fun getMcpServersFlow(): Flow<List<McpManagedServerState>> = observation { settings.mcpServers }
    override suspend fun reconnectMcpServer(serverName: String): Unit =
        inBackend { settings.reconnectMcpServer(serverName) }

    override suspend fun logoutMcpServer(serverName: String): Unit =
        inBackend { effects.logoutMcp(serverName) }

    override suspend fun getCodexMcpSettings(): List<McpCodexImportCandidate> =
        inBackend { settings.getCodexMcpSettings() }

    override suspend fun getSessionCatalog(includeArchived: Boolean): List<SessionCatalogEntry> =
        sessions.getSessionCatalog(includeArchived)

    override suspend fun createSession(cwd: Path): Int = sessions.createSession(cwd)

    override suspend fun createSchedule(
        agentId: Int,
        triggerAt: Instant,
        cron: String?,
        description: String,
    ): ScheduleRecord = inBackend {
        val repository = checkNotNull(schedules) { "ScheduleRepository is not configured on BackendGlobalRpc." }
        sessions.ensureSessionExists(agentId)
        repository.create(
            agentId = agentId,
            triggerAt = triggerAt,
            cron = cron,
            description = description,
            createdAt = Clock.System.now(),
        )
    }

    override suspend fun listSchedules(agentId: Int): List<ScheduleRecord> = inBackend {
        val repository = checkNotNull(schedules) { "ScheduleRepository is not configured on BackendGlobalRpc." }
        repository.list(agentId)
    }

    override suspend fun deleteSchedule(scheduleId: Long): Boolean = inBackend {
        val repository = checkNotNull(schedules) { "ScheduleRepository is not configured on BackendGlobalRpc." }
        repository.delete(scheduleId)
    }

    override suspend fun listMcpTools(): List<McpCatalogTool> = observation {
        settings.mcpService.clients.value
            .entries
            .sortedBy { it.key }
            .flatMap { (_, client) ->
                client.listTools().map { tool ->
                    McpCatalogTool(
                        serverName = tool.serverName,
                        serverInstructions = tool.serverInstructions,
                        toolName = tool.toolName,
                        description = tool.description,
                        inputSchema = tool.inputSchema,
                        outputSchema = tool.outputSchema,
                    )
                }
            }
    }

    override suspend fun callMcpTool(
        serverName: String,
        toolName: String,
        arguments: JsonObject,
    ): CallToolResult = inBackend {
        val client = settings.mcpService.clients.value[serverName]
            ?: throw IllegalArgumentException("MCP server '$serverName' is not enabled or does not exist.")
        val tool = client.listTools().firstOrNull { it.toolName == toolName }
            ?: throw IllegalArgumentException("MCP server '$serverName' has no tool named '$toolName'.")
        val raw = tool.call(arguments)
        val mediaRoot = home ?: settings.contextSettings.value.kodexHome
        raw.materializeImageBlocks(mediaRoot = mediaRoot, serverName = serverName)
    }

    override suspend fun keepSessionAlive(sessionIndex: Int): Unit = sessions.keepSessionAlive(sessionIndex)
    override suspend fun archiveSession(sessionIndex: Int): Unit = sessions.archiveSession(sessionIndex)
    override suspend fun unarchiveSession(sessionIndex: Int): Unit = sessions.unarchiveSession(sessionIndex)
    override suspend fun forkSession(sessionIndex: Int): Int = sessions.forkSession(sessionIndex)
    override suspend fun forkSessionHistory(
        sessionIndex: Int,
        untilExclusive: Int,
        expectedCacheNonce: Long,
    ): Int = sessions.forkSessionHistory(sessionIndex, untilExclusive, expectedCacheNonce)

    override suspend fun deleteSession(sessionIndex: Int): Boolean = sessions.deleteSession(sessionIndex)
}

private suspend fun CallToolResult.materializeImageBlocks(
    mediaRoot: Path,
    serverName: String,
): CallToolResult {
    val rewritten = content.map { block ->
        block.materializeImageBlockIfPresent(mediaRoot = mediaRoot, serverName = serverName)
    }
    return if (rewritten == content) this else copy(content = rewritten)
}

private suspend fun JsonElement.materializeImageBlockIfPresent(
    mediaRoot: Path,
    serverName: String,
): JsonElement {
    val obj = this as? JsonObject ?: return this
    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
    if (type != "image") return this
    val rawData = (obj["data"] as? JsonPrimitive)?.contentOrNull ?: return this
    val payload = if (rawData.startsWith("data:", ignoreCase = true)) {
        rawData.substringAfter(',', "")
    } else {
        rawData
    }
    val bytes = runCatching { Base64.Mime.decode(payload.trim()) }.getOrNull() ?: return this
    val declaredMime = (obj["mimeType"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    val resolvedMime = declaredMime ?: sniffImageMimeType(bytes)
    val ext = when (resolvedMime?.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "bin"
    }
    val directory = Path(mediaRoot, "media", "mcp", serverName.toSafeSegment())
    SystemCoroutineFileSystem.createDirectories(directory)
    val savedPath = Path(directory, "${Uuid.generateV7()}.$ext")
    SystemCoroutineFileSystem.writeBytes(savedPath, bytes)
    return buildJsonObject {
        put("type", JsonPrimitive("image_file"))
        put("path", JsonPrimitive(savedPath.toString()))
        if (resolvedMime != null) {
            put("mimeType", JsonPrimitive(resolvedMime))
        }
        obj["annotations"]?.let { put("annotations", it) }
        obj["_meta"]?.let { put("_meta", it) }
    }
}

private fun sniffImageMimeType(bytes: ByteArray): String? = when {
    bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte() -> "image/png"

    bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte() -> "image/jpeg"

    bytes.size >= 6 &&
        bytes[0] == 0x47.toByte() &&
        bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() &&
        bytes[3] == 0x38.toByte() -> "image/gif"

    bytes.size >= 12 &&
        bytes[0] == 0x52.toByte() &&
        bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() &&
        bytes[3] == 0x46.toByte() &&
        bytes[8] == 0x57.toByte() &&
        bytes[9] == 0x45.toByte() &&
        bytes[10] == 0x42.toByte() &&
        bytes[11] == 0x50.toByte() -> "image/webp"

    else -> null
}

private fun String.toSafeSegment(): String =
    map { ch ->
        if (ch.isLetterOrDigit() && ch.code < 128 || ch == '-' || ch == '_') ch else '_'
    }.joinToString("").ifEmpty { "mcp" }
