package io.github.stream29.kodex.mcp.impl

import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpCodexImportSource
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportItemKind
import io.github.stream29.kodex.mcp.contract.McpManagerEffect
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthLoginAttempt
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpSecretDraft
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpStdioDraft
import io.github.stream29.kodex.mcp.contract.McpStreamableHttpDraft
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class McpManagerImplTest {
    @Test
    fun editsPreserveSecretsWhileRenameResetsOAuthState() = runTest {
        val initialized = initializedOAuth()
        val store = TestMcpConfigurationStore(
            mapOf(
                "alpha" to McpServerConfiguration.StreamableHttp(
                    url = "https://alpha.example.test/mcp",
                    headers = mapOf("Authorization" to McpSecret("header-secret")),
                    oauth = initialized,
                ),
            ),
        )
        val manager = backgroundScope.McpManagerImpl(
            store = store,
            service = TestMcpService(),
            codexImportSource = McpCodexImportSource { emptyList() },
            loginAttemptFactory = { error("Login is not expected.") },
        )
        runCurrent()

        assertFalse("header-secret" in manager.servers.value.toString())
        assertFalse("access-token" in manager.servers.value.toString())
        assertEquals(listOf("Authorization"), manager.servers.value.single().headerNames)

        manager.edit(
            existingServerName = "alpha",
            draft = McpServerDraft.StreamableHttp(
                serverName = "alpha",
                configuration = McpStreamableHttpDraft(
                    url = "https://alpha.example.test/mcp",
                    headers = mapOf("Authorization" to McpSecretDraft.Keep),
                    oauth = initialized.toDraft(),
                ),
            ),
        )
        val edited = assertIs<McpServerConfiguration.StreamableHttp>(
            store.configurations.value.getValue("alpha"),
        )
        assertEquals(McpSecret("header-secret"), edited.headers.getValue("Authorization"))
        assertEquals(initialized, edited.oauth)

        manager.edit(
            existingServerName = "alpha",
            draft = McpServerDraft.StreamableHttp(
                serverName = "alpha",
                configuration = McpStreamableHttpDraft(
                    url = "https://retargeted.example.test/mcp",
                    headers = mapOf("Authorization" to McpSecretDraft.Keep),
                    oauth = initialized.toDraft(),
                ),
            ),
        )
        val retargeted = assertIs<McpServerConfiguration.StreamableHttp>(
            store.configurations.value.getValue("alpha"),
        )
        assertIs<McpOAuthConfiguration.Uninitialized>(retargeted.oauth)
        assertEquals(
            McpSecret("client-secret"),
            assertIs<McpOAuthConfiguration.Uninitialized>(retargeted.oauth).client.clientSecret,
        )

        manager.edit(
            existingServerName = "alpha",
            draft = McpServerDraft.StreamableHttp(
                serverName = "beta",
                enabled = false,
                configuration = McpStreamableHttpDraft(
                    url = "https://alpha.example.test/mcp",
                    headers = mapOf("Authorization" to McpSecretDraft.Keep),
                    oauth = initialized.toDraft(),
                ),
            ),
        )
        val renamed = assertIs<McpServerConfiguration.StreamableHttp>(
            store.configurations.value.getValue("beta"),
        )
        assertFalse(renamed.enabled)
        assertIs<McpOAuthConfiguration.Uninitialized>(renamed.oauth)
        assertEquals(
            McpSecret("client-secret"),
            assertIs<McpOAuthConfiguration.Uninitialized>(renamed.oauth).client.clientSecret,
        )
        assertFalse("alpha" in store.configurations.value)

        manager.delete("beta")
        assertEquals(emptyMap(), store.configurations.value)
        manager.close()
    }

    @Test
    fun invalidDraftNeverWritesPartialSettings() = runTest {
        val store = TestMcpConfigurationStore()
        val manager = backgroundScope.McpManagerImpl(
            store = store,
            service = TestMcpService(),
            codexImportSource = McpCodexImportSource { emptyList() },
            loginAttemptFactory = { error("Login is not expected.") },
        )

        assertFailsWith<IllegalArgumentException> {
            manager.add(
                McpServerDraft.Stdio(
                    serverName = " ",
                    configuration = McpStdioDraft(command = "server"),
                ),
            )
        }
        assertEquals(0, store.successfulUpdateCount)
        assertEquals(emptyMap(), store.configurations.value)

        manager.add(
            McpServerDraft.Stdio(
                serverName = "stdio",
                configuration = McpStdioDraft(
                    command = "server",
                    environment = mapOf(
                        "TOKEN" to McpSecretDraft.Replace("environment-secret"),
                    ),
                    workingDirectory = Path("workspace"),
                ),
            ),
        )
        assertEquals(1, store.successfulUpdateCount)
        assertFalse("environment-secret" in manager.servers.value.toString())
        manager.close()
    }

    @Test
    fun importPreviewFiltersAndCommitsConflictChoicesAtomically() = runTest {
        val existing = McpServerConfiguration.Stdio(command = "existing")
        val store = TestMcpConfigurationStore(mapOf("conflict" to existing))
        val importedOauth = initializedOAuth()
        val service = TestMcpService()
        val manager = backgroundScope.McpManagerImpl(
            store = store,
            service = service,
            codexImportSource = McpCodexImportSource {
                supportedCodexImports(
                    "conflict" to McpServerConfiguration.Stdio(command = "replacement"),
                    "new-http" to McpServerConfiguration.StreamableHttp(
                        url = "https://new.example.test/mcp",
                        oauth = importedOauth,
                    ),
                    "unmatched" to McpServerConfiguration.Stdio(command = "unmatched"),
                ) + McpCodexImportCandidate.Unsupported(
                    serverName = "unsupported",
                    transport = McpTransportKind.StreamableHttp,
                    detail = "Unsupported fields: bearer_token_env_var.",
                )
            },
            loginAttemptFactory = { error("Login is not expected.") },
        )

        val filtered = manager.previewCodexImport("new")
        assertEquals(listOf("new-http"), filtered.items.map { it.serverName })
        assertFalse("access-token" in filtered.toString())

        val preview = manager.previewCodexImport()
        assertEquals(
            mapOf(
                "conflict" to McpImportItemKind.Conflict,
                "new-http" to McpImportItemKind.New,
                "unmatched" to McpImportItemKind.New,
                "unsupported" to McpImportItemKind.Unsupported,
            ),
            preview.items.associate { it.serverName to it.kind },
        )
        assertFalse(preview.items.single { it.serverName == "unsupported" }.selectable)
        val writesBeforeImport = store.successfulUpdateCount
        manager.applyCodexImport(
            previewId = preview.id,
            decisions = mapOf(
                "conflict" to McpImportDecision.Replace,
                "new-http" to McpImportDecision.Import,
                "unmatched" to McpImportDecision.Skip,
            ),
        )

        assertEquals(writesBeforeImport + 1, store.successfulUpdateCount)
        assertEquals(
            McpServerConfiguration.Stdio(command = "replacement"),
            store.configurations.value.getValue("conflict"),
        )
        val imported = assertIs<McpServerConfiguration.StreamableHttp>(
            store.configurations.value.getValue("new-http"),
        )
        assertIs<McpOAuthConfiguration.Uninitialized>(imported.oauth)
        assertFalse("unmatched" in store.configurations.value)
        runCurrent()
        assertEquals(listOf("conflict"), service.invalidatedServerNames)
        assertFailsWith<IllegalArgumentException> {
            manager.applyCodexImport(preview.id, emptyMap())
        }
        manager.close()
    }

    @Test
    fun browserLoginPublishesEffectPersistsCredentialsAndLogsOut() = runTest {
        val uninitialized = initializedOAuth().toUninitialized()
        val store = TestMcpConfigurationStore(
            mapOf(
                "oauth" to McpServerConfiguration.StreamableHttp(
                    url = "https://oauth.example.test/mcp",
                    oauth = uninitialized,
                ),
            ),
        )
        val attempt = TestMcpOAuthLoginAttempt(uninitialized)
        val manager = backgroundScope.McpManagerImpl(
            store = store,
            service = TestMcpService(),
            codexImportSource = McpCodexImportSource { emptyList() },
            loginAttemptFactory = { attempt },
        )
        runCurrent()

        val loginJob = launch { manager.login("oauth") }
        runCurrent()
        assertEquals(
            McpAuthenticationState.Authorizing,
            manager.servers.value.single().authentication,
        )
        val effect = assertIs<McpManagerEffect.OpenAuthorizationUrl>(manager.effects.first())
        assertEquals("oauth", effect.serverName)
        assertEquals(attempt.authorizationUrl, effect.url)

        attempt.result.complete(initializedOAuth())
        loginJob.join()
        runCurrent()
        assertIs<McpOAuthConfiguration.Initialized>(
            assertIs<McpServerConfiguration.StreamableHttp>(
                store.configurations.value.getValue("oauth"),
            ).oauth,
        )
        assertEquals(
            McpAuthenticationState.Authorized,
            manager.servers.value.single().authentication,
        )

        manager.logout("oauth")
        runCurrent()
        assertIs<McpOAuthConfiguration.Uninitialized>(
            assertIs<McpServerConfiguration.StreamableHttp>(
                store.configurations.value.getValue("oauth"),
            ).oauth,
        )
        assertEquals(
            McpAuthenticationState.LoginRequired,
            manager.servers.value.single().authentication,
        )
        manager.close()
    }

    @Test
    fun browserLoginPersistsDynamicRegistrationBeforeOpeningTheBrowser() = runTest {
        val uninitialized = McpOAuthConfiguration.Uninitialized(
            client = McpOAuthClient(),
        )
        val prepared = uninitialized.copy(
            client = uninitialized.client.copy(clientId = "registered-client"),
            resource = "https://oauth.example.test/mcp",
            scopes = listOf("tools.read"),
        )
        val store = TestMcpConfigurationStore(
            mapOf(
                "oauth" to McpServerConfiguration.StreamableHttp(
                    url = "https://oauth.example.test/mcp",
                    oauth = uninitialized,
                ),
            ),
        )
        val attempt = TestMcpOAuthLoginAttempt(prepared)
        val manager = backgroundScope.McpManagerImpl(
            store = store,
            service = TestMcpService(),
            codexImportSource = McpCodexImportSource { emptyList() },
            loginAttemptFactory = { attempt },
        )
        runCurrent()

        val loginJob = launch { manager.login("oauth") }
        runCurrent()

        assertEquals(
            prepared,
            assertIs<McpServerConfiguration.StreamableHttp>(
                store.configurations.value.getValue("oauth"),
            ).oauth,
        )
        attempt.result.complete(
            initializedOAuth().copy(
                client = prepared.client,
                resource = prepared.resource,
                scopes = prepared.scopes,
            ),
        )
        loginJob.join()
        manager.close()
    }

    @Test
    fun reconnectDelegatesOnlyToPublishedServiceClient() = runTest {
        val client = TestMcpClient("server")
        val manager = backgroundScope.McpManagerImpl(
            store = TestMcpConfigurationStore(
                mapOf("server" to McpServerConfiguration.Stdio(command = "server")),
            ),
            service = TestMcpService(mapOf("server" to client)),
            codexImportSource = McpCodexImportSource { emptyList() },
            loginAttemptFactory = { error("Login is not expected.") },
        )

        manager.reconnect("server")
        assertEquals(1, client.reconnectCount)
        assertFailsWith<IllegalArgumentException> { manager.reconnect("missing") }
        manager.close()
    }
}

