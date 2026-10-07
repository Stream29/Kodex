package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenEndpointAuthMethod
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.rpc.models.CliSidebarSettings
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val splitSettingsStoreTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("loading missing files creates neither file nor directory") {
        withSplitSettingsDirectory { directory ->
            assertFalse(SystemCoroutineFileSystem.exists(directory))
            val defaults = backendDefaults()
            assertEquals(defaults, openBackendSettings(directory, defaults).settings.value)
            assertEquals(CliFrontendSettings(), openCliFrontendSettings(directory).settings.value)
            assertFalse(SystemCoroutineFileSystem.exists(directory))
        }
    }

    test("each side ignores legacy and opposite files and writes only its own file") {
        withSplitSettingsDirectory { directory ->
            val old = Path(directory, "settings.yml")
            val backendPath = Path(directory, "settings.backend.yml")
            val frontendPath = Path(directory, "settings.frontend.cli.yml")
            writeFixture(old, "legacy: [invalid\n")
            writeFixture(frontendPath, "frontend: [invalid\n")
            val backend = openBackendSettings(directory, backendDefaults())
            backend.update { it.copy(authSource = KodexAuthSource.Kodex) }
            assertEquals("frontend: [invalid\n", SystemCoroutineFileSystem.readString(frontendPath))
            assertEquals("legacy: [invalid\n", SystemCoroutineFileSystem.readString(old))
            val backendText = SystemCoroutineFileSystem.readString(backendPath)
            SystemCoroutineFileSystem.delete(frontendPath)
            val frontend = openCliFrontendSettings(directory)
            frontend.update { it.copy(newLineKey = NewLineKey.Enter) }
            assertEquals(backendText, SystemCoroutineFileSystem.readString(backendPath))
            assertEquals("legacy: [invalid\n", SystemCoroutineFileSystem.readString(old))
            writeFixture(backendPath, "backend: [invalid\n")
            assertEquals(NewLineKey.Enter, frontend.reload().newLineKey)
        }
    }

    test("backend sparse nested fields resolve against supplied defaults without rewriting") {
        withSplitSettingsDirectory { directory ->
            val defaults = backendDefaults().copy(
                authSource = KodexAuthSource.Kodex,
                contextSources = AgentContextSourceSettings(
                    kodexHomeEnabled = false,
                    customSources = listOf(AgentContextCustomSource("default-source")),
                ),
                newSession = KodexNewSessionSettings(
                    model = OpenAiModelId("default-model"),
                    reasoningEffort = ReasoningEffort.High,
                    serviceTier = ServiceTier.Flex,
                    requestUserInputMode = RequestUserInputMode.NoQuestion,
                ),
                sessionTitle = SessionTitleSettings(
                    enabled = false,
                    model = OpenAiModelId("default-title"),
                    reasoningEffort = ReasoningEffort.High,
                ),
            )
            val text = """
                future_section: {unused: true}
                context_sources:
                  agents_home: false
                  unknown: ignored
                new_session:
                  model: selected-model
                session_title:
                  enabled: true
            """.trimIndent()
            val path = Path(directory, "settings.backend.yml")
            writeFixture(path, text)
            val loaded = openBackendSettings(directory, defaults).settings.value
            assertEquals(
                defaults.copy(
                    contextSources = defaults.contextSources.copy(agentsHomeEnabled = false),
                    newSession = defaults.newSession.copy(model = OpenAiModelId("selected-model")),
                    sessionTitle = defaults.sessionTitle.copy(enabled = true),
                ),
                loaded,
            )
            assertEquals(text, SystemCoroutineFileSystem.readString(path))
        }
    }

    test("explicit nullable title model and empty collections override nonempty defaults") {
        withSplitSettingsDirectory { directory ->
            val defaults = backendDefaults().copy(
                sessionTitle = SessionTitleSettings(model = OpenAiModelId("configured-default")),
                contextSources = AgentContextSourceSettings(
                    customSources = listOf(AgentContextCustomSource("source")),
                ),
                mcpServers = mapOf("default" to McpServerConfiguration.Stdio(command = "unused")),
            )
            writeFixture(
                Path(directory, "settings.backend.yml"),
                "session_title: {model: null}\ncontext_sources: {custom_sources: []}\nmcp_servers: {}\n",
            )
            val store = openBackendSettings(directory, defaults)
            val expected = defaults.copy(
                sessionTitle = defaults.sessionTitle.copy(model = null),
                contextSources = defaults.contextSources.copy(customSources = emptyList()),
                mcpServers = emptyMap(),
            )
            assertEquals(expected, store.settings.value)
            store.update { it }
            assertEquals(expected, openBackendSettings(directory, defaults).settings.value)
        }
    }

    test("backend full values retain shell custom sources and MCP credentials") {
        withSplitSettingsDirectory { directory ->
            val expected = backendDefaults().copy(
                authSource = KodexAuthSource.Kodex,
                shell = Shell(ShellType.Bash, Path("/selected/bin/bash")),
                contextSources = AgentContextSourceSettings(
                    agentsHomeEnabled = false,
                    workingDirectoryEnabled = false,
                    customSources = listOf(AgentContextCustomSource("source", enabled = false)),
                ),
                newSession = KodexNewSessionSettings(
                    model = OpenAiModelId("selected-model"),
                    reasoningEffort = ReasoningEffort.Max,
                    serviceTier = ServiceTier.Fast,
                    requestUserInputMode = RequestUserInputMode.NoQuestion,
                ),
                sessionTitle = SessionTitleSettings(
                    enabled = false,
                    model = OpenAiModelId("selected-title"),
                    reasoningEffort = ReasoningEffort.Medium,
                ),
                mcpServers = testMcpConfigurations(),
            )
            val store = openBackendSettings(directory, backendDefaults())
            assertEquals(expected, store.update { expected })
            assertEquals(expected, store.settings.value)
            assertEquals(expected, openBackendSettings(directory, backendDefaults()).settings.value)
            val text = SystemCoroutineFileSystem.readString(store.settingsPath)
            assertTrue("auth_source: kodex" in text)
            assertTrue("context_sources:" in text)
            assertTrue("access-token" in text)
            assertFalse("access-token" in expected.toString())
            assertFalse("new_line_key:" in text)
            assertFalse("sidebars:" in text)
            assertFalse("hooks:" in text)
        }
    }

    test("frontend sparse fields preserve defaults and omit obsolete widths on update") {
        withSplitSettingsDirectory { directory ->
            val defaults = CliFrontendSettings(
                newLineKey = NewLineKey.Enter,
                sidebars = CliSidebarSettings(SidebarContent.None, SidebarContent.HistoryIndex),
                hooks = listOf(testHook("compiled")),
            )
            val path = Path(directory, "settings.frontend.cli.yml")
            val text = "sidebars: {right: terminal_sessions, left_width: 99}\nhooks: []\nfuture: true\n"
            writeFixture(path, text)
            val store = openCliFrontendSettings(directory, defaults)
            val expected = defaults.copy(
                sidebars = defaults.sidebars.copy(right = SidebarContent.TerminalSessions),
                hooks = emptyList(),
            )
            assertEquals(expected, store.settings.value)
            assertEquals(text, SystemCoroutineFileSystem.readString(path))
            store.update { it }
            val encoded = SystemCoroutineFileSystem.readString(path)
            assertFalse("left_width" in encoded)
            assertFalse("future" in encoded)
            assertEquals(expected, openCliFrontendSettings(directory, defaults).settings.value)
        }
    }

    test("frontend ordered hooks preserve multiple types and command data without executing") {
        withSplitSettingsDirectory { directory ->
            val expected = CliFrontendSettings(
                newLineKey = NewLineKey.Enter,
                sidebars = CliSidebarSettings(SidebarContent.None, SidebarContent.HistoryIndex),
                hooks = listOf(
                    testHook("first").copy(command = "never-executed \"引号\" \nsecond line"),
                    testHook("second").copy(types = NotificationHookType.entries.toSet()),
                ),
            )
            val store = openCliFrontendSettings(directory)
            assertEquals(expected, store.update { expected })
            assertEquals(expected, openCliFrontendSettings(directory).settings.value)
            val text = SystemCoroutineFileSystem.readString(store.settingsPath)
            assertTrue("types:" in text)
            assertTrue("stop_request_user_input" in text)
            assertFalse("auth_source:" in text)
            assertFalse("mcp_servers:" in text)
            assertFalse("left_width:" in text)
        }
    }

    for ((label, text) in listOf(
        "empty hook types" to "hooks: [{name: n, types: [], command: c}]",
        "unknown hook type" to "hooks: [{name: n, types: [unknown], command: c}]",
        "duplicate hook names" to """
            hooks:
              - {name: n, types: [stop_assistant_message], command: a}
              - {name: n, types: [stop_unhandled_error], command: b}
        """.trimIndent(),
        "empty command" to "hooks: [{name: n, types: [stop_unhandled_error], command: ' '}]",
        "invalid key" to "new_line_key: unsupported",
    )) {
        test("frontend rejects $label without replacing the file") {
            withSplitSettingsDirectory { directory ->
                val path = Path(directory, "settings.frontend.cli.yml")
                writeFixture(path, text)
                assertFailsWith<IllegalArgumentException> { openCliFrontendSettings(directory) }
                assertEquals(text, SystemCoroutineFileSystem.readString(path))
            }
        }
    }

    for ((label, text) in listOf(
        "null required field" to "auth_source: null",
        "null nested structure" to "new_session: null",
        "null required nested value" to "new_session: {model: null}",
        "invalid service tier" to "new_session: {service_tier: unsupported}",
        "invalid context source" to "context_sources: {custom_sources: [{path: ' '}]}",
    )) {
        test("backend rejects $label without replacing the file") {
            withSplitSettingsDirectory { directory ->
                val path = Path(directory, "settings.backend.yml")
                writeFixture(path, text)
                assertFailsWith<IllegalArgumentException> { openBackendSettings(directory, backendDefaults()) }
                assertEquals(text, SystemCoroutineFileSystem.readString(path))
            }
        }
    }

    for (side in SplitSettingsSide.entries) {
        test("$side empty mapping uses defaults and normal update clears only its own temporary") {
            withSplitSettingsDirectory { directory ->
                val path = Path(directory, side.fileName)
                writeFixture(path, "{}")
                val unrelated = Path(directory, ".user-owned.tmp")
                writeFixture(unrelated, "preserve")
                val probe = side.open(directory)
                assertEquals(emptyList(), probe.markers())
                probe.append("first")
                assertEquals(listOf("first"), probe.markers())
                assertEquals(listOf("first"), side.open(directory).markers())
                assertEquals("preserve", SystemCoroutineFileSystem.readString(unrelated))
                assertNoTemporary(directory, side)
            }
        }

        test("$side updates use the current file and reload publishes it") {
            withSplitSettingsDirectory { directory ->
                val first = side.open(directory)
                first.append("first")
                val second = side.open(directory)
                second.append("second")
                assertEquals(listOf("first"), first.markers())
                first.append("third")
                assertEquals(listOf("first", "second", "third"), first.markers())
                second.reload()
                assertEquals(first.markers(), second.markers())
            }
        }

        test("$side serializes concurrent updates on the same store") {
            withSplitSettingsDirectory { directory ->
                val probe = side.open(directory)
                coroutineScope {
                    repeat(16) { index -> launch { probe.append("marker-$index") } }
                }
                assertEquals((0 until 16).map { "marker-$it" }.toSet(), probe.markers().toSet())
                assertEquals(16, probe.markers().size)
                assertEquals(probe.markers(), side.open(directory).markers())
                assertNoTemporary(directory, side)
            }
        }

        test("$side malformed reload and update preserve the last published state") {
            withSplitSettingsDirectory { directory ->
                val probe = side.open(directory)
                probe.append("before")
                val path = Path(directory, side.fileName)
                writeFixture(path, "broken: [\n")
                assertFailsWith<IllegalArgumentException> { probe.reload() }
                assertFailsWith<IllegalArgumentException> { probe.append("after") }
                assertEquals(listOf("before"), probe.markers())
                assertEquals("broken: [\n", SystemCoroutineFileSystem.readString(path))
                assertNoTemporary(directory, side)
            }
        }

        test("$side transform failures preserve the original throwable file and state") {
            withSplitSettingsDirectory { directory ->
                val probe = side.open(directory)
                probe.append("before")
                val path = Path(directory, side.fileName)
                val contents = SystemCoroutineFileSystem.readString(path)
                val expected = IllegalStateException("transform failed")
                assertSame(expected, assertFailsWith<IllegalStateException> { probe.fail(expected) })
                assertEquals(contents, SystemCoroutineFileSystem.readString(path))
                assertEquals(listOf("before"), probe.markers())
                assertNoTemporary(directory, side)
            }
        }

        test("$side a failed temporary write cleans up and permits a later update") {
            withSplitSettingsDirectory { directory ->
                val fileSystem = FaultingSettingsFileSystem()
                val probe = side.open(directory, fileSystem)
                probe.append("before")
                val path = Path(directory, side.fileName)
                val contents = SystemCoroutineFileSystem.readString(path)
                val expected = IOException("write failed")
                fileSystem.afterWrite = { throw expected }
                assertSame(expected, assertFailsWith<IOException> { probe.append("lost") })
                assertEquals(listOf("before"), probe.markers())
                assertEquals(contents, SystemCoroutineFileSystem.readString(path))
                assertNoTemporary(directory, side)
                fileSystem.afterWrite = {}
                probe.append("after")
                assertEquals(listOf("before", "after"), probe.markers())
            }
        }

        test("$side cancellation before replacement waits for active temporary cleanup") {
            withSplitSettingsDirectory { directory ->
                val fileSystem = FaultingSettingsFileSystem()
                val probe = side.open(directory, fileSystem)
                probe.append("before")
                val path = Path(directory, side.fileName)
                val contents = SystemCoroutineFileSystem.readString(path)
                val entered = CompletableDeferred<Unit>()
                val cleaned = CompletableDeferred<Unit>()
                fileSystem.beforeMove = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                fileSystem.afterDelete = {
                    currentCoroutineContext().ensureActive()
                    cleaned.complete(Unit)
                }
                coroutineScope {
                    val operation = launch(start = CoroutineStart.UNDISPATCHED) { probe.append("cancelled") }
                    try {
                        withTimeout(10.seconds) { entered.await() }
                    } finally {
                        operation.cancelAndJoin()
                    }
                    assertTrue(operation.isCancelled)
                }
                assertTrue(cleaned.isCompleted)
                assertEquals(listOf("before"), probe.markers())
                assertEquals(contents, SystemCoroutineFileSystem.readString(path))
                assertNoTemporary(directory, side)
            }
        }

        test("$side cleanup failures do not replace the original write failure") {
            withSplitSettingsDirectory { directory ->
                val fileSystem = FaultingSettingsFileSystem()
                val probe = side.open(directory, fileSystem)
                val primary = IOException("replacement failed")
                val cleanup = IOException("cleanup failed")
                fileSystem.beforeMove = { throw primary }
                fileSystem.afterDelete = { throw cleanup }
                val actual = assertFailsWith<IOException> { probe.append("failed") }
                assertSame(primary, actual)
                // JVM coroutine stack recovery can copy the exception crossing withContext.
                val suppressed = assertIs<IOException>(actual.suppressedExceptions.single())
                assertEquals(cleanup.message, suppressed.message)
                assertEquals(emptyList(), probe.markers())
                assertFalse(SystemCoroutineFileSystem.exists(Path(directory, side.fileName)))
                assertNoTemporary(directory, side)
            }
        }

        test("$side cleanup-only failure propagates after persistence without publishing or claiming rollback") {
            withSplitSettingsDirectory { directory ->
                val fileSystem = FaultingSettingsFileSystem()
                val probe = side.open(directory, fileSystem)
                probe.append("before")
                val cleanup = IOException("cleanup failed after replacement")
                fileSystem.afterDelete = { throw cleanup }
                val actual = assertFailsWith<IOException> { probe.append("committed") }
                // Coroutine stack recovery may copy the failure crossing withContext.
                assertEquals(cleanup.message, actual.message)
                assertEquals(listOf("before"), probe.markers())
                assertEquals(listOf("before", "committed"), side.open(directory).markers())
                assertNoTemporary(directory, side)
            }
        }

        test("$side lost completion after replacement is not reported as a rollback") {
            withSplitSettingsDirectory { directory ->
                val fileSystem = FaultingSettingsFileSystem()
                val probe = side.open(directory, fileSystem)
                probe.append("before")
                val failure = CancellationException("completion lost")
                fileSystem.afterMove = { throw failure }
                assertSame(failure, assertFailsWith<CancellationException> { probe.append("committed") })
                assertEquals(listOf("before"), probe.markers())
                assertEquals(listOf("before", "committed"), side.open(directory).markers())
                assertNoTemporary(directory, side)
            }
        }
    }
}

