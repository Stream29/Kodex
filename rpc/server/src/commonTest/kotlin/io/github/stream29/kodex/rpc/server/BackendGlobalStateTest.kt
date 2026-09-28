@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.cli.settings.openBackendSettings
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

val backendGlobalStateTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("complete snapshot preserves secrets and a same-value import never writes or invalidates") {
        withGlobalFixture { global, service, fs, _ ->
            val initial = global.settings.value
            assertTrue(global.compareAndSetSettings(initial, initial))
            assertEquals(0, fs.moves)
            assertEquals(0, service.invalidations)
            assertFalse(fs.exists(global.store.settingsPath))
            val config = assertIs<McpServerConfiguration.StreamableHttp>(initial.mcpServers["alpha"])
            assertEquals(McpSecret("header-secret"), config.headers["Authorization"])
            val oauth = assertIs<McpOAuthConfiguration.Initialized>(config.oauth)
            assertEquals(McpSecret("access-token"), oauth.accessToken)
            global.mcpServers.first { it.size == 1 }.single().let {
                assertFalse(it.enabled)
                assertEquals(listOf("Authorization"), it.headerNames)
                assertFalse("header-secret" in it.toString())
                assertFalse("access-token" in it.toString())
            }
        }
    }
    test("background credential refresh makes the complete stale CAS fail") {
        withGlobalFixture { global, _, fs, _ ->
            val old = global.settings.value
            global.mcpConfigurations.update { current ->
                val config = current.getValue("alpha") as McpServerConfiguration.StreamableHttp
                val oauth = config.oauth as McpOAuthConfiguration.Initialized
                current + ("alpha" to config.copy(oauth = oauth.copy(accessToken = McpSecret("new-token"))))
            }
            assertFalse(global.compareAndSetSettings(old, old.copy(authSource = KodexAuthSource.Kodex)))
            assertEquals(1, fs.moves)
            assertEquals(KodexAuthSource.Codex, global.settings.value.authSource)
            val current = global.settings.value
            assertTrue(global.compareAndSetSettings(current, current.copy(authSource = KodexAuthSource.Kodex)))
            val saved = openBackendSettings(Path(global.store.settingsPath.parent!!.toString()), old).settings.value
            assertEquals(global.settings.value, saved)
        }
    }
    test("invalid configuration fails before persistence and does not become CAS false") {
        withGlobalFixture { global, _, fs, _ ->
            val initial = global.settings.value
            assertFailsWith<IllegalArgumentException> {
                global.compareAndSetSettings(initial, initial.copy(mcpServers = mapOf(
                    " " to McpServerConfiguration.Stdio("never-run", enabled = false),
                )))
            }
            assertEquals(0, fs.moves)
            assertEquals(initial, global.settings.value)
            // A stale proposal is rejected by comparison, not validated against a different snapshot.
            assertFalse(global.compareAndSetSettings(initial.copy(mcpServers = emptyMap()),
                initial.copy(mcpServers = mapOf("" to McpServerConfiguration.Stdio("never-run")))))
        }
    }
    test("rename requires OAuth reset while nonidentity edits preserve complete credentials") {
        withGlobalFixture { global, _, _, _ ->
            val initial = global.settings.value
            val config = initial.mcpServers.getValue("alpha") as McpServerConfiguration.StreamableHttp
            assertFailsWith<IllegalArgumentException> {
                global.compareAndSetSettings(initial, initial.copy(mcpServers = mapOf("renamed" to config)))
            }
            assertFailsWith<IllegalArgumentException> {
                global.compareAndSetSettings(initial, initial.copy(mcpServers =
                    mapOf("alpha" to config.copy(url = "https://other.example.test/mcp"))))
            }
            val changed = initial.copy(mcpServers = mapOf("alpha" to config.copy(
                headers = config.headers + ("X-Custom" to McpSecret("another-secret")),
            )))
            assertTrue(global.compareAndSetSettings(initial, changed))
            val oauth = config.oauth as McpOAuthConfiguration.Initialized
            val renamed = changed.copy(mcpServers = mapOf("renamed" to config.copy(
                oauth = McpOAuthConfiguration.Uninitialized(oauth.client, oauth.resource, oauth.scopes),
            )))
            assertTrue(global.compareAndSetSettings(changed, renamed))
            global.mcpServers.first { it.singleOrNull()?.serverName == "renamed" }
        }
    }
    test("failed file replacement publishes neither settings nor fake connection success") {
        withGlobalFixture { global, service, fs, _ ->
            val initial = global.settings.value
            fs.fail = true
            assertFailsWith<IllegalStateException> {
                global.compareAndSetSettings(initial, initial.copy(authSource = KodexAuthSource.Kodex))
            }
            assertEquals(initial, global.settings.value)
            assertEquals(0, service.invalidations)
            fs.fail = false
            assertTrue(global.compareAndSetSettings(initial, initial.copy(authSource = KodexAuthSource.Kodex)))
        }
    }
    test("CAS publication and a queued token write share the actual store lock") {
        kotlinx.coroutines.coroutineScope {
            withGlobalFixture { global, _, fs, _ ->
                val initial = global.settings.value
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                fs.beforeMove = { entered.complete(Unit); release.await() }
                val cas = async {
                    global.compareAndSetSettings(initial, initial.copy(authSource = KodexAuthSource.Kodex))
                }
                entered.await()
                val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                    global.mcpConfigurations.update { current -> current - "alpha" }
                }
                assertFalse(refresh.isCompleted)
                fs.beforeMove = {}
                release.complete(Unit)
                assertTrue(cas.await())
                refresh.await()
                assertEquals(KodexAuthSource.Kodex, global.settings.value.authSource)
                assertEquals(emptyMap(), global.settings.value.mcpServers)
                assertEquals(2, fs.moves)
            }
        }
    }
    test("observing and cancelling one frontend does not close shared resources") {
        withGlobalFixture { global, service, _, _ ->
            kotlinx.coroutines.coroutineScope {
                val observer = launch(start = CoroutineStart.UNDISPATCHED) { global.mcpServers.collect() }
                observer.cancelAndJoin()
                assertFalse(service.closed)
                assertTrue(global.compareAndSetSettings(global.settings.value, global.settings.value))
            }
            assertFailsWith<IllegalArgumentException> { global.reconnectMcpServer("missing") }
        }
    }
    test("context projection follows backend settings with backend-owned roots") {
        withGlobalFixture { global, _, _, root ->
            val initial = global.settings.value
            val source = initial.contextSources.copy(agentsHomeEnabled = false)
            assertTrue(global.compareAndSetSettings(initial, initial.copy(contextSources = source)))
            val context = global.contextSettings.first { it.sources == source }
            assertEquals(Path(root, "agents"), context.agentsHome)
            assertEquals(root, context.kodexHome)
            assertEquals(Path(root, "codex"), context.codexHome)
        }
    }
    test("explicit Codex read distinguishes absent configuration and malformed TOML") {
        withGlobalFixture { global, _, _, root ->
            assertEquals(emptyList(), global.getCodexMcpSettings())
            val home = Path(root, "codex")
            SystemCoroutineFileSystem.createDirectories(home)
            SystemCoroutineFileSystem.writeString(Path(home, "config.toml"), "[invalid\n")
            assertFails { global.getCodexMcpSettings() }
        }
    }
    test("Codex import is sorted and contains declarations but no initialized OAuth") {
        withGlobalFixture { global, _, _, root ->
            val home = Path(root, "codex")
            SystemCoroutineFileSystem.createDirectories(home)
            SystemCoroutineFileSystem.writeString(Path(home, "config.toml"), """
                [mcp_servers.zeta]
                command = "never-run"
                [mcp_servers.alpha]
                url = "https://example.test/mcp"
                scopes = ["read"]
                [mcp_servers.alpha.http_headers]
                Authorization = "import-secret"
            """.trimIndent())
            val result = global.getCodexMcpSettings()
            assertEquals(listOf("alpha", "zeta"), result.map { it.serverName })
            val config = assertIs<McpServerConfiguration.StreamableHttp>(
                assertIs<McpCodexImportCandidate.Supported>(result.first()).configuration)
            assertIs<McpOAuthConfiguration.Uninitialized>(config.oauth)
            assertEquals(McpSecret("import-secret"), config.headers["Authorization"])
        }
    }
    test("model bootstrap preserves source order under the backend owner") {
        val expected = listOf("z-model", "a-model").map { ModelInfo(OpenAiModelId(it), it) }
        val client = mockOpenAiClient { listModels { OpenAiResult.Success(ModelsResponse(expected)) } }
        withGlobalFixture(client) { global, _, _, _ ->
            assertEquals(expected, global.models.first { it == expected })
        }
    }
    test("backend exit cancels and joins model refresh without relying on explicit frontend close") {
        val entered = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val client = mockOpenAiClient {
            listModels {
                try { entered.complete(Unit); awaitCancellation() }
                finally { cleaned.complete(Unit) }
            }
        }
        withGlobalFixture(client) { _, _, _, _ -> entered.await() }
        assertTrue(cleaned.isCompleted)
    }
    test("real MCP service reconciles CAS enable disable and rename without frontend reconnect") {
        val root = Path(SystemTemporaryDirectory, "kodex-rpc-global-${Random.nextLong()}")
        val initial = globalDefaults().copy(mcpServers = mapOf(
            "alpha" to McpServerConfiguration.StreamableHttp(
                "https://never-contacted.example.test/mcp",
                oauth = McpOAuthConfiguration.Uninitialized(McpOAuthClient(clientId = "test-client")),
                enabled = false,
            ),
        ))
        try {
            val store = openBackendSettings(root, initial)
            withBackendGlobalState(
                store, root, root, root, mockOpenAiClient { listModels { awaitCancellation() } },
                tokenRefresher = { error("No initialized credentials in this test.") },
                loginAttemptFactory = { error("No login in this test.") },
            ) { global ->
                global.mcpServers.first { it.singleOrNull()?.enabled == false }
                assertTrue(global.mcpService.clients.value.isEmpty())
                val config = initial.mcpServers.getValue("alpha") as McpServerConfiguration.StreamableHttp
                val enabled = initial.copy(mcpServers = mapOf("alpha" to config.copy(enabled = true)))
                assertTrue(global.compareAndSetSettings(initial, enabled))
                val first = global.mcpService.clients.first { "alpha" in it }.getValue("alpha")
                first.state.first { it == McpClientState.AuthenticationBlocked }
                assertTrue(global.compareAndSetSettings(enabled, enabled))
                assertSame(first, global.mcpService.clients.value["alpha"])
                val renamed = enabled.copy(mcpServers = mapOf("beta" to config.copy(enabled = true)))
                assertTrue(global.compareAndSetSettings(enabled, renamed))
                val next = global.mcpService.clients.first { "beta" in it && "alpha" !in it }.getValue("beta")
                assertNotSame(first, next)
                assertTrue(global.compareAndSetSettings(renamed, initial))
                global.mcpService.clients.first { it.isEmpty() }
                global.mcpServers.first { it.singleOrNull()?.serverName == "alpha" && !it.single().enabled }
            }
        } finally {
            deleteGlobalTestTree(root)
        }
    }
}

