package io.github.stream29.kodex.app.migration

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.app.migration.v0_4_7.LegacySettingsFixture
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

/** End-to-end registry upgrade from pre-split legacy `settings.yml` through `0.4.15` into [UnifiedSettingsStore]. */
val migrateToV047CurrentStoresTest by testSuite(compartment = { TestCompartment.RealTime }) {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-migration-0.4.7-${Random.nextLong()}").also { fs.createDirectories(it) }
    } closeWith { withContext(NonCancellable) { remove(this@closeWith) } } asParameterForEach {
        test("multi-version upgrade from 0.3.2 through 0.4.15 opens UnifiedSettingsStore with every retained field") { home ->
            fs.writeString(Path(home, "version.json"), "\"0.3.2\"")
            fs.writeString(Path(home, "settings.yml"), LegacySettingsFixture)
            fs.writeString(Path(home, "unknown.txt"), "keep")
            val applied = mutableListOf<String>()
            prepareKodexHome(
                home,
                MigrationVersion("0.4.15"),
                KodexHomeMigrations,
                fs,
                onMigrationStarted = { _, next -> applied += next.toString() },
            ).closeAndJoin()
            assertEquals(listOf("0.3.3", "0.3.5", "0.4.3", "0.4.5", "0.4.7", "0.4.15"), applied)

            val unified = UnifiedSettingsStore.open(home, backendDefaults(), fs).current
            assertEquals(retainedFixtureBackend(), unified.backend)
            assertEquals(NewLineKey.Enter, unified.frontend.newLineKey)
            assertEquals(SidebarContent.None, unified.frontend.sidebars.left)
            assertEquals(SidebarContent.HistoryIndex, unified.frontend.sidebars.right)
            assertTrue(unified.frontend.hooks.isEmpty())

            val encoded = fs.readString(Path(home, "settings.yml"))
            assertFalse("left_width" in encoded)
            assertFalse("should-never-run" in encoded)
            assertFalse(fs.exists(Path(home, "settings.backend.yml")))
            assertFalse(fs.exists(Path(home, "settings.frontend.cli.yml")))
            assertTrue(fs.exists(Path(home, "skills", "kodex-home", "SKILL.md")))
            assertEquals("keep", fs.readString(Path(home, "unknown.txt")))
            assertTrue(fs.list(home).none { it.name.endsWith(".tmp") })
        }
    }
}

private val fs = SystemCoroutineFileSystem

private fun backendDefaults(): BackendSettings = BackendSettings(
    authSource = KodexAuthSource.Codex,
    shell = Shell(ShellType.Bash, Path("/fixture/bash")),
    contextSources = AgentContextSourceSettings(),
    newSession = KodexNewSessionSettings(),
    sessionTitle = SessionTitleSettings(),
    mcpServers = emptyMap(),
)

private fun retainedFixtureBackend(): BackendSettings = backendDefaults().copy(
    authSource = KodexAuthSource.Kodex,
    shell = Shell(ShellType.Bash, Path("/bin/bash")),
    contextSources = AgentContextSourceSettings(
        agentsHomeEnabled = false,
        workingDirectoryEnabled = false,
        customSources = listOf(AgentContextCustomSource("~/context", enabled = false)),
    ),
    newSession = KodexNewSessionSettings(
        model = OpenAiModelId("fixture-model"),
        reasoningEffort = ReasoningEffort.Max,
        serviceTier = ServiceTier.Fast,
        requestUserInputMode = RequestUserInputMode.NoQuestion,
    ),
    sessionTitle = SessionTitleSettings(
        enabled = false,
        model = OpenAiModelId("title-model"),
        reasoningEffort = ReasoningEffort.Custom("custom-effort"),
    ),
    mcpServers = mapOf(
        "http" to McpServerConfiguration.StreamableHttp(
            url = "https://fixture.invalid/mcp",
            headers = mapOf("Authorization" to McpSecret("fixture-header")),
            oauth = McpOAuthConfiguration.Initialized(
                client = McpOAuthClient(
                    clientId = "fixture-client",
                    clientSecret = McpSecret("fixture-secret"),
                    redirectUri = "http://127.0.0.1:8765/callback",
                ),
                resource = "fixture-resource",
                scopes = listOf("read", "write"),
                resolvedAuthorizationEndpoint = "https://fixture.invalid/authorize",
                resolvedTokenEndpoint = "https://fixture.invalid/token",
                tokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
                accessToken = McpSecret("fixture-access"),
                refreshToken = McpSecret("fixture-refresh"),
                tokenType = "Bearer",
                expiresAtEpochSeconds = 9999999999L,
            ),
            enabled = false,
        ),
        "process" to McpServerConfiguration.Stdio(
            command = "fixture-command",
            args = listOf("--flag", "value"),
            environment = mapOf("KEY" to McpSecret("fixture-value")),
            workingDirectory = Path("relative/work"),
            enabled = false,
        ),
    ),
)

private suspend fun remove(path: Path) {
    val metadata = fs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fs.list(path).forEach { remove(it) }
    fs.delete(path, mustExist = false)
}
