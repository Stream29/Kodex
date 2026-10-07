package io.github.stream29.kodex.mcp.impl

import io.github.stream29.kodex.mcp.contract.toConfiguration
import io.github.stream29.kodex.mcp.contract.validatedName
import io.github.stream29.kodex.mcp.contract.requireUniqueMcpModelNames

import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpCodexImportSource
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportItem
import io.github.stream29.kodex.mcp.contract.McpImportItemKind
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.mcp.contract.McpManager
import io.github.stream29.kodex.mcp.contract.McpManagerEffect
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthDraft
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttempt
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttemptFactory
import io.github.stream29.kodex.mcp.contract.McpOAuthSummary
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpSecretDraft
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpStdioDraft
import io.github.stream29.kodex.mcp.contract.McpStreamableHttpDraft
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Default application-wide [McpManager] implementation. */
public class McpManagerImpl internal constructor(
    scope: CoroutineScope,
    private val store: McpConfigurationStore,
    private val service: McpService,
    private val codexImportSource: McpCodexImportSource,
    private val loginAttemptFactory: McpOAuthLoginAttemptFactory,
) : McpManager {
    private val scope = scope.supervisorChildScope()
    private val commandMutex = Mutex()
    private val effectChannel = Channel<McpManagerEffect>(Channel.BUFFERED)
    private val authenticationOverrides =
        MutableStateFlow<Map<String, McpAuthenticationState>>(emptyMap())
    private val mutableServers = MutableStateFlow<List<McpManagedServerState>>(emptyList())
    private var nextPreviewId: Long = 1
    private var activePreview: RawImportPreview? = null
    private val activeLoginAttempts = mutableMapOf<String, ActiveLogin>()
    private var closed = false

    override val servers: StateFlow<List<McpManagedServerState>> = mutableServers.asStateFlow()
    override val effects: Flow<McpManagerEffect> = effectChannel.receiveAsFlow()

    init {
        this.scope.launch {
            combine(
                store.configurations,
                service.clients,
                service.authentication,
                authenticationOverrides,
            ) { configurations, clients, runtimeAuthentication, overrides ->
                ManagerSnapshot(
                    configurations = configurations,
                    clients = clients,
                    authentication = runtimeAuthentication + overrides,
                )
            }.collectLatest { snapshot ->
                publish(snapshot)
                coroutineScope {
                    snapshot.clients.values.forEach { client ->
                        launch {
                            client.state.collect {
                                publish(
                                    snapshot.copy(
                                        configurations = store.configurations.value,
                                        clients = service.clients.value,
                                        authentication = service.authentication.value +
                                            authenticationOverrides.value,
                                    ),
                                )
                            }
                        }
                    }
                    awaitCancellation()
                }
            }
        }
    }

    override suspend fun add(draft: McpServerDraft) {
        command {
            val name = draft.validatedName()
            require(name !in store.configurations.value) {
                "An MCP server named '$name' already exists."
            }
            val configuration = draft.toConfiguration(existing = null, preserveOAuth = false)
            store.update { current ->
                require(name !in current) { "An MCP server named '$name' already exists." }
                (current + (name to configuration)).also { requireUniqueMcpModelNames(it.keys, "server") }
            }
        }
    }

    override suspend fun edit(existingServerName: String, draft: McpServerDraft) {
        command {
            require(existingServerName.isNotBlank()) { "An MCP server name must not be blank." }
            val existing = store.configurations.value[existingServerName]
                ?: throw IllegalArgumentException("MCP server '$existingServerName' does not exist.")
            val nextName = draft.validatedName()
            val renamed = nextName != existingServerName
            require(!renamed || nextName !in store.configurations.value) {
                "An MCP server named '$nextName' already exists."
            }
            draft.toConfiguration(
                existing = existing,
                preserveOAuth = !renamed,
            )
            store.update { current ->
                val latest = current[existingServerName]
                    ?: throw IllegalArgumentException(
                        "MCP server '$existingServerName' does not exist.",
                    )
                require(!renamed || nextName !in current) {
                    "An MCP server named '$nextName' already exists."
                }
                val resolved = draft.toConfiguration(
                    existing = latest,
                    preserveOAuth = !renamed,
                )
                ((current - existingServerName) + (nextName to resolved))
                    .also { requireUniqueMcpModelNames(it.keys, "server") }
            }
            authenticationOverrides.value -= existingServerName
        }
    }

    override suspend fun delete(serverName: String) {
        command {
            activeLoginAttempts[serverName]?.cancel()
            store.update { current ->
                require(serverName in current) { "MCP server '$serverName' does not exist." }
                current - serverName
            }
            authenticationOverrides.value -= serverName
        }
    }

    override suspend fun setEnabled(serverName: String, enabled: Boolean) {
        command {
            store.update { current ->
                val configuration = current[serverName]
                    ?: throw IllegalArgumentException("MCP server '$serverName' does not exist.")
                current + (serverName to configuration.withEnabled(enabled))
            }
        }
    }

    override suspend fun login(serverName: String) {
        loginWithPreparedCallback(serverName) { attempt ->
            effectChannel.send(McpManagerEffect.OpenAuthorizationUrl(serverName, attempt.authorizationUrl))
        }
    }

    /** Backend callback instead of broadcasting an authorization URL as a UI effect. */
    public suspend fun loginWithPreparedCallback(
        serverName: String,
        expectedRedirectUri: String? = null,
        onPrepared: suspend (McpOAuthLoginAttempt) -> Unit,
    ) {
        val operation = command {
            require(serverName !in activeLoginAttempts) {
                "MCP server '$serverName' is already authorizing."
            }
            val configuration = store.configurations.value[serverName]
                as? McpServerConfiguration.StreamableHttp
                ?: throw IllegalArgumentException(
                    "MCP server '$serverName' does not support browser OAuth.",
                )
            val oauth = configuration.oauth
                ?: throw IllegalArgumentException(
                    "MCP server '$serverName' has no OAuth configuration.",
                )
            require(expectedRedirectUri == null || oauth.client.redirectUri == expectedRedirectUri) {
                "The callback address does not match the MCP configuration."
            }
            val uninitialized = oauth.toUninitialized()
            val loginConfiguration = configuration.copy(oauth = uninitialized)
            authenticationOverrides.value +=
                serverName to McpAuthenticationState.Authorizing
            val attempt = try {
                loginAttemptFactory.create(loginConfiguration)
            } catch (failure: Throwable) {
                authenticationOverrides.value +=
                    serverName to McpAuthenticationState.Failed("Authorization could not start.")
                throw failure
            }
            val prepared = attempt.preparedConfiguration
            try {
                if (prepared != uninitialized) {
                    store.update { current ->
                        val latest = current[serverName]
                            as? McpServerConfiguration.StreamableHttp
                            ?: throw IllegalStateException(
                                "MCP server '$serverName' changed during authorization.",
                            )
                        require(latest.url == configuration.url &&
                            latest.oauth?.loginIdentity() == uninitialized.loginIdentity()) {
                            "MCP server '$serverName' changed during authorization."
                        }
                        current + (serverName to latest.copy(oauth = prepared))
                    }
                }
            } catch (failure: Throwable) {
                attempt.close()
                authenticationOverrides.value +=
                    serverName to McpAuthenticationState.Failed("Authorization could not start.")
                throw failure
            }
            activeLoginAttempts[serverName] = ActiveLogin(attempt, currentCoroutineContext().job)
            LoginOperation(configuration.url, prepared, attempt)
        }
        try {
            onPrepared(operation.attempt)
            val initialized = operation.attempt.awaitInitialized()
            currentCoroutineContext().ensureActive()
            command {
                store.update { current ->
                    val latest = current[serverName]
                        as? McpServerConfiguration.StreamableHttp
                        ?: throw IllegalStateException(
                            "MCP server '$serverName' changed during authorization.",
                        )
                    require(
                        latest.url == operation.serverUrl && latest.oauth?.loginIdentity() ==
                            operation.uninitialized.loginIdentity(),
                    ) {
                        "MCP server '$serverName' changed during authorization."
                    }
                    current + (serverName to latest.copy(oauth = initialized))
                }
                authenticationOverrides.value -= serverName
            }
        } catch (cancellation: CancellationException) {
            authenticationOverrides.value -= serverName
            throw cancellation
        } catch (failure: Throwable) {
            authenticationOverrides.value +=
                serverName to McpAuthenticationState.Failed("Authorization failed.")
            throw failure
        } finally {
            withContext(NonCancellable) {
                operation.attempt.close()
                commandMutex.withLock {
                    if (activeLoginAttempts[serverName]?.attempt === operation.attempt) {
                        activeLoginAttempts.remove(serverName)
                    }
                }
            }
        }
    }

    override suspend fun cancelLogin(serverName: String) {
        val attempt = command {
            activeLoginAttempts[serverName]
                ?: return@command null
        }
        attempt?.cancel()
    }

    /*
     * Keep logout independent from browser-attempt cancellation: the caller
     * explicitly cancels first when dismissing a pending authorization.
     */
    override suspend fun logout(serverName: String) {
        command {
            require(serverName !in activeLoginAttempts) {
                "MCP server '$serverName' is currently authorizing."
            }
            store.update { current ->
                val latest = current[serverName]
                    as? McpServerConfiguration.StreamableHttp
                    ?: throw IllegalArgumentException(
                        "MCP server '$serverName' does not support OAuth.",
                    )
                val oauth = latest.oauth
                    ?: throw IllegalArgumentException(
                        "MCP server '$serverName' has no OAuth configuration.",
                    )
                current + (serverName to latest.copy(oauth = oauth.toUninitialized()))
            }
            authenticationOverrides.value -= serverName
        }
    }

    override suspend fun reconnect(serverName: String) {
        val client = service.clients.value[serverName]
            ?: throw IllegalArgumentException("MCP server '$serverName' is not connected.")
        client.reconnect()
    }

    /**
     * Coordinates a full-value settings commit with manager commands.
     * [commit] must compare/persist at the actual store's write boundary; this
     * command lock is not a replacement for file CAS or token-refresh locking.
     * No Replace intent, invalidate or reconnect is inferred from equal values.
     */
    public suspend fun commitConfigurationChange(
        expect: Map<String, McpServerConfiguration>,
        update: Map<String, McpServerConfiguration>,
        commit: suspend () -> Boolean,
    ): Boolean = command {
        if (!commit()) return@command false
        val changedNames = (expect.keys + update.keys).filter { expect[it] != update[it] }
        changedNames.filter { it !in update }.forEach { activeLoginAttempts[it]?.cancel() }
        authenticationOverrides.value -= changedNames.toSet()
        true
    }

    override suspend fun previewCodexImport(filter: String): McpImportPreview =
        command {
            val imported = codexImportSource.read()
                .sortedBy(McpCodexImportCandidate::serverName)
            require(imported.map(McpCodexImportCandidate::serverName).distinct().size ==
                imported.size) {
                "Codex MCP import candidates must have unique server names."
            }
            val normalizedFilter = filter.trim()
            val filtered = imported.filter { candidate ->
                normalizedFilter.isEmpty() ||
                    candidate.serverName.contains(normalizedFilter, ignoreCase = true)
            }
            check(nextPreviewId < Long.MAX_VALUE) { "MCP import preview ids are exhausted." }
            val id = nextPreviewId++
            val existingNames = store.configurations.value.keys
            val items = filtered.map { candidate ->
                when {
                    candidate.serverName.isBlank() -> {
                        McpImportItem(
                            serverName = candidate.serverName,
                            transport = candidate.transport,
                            kind = McpImportItemKind.Unsupported,
                            enabled = (candidate as? McpCodexImportCandidate.Supported)
                                ?.configuration
                                ?.enabled,
                            selectable = false,
                            detail = "The server name is blank.",
                        )
                    }

                    candidate is McpCodexImportCandidate.Unsupported -> {
                        McpImportItem(
                            serverName = candidate.serverName,
                            transport = candidate.transport,
                            kind = McpImportItemKind.Unsupported,
                            enabled = null,
                            selectable = false,
                            detail = candidate.detail,
                        )
                    }

                    else -> {
                        val supported = candidate as McpCodexImportCandidate.Supported
                        McpImportItem(
                            serverName = supported.serverName,
                            transport = supported.transport,
                            kind = if (supported.serverName in existingNames) {
                                McpImportItemKind.Conflict
                            } else {
                                McpImportItemKind.New
                            },
                            enabled = supported.configuration.enabled,
                            selectable = true,
                        )
                    }
                }
            }
            activePreview = RawImportPreview(
                id = id,
                configurations = filtered
                    .filterIsInstance<McpCodexImportCandidate.Supported>()
                    .filter { candidate -> candidate.serverName.isNotBlank() }
                    .associate { candidate ->
                        candidate.serverName to candidate.configuration
                    },
            )
            McpImportPreview(
                id = id,
                filter = normalizedFilter,
                items = items,
            )
        }

    override suspend fun applyCodexImport(
        previewId: Long,
        decisions: Map<String, McpImportDecision>,
    ) {
        command {
            val preview = activePreview?.takeIf { it.id == previewId }
                ?: throw IllegalArgumentException("MCP import preview $previewId is no longer active.")
            require(decisions.keys.all(preview.configurations::containsKey)) {
                "MCP import decisions contain an item outside the active preview."
            }
            val replacedServerNames = decisions
                .filterValues { decision -> decision == McpImportDecision.Replace }
                .keys
            store.update { current ->
                val updated = current.toMutableMap()
                preview.configurations.forEach { (name, configuration) ->
                    when (decisions[name] ?: McpImportDecision.Skip) {
                        McpImportDecision.Skip -> Unit
                        McpImportDecision.Import -> {
                            require(name !in updated) {
                                "MCP server '$name' now conflicts with existing settings."
                            }
                            updated[name] = configuration.withoutOAuthCredentials()
                        }

                        McpImportDecision.Replace -> {
                            require(name in updated) {
                                "MCP server '$name' is no longer a replaceable conflict."
                            }
                            updated[name] = configuration.withoutOAuthCredentials()
                        }
                    }
                }
                updated.toMap().also { requireUniqueMcpModelNames(it.keys, "server") }
            }
            authenticationOverrides.value -= decisions
                .filterValues { decision -> decision != McpImportDecision.Skip }
                .keys
            activePreview = null
            replacedServerNames.forEach { serverName ->
                scope.launch {
                    service.invalidate(serverName)
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        activeLoginAttempts.values.forEach { attempt -> attempt.cancel() }
        activeLoginAttempts.clear()
        effectChannel.close()
        scope.cancel()
    }

    private suspend fun <T> command(block: suspend () -> T): T {
        check(!closed) { "The MCP manager is closed." }
        return commandMutex.withLock { block() }
    }

    private fun publish(snapshot: ManagerSnapshot) {
        mutableServers.value = snapshot.configurations
            .entries
            .sortedBy(Map.Entry<String, McpServerConfiguration>::key)
            .map { (name, configuration) ->
                val client = snapshot.clients[name]
                McpManagedServerState(
                    serverName = name,
                    transport = configuration.transportKind(),
                    enabled = configuration.enabled,
                    authentication = snapshot.authentication[name]
                        ?: configuration.persistedAuthenticationState(),
                    connection = client?.state?.value,
                    toolCount = client?.listTools()?.size ?: 0,
                    headerNames = (
                        configuration as? McpServerConfiguration.StreamableHttp
                        )?.headers?.keys?.sorted().orEmpty(),
                    environmentNames = (
                        configuration as? McpServerConfiguration.Stdio
                        )?.environment?.keys?.sorted().orEmpty(),
                    oauth = (
                        configuration as? McpServerConfiguration.StreamableHttp
                        )?.oauth?.toSummary(),
                    streamableHttpUrl = (
                        configuration as? McpServerConfiguration.StreamableHttp
                        )?.url,
                    stdioCommand = (
                        configuration as? McpServerConfiguration.Stdio
                        )?.command,
                    stdioArguments = (
                        configuration as? McpServerConfiguration.Stdio
                        )?.args.orEmpty(),
                    stdioWorkingDirectory = (
                        configuration as? McpServerConfiguration.Stdio
                        )?.workingDirectory,
                )
            }
    }
}

/** Creates an independently owned manager under this scope. */
public fun CoroutineScope.McpManagerImpl(
    store: McpConfigurationStore,
    service: McpService,
    codexImportSource: McpCodexImportSource,
    loginAttemptFactory: McpOAuthLoginAttemptFactory,
): McpManagerImpl =
    McpManagerImpl(
        scope = this,
        store = store,
        service = service,
        codexImportSource = codexImportSource,
        loginAttemptFactory = loginAttemptFactory,
    )

private data class ManagerSnapshot(
    val configurations: Map<String, McpServerConfiguration>,
    val clients: Map<String, McpClient>,
    val authentication: Map<String, McpAuthenticationState>,
)

private data class RawImportPreview(
    val id: Long,
    val configurations: Map<String, McpServerConfiguration>,
)

private data class LoginOperation(
    val serverUrl: String,
    val uninitialized: McpOAuthConfiguration.Uninitialized,
    val attempt: McpOAuthLoginAttempt,
)

private class ActiveLogin(val attempt: McpOAuthLoginAttempt, val job: Job) {
    fun cancel() {
        job.cancel()
        attempt.close()
    }
}

private fun McpOAuthConfiguration.toUninitialized(): McpOAuthConfiguration.Uninitialized =
    McpOAuthConfiguration.Uninitialized(
        client = client,
        resource = resource,
        scopes = scopes,
    )

private data class OAuthLoginIdentity(
    val clientId: String?,
    val clientSecret: McpSecret?,
    val redirectUri: String,
    val authorizationEndpoint: String?,
    val tokenEndpoint: String?,
    val resource: String?,
    val scopes: List<String>,
)

private fun McpOAuthConfiguration.loginIdentity(): OAuthLoginIdentity =
    OAuthLoginIdentity(
        clientId = client.clientId,
        clientSecret = client.clientSecret,
        redirectUri = client.redirectUri,
        authorizationEndpoint = client.authorizationEndpoint,
        tokenEndpoint = client.tokenEndpoint,
        resource = resource,
        scopes = scopes,
    )

private fun McpServerConfiguration.withEnabled(enabled: Boolean): McpServerConfiguration =
    when (this) {
        is McpServerConfiguration.StreamableHttp -> copy(enabled = enabled)
        is McpServerConfiguration.Stdio -> copy(enabled = enabled)
    }

private fun McpServerConfiguration.withoutOAuthCredentials(): McpServerConfiguration =
    when (this) {
        is McpServerConfiguration.StreamableHttp -> copy(
            oauth = oauth?.toUninitialized(),
        )

        is McpServerConfiguration.Stdio -> this
    }

private fun McpServerConfiguration.transportKind(): McpTransportKind =
    when (this) {
        is McpServerConfiguration.StreamableHttp -> McpTransportKind.StreamableHttp
        is McpServerConfiguration.Stdio -> McpTransportKind.Stdio
    }

private fun McpServerConfiguration.persistedAuthenticationState(): McpAuthenticationState =
    when (this) {
        is McpServerConfiguration.Stdio -> McpAuthenticationState.NotConfigured
        is McpServerConfiguration.StreamableHttp -> when (oauth) {
            null -> McpAuthenticationState.NotConfigured
            is McpOAuthConfiguration.Uninitialized -> McpAuthenticationState.LoginRequired
            is McpOAuthConfiguration.Initialized -> McpAuthenticationState.Authorized
        }
    }

private fun McpOAuthConfiguration.toSummary(): McpOAuthSummary =
    McpOAuthSummary(
        clientId = client.clientId,
        hasClientSecret = client.clientSecret != null,
        redirectUri = client.redirectUri,
        authorizationEndpoint = client.authorizationEndpoint,
        tokenEndpoint = client.tokenEndpoint,
        resource = resource,
        scopes = scopes,
    )