private suspend fun withGlobalFixture(
    client: OpenAiClient = mockOpenAiClient { listModels { awaitCancellation() } },
    block: suspend (BackendGlobalState, GlobalTestMcpService, GlobalTestFileSystem, Path) -> Unit,
) {
    val root = Path(SystemTemporaryDirectory, "kodex-rpc-global-${Random.nextLong()}")
    val fs = GlobalTestFileSystem()
    val service = GlobalTestMcpService()
    try {
        val store = openBackendSettings(root, globalDefaults(), fs)
        withBackendGlobalState(
            store, Path(root, "agents"), root, Path(root, "codex"), client,
            loginAttemptFactory = { error("No browser login in this fixture.") },
            serviceFactory = { _, _ -> service },
        ) { global -> block(global, service, fs, root) }
        assertTrue(service.closed)
    } finally {
        deleteGlobalTestTree(root)
    }
}

private class GlobalTestMcpService : McpService {
    override val clients = MutableStateFlow<Map<String, McpClient>>(emptyMap())
    override val authentication = MutableStateFlow<Map<String, McpAuthenticationState>>(emptyMap())
    var invalidations = 0
    var closed = false
    override suspend fun invalidate(serverName: String) { invalidations++ }
    override suspend fun refresh() = Unit
    override fun close() { closed = true }
}