private class TestMcpConfigurationStore(
    initial: Map<String, McpServerConfiguration> = emptyMap(),
) : McpConfigurationStore {
    private val mutex = Mutex()
    override val configurations = MutableStateFlow(initial)
    var successfulUpdateCount: Int = 0

    override suspend fun update(
        transform: (Map<String, McpServerConfiguration>) -> Map<String, McpServerConfiguration>,
    ): Map<String, McpServerConfiguration> =
        mutex.withLock {
            val updated = transform(configurations.value)
            configurations.value = updated
            successfulUpdateCount += 1
            updated
        }
}

private class TestMcpService(
    initialClients: Map<String, McpClient> = emptyMap(),
) : McpService {
    override val clients: StateFlow<Map<String, McpClient>> = MutableStateFlow(initialClients)
    override val authentication: StateFlow<Map<String, McpAuthenticationState>> =
        MutableStateFlow(emptyMap())
    val invalidatedServerNames = mutableListOf<String>()
    override suspend fun invalidate(serverName: String) {
        invalidatedServerNames += serverName
    }

    override suspend fun refresh(): Unit = Unit
    override fun close(): Unit = Unit
}

private class TestMcpClient(
    override val serverName: String,
) : McpClient {
    override val state: StateFlow<McpClientState> = MutableStateFlow(McpClientState.Healthy)
    var reconnectCount: Int = 0

    override fun listTools(): List<McpTool> = emptyList()

    override suspend fun reconnect() {
        reconnectCount += 1
    }
}