private enum class SplitSettingsSide(val fileName: String) {
    Backend("settings.backend.yml"),
    Frontend("settings.frontend.cli.yml"),
    ;

    suspend fun open(
        directory: Path,
        fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    ): SplitStoreProbe = when (this) {
        Backend -> {
            val store = openBackendSettings(directory, backendDefaults(), fileSystem)
            SplitStoreProbe(
                markers = { store.settings.value.contextSources.customSources.map { it.path } },
                append = { marker ->
                    store.update {
                        it.copy(contextSources = it.contextSources.copy(
                            customSources = it.contextSources.customSources + AgentContextCustomSource(marker),
                        ))
                    }
                },
                reload = { store.reload() },
                fail = { failure -> store.update { throw failure } },
            )
        }
        Frontend -> {
            val store = openCliFrontendSettings(directory, fileSystem = fileSystem)
            SplitStoreProbe(
                markers = { store.settings.value.hooks.map { it.name } },
                append = { marker -> store.update { it.copy(hooks = it.hooks + testHook(marker)) } },
                reload = { store.reload() },
                fail = { failure -> store.update { throw failure } },
            )
        }
    }
}

private class SplitStoreProbe(
    val markers: () -> List<String>,
    val append: suspend (String) -> Unit,
    val reload: suspend () -> Unit,
    val fail: suspend (Throwable) -> Unit,
)