private class GlobalTestFileSystem : CoroutineFileSystem by SystemCoroutineFileSystem {
    var moves = 0
    var fail = false
    var beforeMove: suspend () -> Unit = {}
    override suspend fun atomicMove(source: Path, destination: Path) {
        beforeMove()
        if (fail) error("Synthetic persistence failure.")
        SystemCoroutineFileSystem.atomicMove(source, destination)
        moves++
    }
}

private fun globalDefaults(): BackendSettings = BackendSettings(
    authSource = KodexAuthSource.Codex,
    shell = Shell(ShellType.Sh, Path("/bin/sh")),
    contextSources = AgentContextSourceSettings(),
    newSession = KodexNewSessionSettings(),
    sessionTitle = SessionTitleSettings(),
    mcpServers = mapOf("alpha" to McpServerConfiguration.StreamableHttp(
        "https://example.test/mcp", headers = mapOf("Authorization" to McpSecret("header-secret")),
        oauth = McpOAuthConfiguration.Initialized(
            McpOAuthClient(clientId = "client-id"), resolvedAuthorizationEndpoint = "https://example.test/auth",
            resolvedTokenEndpoint = "https://example.test/token", accessToken = McpSecret("access-token"),
        ), enabled = false,
    )),
)

private suspend fun deleteGlobalTestTree(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { deleteGlobalTestTree(it) }
    SystemCoroutineFileSystem.delete(path)
}
