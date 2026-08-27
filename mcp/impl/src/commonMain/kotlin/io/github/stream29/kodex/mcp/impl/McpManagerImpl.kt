package io.github.stream29.kodex.mcp.impl

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
    private val activeLoginAttempts = mutableMapOf<String, McpOAuthLoginAttempt>()
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
                current + (name to configuration)
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
                (current - existingServerName) + (nextName to resolved)
            }
            authenticationOverrides.value -= existingServerName
        }
    }

    override suspend fun delete(serverName: String) {
        command {
            activeLoginAttempts[serverName]?.close()
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
                        require(latest.oauth?.loginIdentity() == uninitialized.loginIdentity()) {
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
            activeLoginAttempts[serverName] = attempt
            LoginOperation(prepared, attempt)
        }
        try {
            effectChannel.send(
                McpManagerEffect.OpenAuthorizationUrl(
                    serverName = serverName,
                    url = operation.attempt.authorizationUrl,
                ),
            )
            val initialized = operation.attempt.awaitInitialized()
            command {
                store.update { current ->
                    val latest = current[serverName]
                        as? McpServerConfiguration.StreamableHttp
                        ?: throw IllegalStateException(
                            "MCP server '$serverName' changed during authorization.",
                        )
                    require(
                        latest.oauth?.loginIdentity() ==
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
            operation.attempt.close()
            commandMutex.withLock {
                if (activeLoginAttempts[serverName] === operation.attempt) {
                    activeLoginAttempts.remove(serverName)
                }
            }
        }
    }

    override suspend fun cancelLogin(serverName: String) {
        val attempt = command {
            activeLoginAttempts[serverName]
                ?: return@command null
        }
        attempt?.close()
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
                updated.toMap()
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
        activeLoginAttempts.values.forEach { attempt -> attempt.close() }
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
    val uninitialized: McpOAuthConfiguration.Uninitialized,
    val attempt: McpOAuthLoginAttempt,
)

private fun McpServerDraft.validatedName(): String =
    serverName.trim().also { name ->
        require(name.isNotEmpty()) { "An MCP server name must not be blank." }
    }

private fun McpServerDraft.toConfiguration(
    existing: McpServerConfiguration?,
    preserveOAuth: Boolean,
): McpServerConfiguration =
    when (this) {
        is McpServerDraft.StreamableHttp -> {
            val previous = existing as? McpServerConfiguration.StreamableHttp
            configuration.toConfiguration(
                enabled = enabled,
                existing = previous,
                preserveOAuth = preserveOAuth,
            )
        }

        is McpServerDraft.Stdio -> {
            val previous = existing as? McpServerConfiguration.Stdio
            configuration.toConfiguration(enabled, previous)
        }
    }

private fun McpStreamableHttpDraft.toConfiguration(
    enabled: Boolean,
    existing: McpServerConfiguration.StreamableHttp?,
    preserveOAuth: Boolean,
): McpServerConfiguration.StreamableHttp {
    val normalizedUrl = url.trim()
    require(normalizedUrl.isNotEmpty()) { "An MCP Streamable HTTP URL must not be blank." }
    val nextOauth = oauth?.toConfiguration(
        existing = existing?.oauth,
        preserveOAuth = preserveOAuth && existing?.url == normalizedUrl,
    )
    return McpServerConfiguration.StreamableHttp(
        url = normalizedUrl,
        headers = headers.resolveSecrets(existing?.headers.orEmpty(), "header"),
        oauth = nextOauth,
        enabled = enabled,
    )
}

private fun McpStdioDraft.toConfiguration(
    enabled: Boolean,
    existing: McpServerConfiguration.Stdio?,
): McpServerConfiguration.Stdio {
    val normalizedCommand = command.trim()
    require(normalizedCommand.isNotEmpty()) { "An MCP stdio command must not be blank." }
    return McpServerConfiguration.Stdio(
        command = normalizedCommand,
        args = args,
        environment = environment.resolveSecrets(existing?.environment.orEmpty(), "environment"),
        workingDirectory = workingDirectory,
        enabled = enabled,
    )
}

private fun McpOAuthDraft.toConfiguration(
    existing: McpOAuthConfiguration?,
    preserveOAuth: Boolean,
): McpOAuthConfiguration {
    val normalizedClientId = clientId.normalizedOptional()
    val existingClientSecret = existing?.client?.clientSecret
    val client = io.github.stream29.kodex.mcp.contract.McpOAuthClient(
        clientId = normalizedClientId,
        clientSecret = clientSecret.resolveOptionalSecret(existingClientSecret, "client secret"),
        redirectUri = redirectUri.trim(),
        authorizationEndpoint = authorizationEndpoint.normalizedOptional(),
        tokenEndpoint = tokenEndpoint.normalizedOptional(),
    )
    val uninitialized = McpOAuthConfiguration.Uninitialized(
        client = client,
        resource = resource.normalizedOptional(),
        scopes = scopes.map(String::trim).filter(String::isNotEmpty).distinct(),
    )
    val initialized = existing as? McpOAuthConfiguration.Initialized
    return if (
        preserveOAuth &&
        initialized != null &&
        initialized.loginIdentity() == uninitialized.loginIdentity()
    ) {
        initialized.copy(
            client = client,
            resource = uninitialized.resource,
            scopes = uninitialized.scopes,
        )
    } else {
        uninitialized
    }
}

private fun Map<String, McpSecretDraft>.resolveSecrets(
    existing: Map<String, McpSecret>,
    kind: String,
): Map<String, McpSecret> {
    val drafts = this
    return buildMap {
        drafts.forEach { (rawName, draft) ->
            val name = rawName.trim()
            require(name.isNotEmpty()) { "An MCP $kind name must not be blank." }
            require(name !in this) { "MCP $kind names must be unique." }
            val secret = when (draft) {
                McpSecretDraft.Keep -> existing[name]
                    ?: throw IllegalArgumentException(
                        "MCP $kind '$name' has no existing value to retain.",
                    )

                is McpSecretDraft.Replace -> McpSecret(draft.value)
            }
            put(name, secret)
        }
    }
}

private fun McpSecretDraft?.resolveOptionalSecret(
    existing: McpSecret?,
    kind: String,
): McpSecret? =
    when (this) {
        null -> null
        McpSecretDraft.Keep -> existing
            ?: throw IllegalArgumentException("The MCP $kind has no existing value to retain.")

        is McpSecretDraft.Replace -> McpSecret(value)
    }

private fun String?.normalizedOptional(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

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