internal class FaultingSettingsFileSystem : CoroutineFileSystem by SystemCoroutineFileSystem {
    var afterWrite: suspend () -> Unit = {}
    var beforeMove: suspend () -> Unit = {}
    var afterMove: suspend () -> Unit = {}
    var afterDelete: suspend () -> Unit = {}

    override suspend fun writePrivateString(path: Path, content: String, mustCreate: Boolean) {
        assertTrue(mustCreate)
        SystemCoroutineFileSystem.writePrivateString(path, content, mustCreate)
        afterWrite()
    }

    override suspend fun atomicMove(source: Path, destination: Path) {
        beforeMove()
        SystemCoroutineFileSystem.atomicMove(source, destination)
        afterMove()
    }

    override suspend fun delete(path: Path, mustExist: Boolean) {
        currentCoroutineContext().ensureActive()
        SystemCoroutineFileSystem.delete(path, mustExist)
        afterDelete()
    }
}

internal fun backendDefaults(): BackendSettings = BackendSettings(
    authSource = KodexAuthSource.Codex,
    shell = Shell(ShellType.Sh, Path("/bin/sh")),
    contextSources = AgentContextSourceSettings(),
    newSession = KodexNewSessionSettings(),
    sessionTitle = SessionTitleSettings(),
    mcpServers = emptyMap(),
)