private class TestMcpOAuthLoginAttempt(
    override val preparedConfiguration: McpOAuthConfiguration.Uninitialized,
) : McpOAuthLoginAttempt {
    override val authorizationUrl: String = "https://issuer.example.test/authorize?state=test"
    val result = CompletableDeferred<McpOAuthConfiguration.Initialized>()
    var closed: Boolean = false

    override suspend fun awaitInitialized(): McpOAuthConfiguration.Initialized = result.await()

    override fun close() {
        closed = true
        result.cancel(CancellationException("Authorization attempt closed."))
    }
}

private fun supportedCodexImports(
    vararg servers: Pair<String, McpServerConfiguration>,
): List<McpCodexImportCandidate> =
    servers.map { (name, configuration) ->
        McpCodexImportCandidate.Supported(
            serverName = name,
            configuration = configuration,
        )
    }

private fun initializedOAuth(): McpOAuthConfiguration.Initialized =
    McpOAuthConfiguration.Initialized(
        client = McpOAuthClient(
            clientId = "client-id",
            clientSecret = McpSecret("client-secret"),
            authorizationEndpoint = "https://issuer.example.test/authorize",
            tokenEndpoint = "https://issuer.example.test/token",
        ),
        resource = "https://resource.example.test",
        scopes = listOf("tools.read"),
        resolvedAuthorizationEndpoint = "https://issuer.example.test/authorize",
        resolvedTokenEndpoint = "https://issuer.example.test/token",
        accessToken = McpSecret("access-token"),
        refreshToken = McpSecret("refresh-token"),
        expiresAtEpochSeconds = 1_800_000_000,
    )

private fun McpOAuthConfiguration.Initialized.toDraft() =
    io.github.stream29.kodex.mcp.contract.McpOAuthDraft(
        clientId = client.clientId,
        clientSecret = McpSecretDraft.Keep,
        redirectUri = client.redirectUri,
        authorizationEndpoint = client.authorizationEndpoint,
        tokenEndpoint = client.tokenEndpoint,
        resource = resource,
        scopes = scopes,
    )

private fun McpOAuthConfiguration.Initialized.toUninitialized() =
    McpOAuthConfiguration.Uninitialized(
        client = client,
        resource = resource,
        scopes = scopes,
    )
