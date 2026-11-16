package io.github.stream29.kodex.mcp.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpConfigurationStore
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
val mcpManagerImplTest by testSuite {
    test("servers state redacts secrets and exposes sorted secret names and OAuth summary") {
        val initialized = initializedOAuth()
        val store = TestMcpConfigurationStore(
            mapOf(
                "alpha" to McpServerConfiguration.StreamableHttp(
                    url = "https://alpha.example.test/mcp",
                    headers = mapOf(
                        "X-Trace" to McpSecret("trace-secret"),
                        "Authorization" to McpSecret("header-secret"),
                    ),
                    oauth = initialized,
                ),
                "beta" to McpServerConfiguration.Stdio(
                    command = "stdio-mcp",
                    environment = mapOf(
                        "Z_TOKEN" to McpSecret("env-z"),
                        "A_TOKEN" to McpSecret("env-a"),
                    ),
                ),
            ),
        )
        val manager = testScope.backgroundScope.McpManagerImpl(
            store = store,
            service = TestMcpService(),
            loginPreparer = { error("Login is not expected.") },
        )
        testScope.runCurrent()

        val snapshotText = manager.servers.value.toString()
        assertFalse("header-secret" in snapshotText)
        assertFalse("trace-secret" in snapshotText)
        assertFalse("access-token" in snapshotText)
        assertFalse("client-secret" in snapshotText)
        assertFalse("env-a" in snapshotText)
        assertFalse("env-z" in snapshotText)

        val alpha = manager.servers.value.single { it.serverName == "alpha" }
        assertEquals(listOf("Authorization", "X-Trace"), alpha.headerNames)
        assertEquals(McpAuthenticationState.Authorized, alpha.authentication)
        assertEquals(true, alpha.oauth?.hasClientSecret)

        val beta = manager.servers.value.single { it.serverName == "beta" }
        assertEquals(listOf("A_TOKEN", "Z_TOKEN"), beta.environmentNames)
        assertEquals(McpAuthenticationState.NotConfigured, beta.authentication)
        manager.close()
    }

    test("loginWithPreparedCallback persists dynamic registration commits credentials and logs out") {
        val uninitialized = McpOAuthConfiguration.Uninitialized(
            client = McpOAuthClient(),
        )
        val registered = uninitialized.copy(
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
        val codeGate = CompletableDeferred<String>()
        var exchangedCode: String? = null
        val manager = testScope.backgroundScope.McpManagerImpl(
            store = store,
            service = TestMcpService(),
            loginPreparer = {
                PreparedMcpOAuthLogin(
                    preparedConfiguration = registered,
                    state = "test-state",
                    authorizationUrl = "https://issuer.example.test/authorize?state=test-state",
                    exchange = { code ->
                        exchangedCode = code
                        initializedOAuth().copy(
                            client = registered.client,
                            resource = registered.resource,
                            scopes = registered.scopes,
                        )
                    },
                )
            },
        )
        testScope.runCurrent()

        var observedPrepared: PreparedMcpOAuthLogin? = null
        val loginJob = launch {
            manager.loginWithPreparedCallback(
                serverName = "oauth",
                expectedRedirectUri = uninitialized.client.redirectUri,
            ) { prepared ->
                observedPrepared = prepared
                codeGate.await()
            }
        }
        testScope.runCurrent()

        assertEquals(registered, observedPrepared?.preparedConfiguration)
        assertEquals(
            registered,
            assertIs<McpServerConfiguration.StreamableHttp>(
                store.configurations.value.getValue("oauth"),
            ).oauth,
        )
        assertEquals(
            McpAuthenticationState.Authorizing,
            manager.servers.value.single().authentication,
        )
        assertFailsWith<IllegalArgumentException> { manager.logout("oauth") }

        codeGate.complete("accepted-code")
        loginJob.join()
        testScope.runCurrent()

        assertEquals("accepted-code", exchangedCode)
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
        testScope.runCurrent()
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

    test("commitConfigurationChange cancels active login when server is removed") {
        val uninitialized = initializedOAuth().toUninitialized()
        val initial = mapOf(
            "oauth" to McpServerConfiguration.StreamableHttp(
                url = "https://oauth.example.test/mcp",
                oauth = uninitialized,
            ),
        )
        val store = TestMcpConfigurationStore(initial)
        val waiting = CompletableDeferred<String>()
        val manager = testScope.backgroundScope.McpManagerImpl(
            store = store,
            service = TestMcpService(),
            loginPreparer = {
                PreparedMcpOAuthLogin(
                    preparedConfiguration = uninitialized,
                    state = "state",
                    authorizationUrl = "https://issuer.example.test/authorize?state=state",
                    exchange = { initializedOAuth() },
                )
            },
        )
        testScope.runCurrent()

        val loginJob = launch {
            manager.loginWithPreparedCallback("oauth") { waiting.await() }
        }
        testScope.runCurrent()
        assertTrue(loginJob.isActive)

        assertTrue(
            manager.commitConfigurationChange(initial, emptyMap()) {
                store.configurations.value = emptyMap()
                true
            },
        )
        testScope.runCurrent()
        assertTrue(loginJob.isCancelled)
        assertEquals(emptyList(), manager.servers.value)
        manager.close()
    }

    test("reconnectDelegatesOnlyToPublishedServiceClient") {
        val client = TestMcpClient("server")
        val manager = testScope.backgroundScope.McpManagerImpl(
            store = TestMcpConfigurationStore(
                mapOf("server" to McpServerConfiguration.Stdio(command = "server")),
            ),
            service = TestMcpService(mapOf("server" to client)),
            loginPreparer = { error("Login is not expected.") },
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

    override suspend fun update(
        transform: (Map<String, McpServerConfiguration>) -> Map<String, McpServerConfiguration>,
    ): Map<String, McpServerConfiguration> =
        mutex.withLock {
            val updated = transform(configurations.value)
            configurations.value = updated
            updated
        }
}

private class TestMcpService(
    initialClients: Map<String, McpClient> = emptyMap(),
) : McpService {
    override val clients: StateFlow<Map<String, McpClient>> = MutableStateFlow(initialClients)
    override val authentication: StateFlow<Map<String, McpAuthenticationState>> =
        MutableStateFlow(emptyMap())

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

private fun McpOAuthConfiguration.Initialized.toUninitialized() =
    McpOAuthConfiguration.Uninitialized(
        client = client,
        resource = resource,
        scopes = scopes,
    )