private fun testHook(name: String): NotificationHook = NotificationHook(
    name = name,
    types = setOf(NotificationHookType.StopAssistantMessage, NotificationHookType.StopUnhandledError),
    command = "never-executed",
)

private fun testMcpConfigurations(): Map<String, McpServerConfiguration> = mapOf(
    "remote" to McpServerConfiguration.StreamableHttp(
        url = "https://mcp.example.test",
        headers = mapOf("Authorization" to McpSecret("header-secret")),
        oauth = McpOAuthConfiguration.Initialized(
            client = McpOAuthClient(
                clientId = "test-client",
                clientSecret = McpSecret("client-secret"),
                redirectUri = "http://127.0.0.1:8765/callback",
            ),
            resource = "https://mcp.example.test",
            scopes = listOf("tools.read"),
            resolvedAuthorizationEndpoint = "https://issuer.example.test/authorize",
            resolvedTokenEndpoint = "https://issuer.example.test/token",
            tokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
            accessToken = McpSecret("access-token"),
            refreshToken = McpSecret("refresh-token"),
            expiresAtEpochSeconds = 1_800_000_000,
        ),
    ),
    "local" to McpServerConfiguration.Stdio(
        command = "not-started",
        args = listOf("--flag"),
        environment = mapOf("TEST_SECRET" to McpSecret("environment-secret")),
        workingDirectory = Path("relative-workspace"),
        enabled = false,
    ),
)

internal suspend fun writeFixture(path: Path, text: String) {
    SystemCoroutineFileSystem.createDirectories(requireNotNull(path.parent))
    SystemCoroutineFileSystem.writeString(path, text)
}

private suspend fun assertNoTemporary(directory: Path, side: SplitSettingsSide) {
    val leftovers = SystemCoroutineFileSystem.list(directory).filter {
        it.name.startsWith(".${side.fileName}.") && it.name.endsWith(".tmp")
    }
    assertEquals(emptyList(), leftovers)
}

internal suspend fun withSplitSettingsDirectory(block: suspend (Path) -> Unit) {
    val root = Path(SystemTemporaryDirectory, "kodex-split-settings-${Random.nextLong()}")
    SystemCoroutineFileSystem.createDirectories(root, mustCreate = true)
    try {
        block(Path(root, "settings"))
    } finally {
        withContext(NonCancellable) {
            withTimeout(10.seconds) { deleteSplitSettingsDirectory(root) }
        }
    }
}

private suspend fun deleteSplitSettingsDirectory(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { deleteSplitSettingsDirectory(it) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
