package io.github.stream29.kodex.app.migration.v0_4_7

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.migration.*
import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.mcp.contract.*
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

val migrateToV0_4_7Test by testSuite(compartment = { TestCompartment.RealTime }) {
    testFixture {
        Path(SystemTemporaryDirectory, "kodex-migration-0.4.7-${Random.nextLong()}").also { fs.createDirectories(it) }
    } closeWith { withContext(NonCancellable) { remove(this@closeWith) } } asParameterForEach {
        test("splits every retained field and credentials without old hooks or widths") { home ->
            fs.writeString(Path(home, "settings.yml"), LegacySettingsFixture)
            fs.writeString(Path(home, "unknown.txt"), "keep")
            val expected = retainedFixtureBackend()
            migrateToV0_4_7(home, fs)
            assertFalse(fs.exists(Path(home, "settings.yml")))
            assertEquals(expected, openBackendSettings(home, backendDefaults()).settings.value)
            val frontend = openCliFrontendSettings(home).settings.value
            assertEquals(NewLineKey.Enter, frontend.newLineKey)
            assertEquals(SidebarContent.None, frontend.sidebars.left)
            assertEquals(SidebarContent.HistoryIndex, frontend.sidebars.right)
            assertTrue(frontend.hooks.isEmpty())
            val encoded = fs.readString(Path(home, "settings.frontend.cli.yml"))
            assertFalse("left_width" in encoded)
            assertFalse("should-never-run" in encoded)
            assertFalse("fixture-secret" in encoded)
            assertEquals("keep", fs.readString(Path(home, "unknown.txt")))
            assertNoTemporary(home)
        }
        test("sparse legacy nulls use frozen defaults without probing a host shell") { home ->
            fs.writeString(Path(home, "settings.yml"), """
                auth_source: null
                shell: null
                new_session: {model: null, service_tier: priority}
                context_sources: {agents_home: null}
                hooks: null
            """.trimIndent())
            migrateToV0_4_7(home, fs)
            val encoded = fs.readString(Path(home, "settings.backend.yml"))
            assertFalse("shell:" in encoded)
            assertTrue("gpt-5.6-sol" in encoded)
            val defaults = backendDefaults()
            val backend = openBackendSettings(home, defaults).settings.value
            assertEquals(KodexAuthSource.Codex, backend.authSource)
            assertEquals(defaults.shell, backend.shell)
            assertEquals("priority", backend.newSession.serviceTier.requestValue)
        }
        test("uninitialized OAuth and YAML-ambiguous credential strings retain their original values") { home ->
            fs.writeString(Path(home, "settings.yml"), """
                mcp_servers:
                  pending:
                    type: streamable_http
                    url: https://fixture.invalid/mcp
                    headers: {flag: "false", number: "0001", absent: "null", empty: ""}
                    oauth:
                      type: uninitialized
                      client: {}
                      scopes: ["null", "true"]
            """.trimIndent())
            migrateToV0_4_7(home, fs)
            val expected = backendDefaults().copy(
                mcpServers = mapOf("pending" to McpServerConfiguration.StreamableHttp(
                    url = "https://fixture.invalid/mcp",
                    headers = mapOf(
                        "flag" to McpSecret("false"), "number" to McpSecret("0001"),
                        "absent" to McpSecret("null"), "empty" to McpSecret(""),
                    ),
                    oauth = McpOAuthConfiguration.Uninitialized(
                        client = McpOAuthClient(), scopes = listOf("null", "true"),
                    ),
                )),
            )
            assertEquals(expected, openBackendSettings(home, backendDefaults()).settings.value)
        }
        test("new Home has no settings files and migration writes no defaults") { home ->
            migrateToV0_4_7(home, fs)
            assertTrue(fs.list(home).isEmpty())
        }
        for (boundary in listOf("backend", "frontend", "delete-before", "delete-after")) {
            test("resumes after $boundary interruption without repeating completed writes") { home ->
                fs.writeString(Path(home, "settings.yml"), LegacySettingsFixture)
                fs.writeString(Path(home, "version.json"), "\"0.4.6\"")
                var interrupted = false
                val faulty = object : CoroutineFileSystem by fs {
                    override suspend fun atomicMove(source: Path, destination: Path) {
                        fs.atomicMove(source, destination)
                        if (!interrupted && (
                                boundary == "backend" && destination.name == "settings.backend.yml" ||
                                    boundary == "frontend" && destination.name == "settings.frontend.cli.yml"
                                )) {
                            interrupted = true
                            throw IOException("Injected interruption")
                        }
                    }
                    override suspend fun delete(path: Path, mustExist: Boolean) {
                        if (!interrupted && path.name == "settings.yml" && boundary == "delete-before") {
                            interrupted = true
                            throw IOException("Injected deletion failure")
                        }
                        fs.delete(path, mustExist)
                        if (!interrupted && path.name == "settings.yml" && boundary == "delete-after") {
                            interrupted = true
                            throw IOException("Injected lost deletion completion")
                        }
                    }
                }
                assertFailsWith<IOException> {
                    prepareKodexHome(home, MigrationVersion("0.4.7"), KodexHomeMigrations, faulty).closeAndJoin()
                }
                assertTrue(interrupted)
                assertEquals("\"0.4.6\"", fs.readString(Path(home, "version.json")))
                val existing = listOf("settings.backend.yml", "settings.frontend.cli.yml").map { Path(home, it) }
                    .filter { fs.exists(it) }.associateWith { fs.readString(it) }
                val resumed = object : CoroutineFileSystem by fs {
                    override suspend fun atomicMove(source: Path, destination: Path) {
                        assertFalse(destination in existing)
                        fs.atomicMove(source, destination)
                    }
                }
                prepareKodexHome(home, MigrationVersion("0.4.7"), KodexHomeMigrations, resumed).closeAndJoin()
                assertEquals("\"0.4.7\"", fs.readString(Path(home, "version.json")))
                existing.forEach { (path, contents) -> assertEquals(contents, fs.readString(path)) }
                assertFalse(fs.exists(Path(home, "settings.yml")))
                assertNoTemporary(home)
            }
        }
        test("existing consistent targets are retained byte-for-byte before deleting the source") { home ->
            fs.writeString(Path(home, "settings.yml"), LegacySettingsFixture)
            migrateToV0_4_7(home, fs)
            val backend = fs.readString(Path(home, "settings.backend.yml"))
            val frontend = fs.readString(Path(home, "settings.frontend.cli.yml"))
            fs.writeString(Path(home, "settings.yml"), LegacySettingsFixture)
            migrateToV0_4_7(home, fs)
            assertEquals(backend, fs.readString(Path(home, "settings.backend.yml")))
            assertEquals(frontend, fs.readString(Path(home, "settings.frontend.cli.yml")))
        }
        test("semantic reentry ignores map order and normalizes existing legacy aliases without rewriting") { home ->
            fs.writeString(Path(home, "settings.yml"), """
                new_session: {reasoning_effort: ultra, service_tier: fast}
            """.trimIndent())
            fs.writeString(Path(home, "settings.backend.yml"), """
                # Retain the existing target verbatim.
                new_session: {service_tier: priority, reasoning_effort: max}
            """.trimIndent())
            fs.writeString(Path(home, "settings.frontend.cli.yml"), "{}")
            val target = fs.readString(Path(home, "settings.backend.yml"))
            migrateToV0_4_7(home, fs)
            assertFalse(fs.exists(Path(home, "settings.yml")))
            assertEquals(target, fs.readString(Path(home, "settings.backend.yml")))
            assertEquals("{}", fs.readString(Path(home, "settings.frontend.cli.yml")))
        }
        for (file in listOf("settings.backend.yml", "settings.frontend.cli.yml")) {
            test("missing source with only $file is ambiguous") { home ->
                fs.writeString(Path(home, file), "{}")
                assertFailsWith<IOException> { migrateToV0_4_7(home, fs) }
                assertEquals(listOf(file), fs.list(home).map { it.name })
            }
        }
        test("missing source with both valid targets permits version completion") { home ->
            fs.writeString(Path(home, "settings.backend.yml"), "{}")
            fs.writeString(Path(home, "settings.frontend.cli.yml"), """
                hooks:
                  - name: retained
                    types: [stop_unhandled_error]
                    command: echo retained
            """.trimIndent())
            migrateToV0_4_7(home, fs)
            assertEquals(1, openCliFrontendSettings(home).settings.value.hooks.size)
        }
        test("conflicting existing target prevents both missing-side writes and source deletion") { home ->
            fs.writeString(Path(home, "settings.yml"), LegacySettingsFixture)
            fs.writeString(Path(home, "settings.frontend.cli.yml"), "new_line_key: shift_enter")
            assertFailsWith<IOException> { migrateToV0_4_7(home, fs) }
            assertFalse(fs.exists(Path(home, "settings.backend.yml")))
            assertEquals(LegacySettingsFixture, fs.readString(Path(home, "settings.yml")))
            assertEquals("new_line_key: shift_enter", fs.readString(Path(home, "settings.frontend.cli.yml")))
        }
        for (bad in listOf(
            "new_line_key: invalid", "sidebars: {left_width: 1}", "new_session: {model: ''}",
            "hooks: {bad: {type: unknown, command: echo}}", "mcp_servers: {s: {type: streamable_http, url: ''}}",
            "context_sources: {custom_sources: null, agents_home: not-a-boolean}", "not: [valid",
        )) {
            test("invalid legacy input ${bad.substringBefore(':')} is not defaulted or overwritten ${bad.length}") { home ->
                fs.writeString(Path(home, "settings.yml"), bad)
                assertFailsWith<IOException> { migrateToV0_4_7(home, fs) }
                assertEquals(bad, fs.readString(Path(home, "settings.yml")))
                assertFalse(fs.exists(Path(home, "settings.backend.yml")))
                assertFalse(fs.exists(Path(home, "settings.frontend.cli.yml")))
            }
        }
        test("a damaged target or explicit target null is rejected without overwriting it") { home ->
            fs.writeString(Path(home, "settings.yml"), LegacySettingsFixture)
            fs.writeString(Path(home, "settings.backend.yml"), "auth_source: null")
            val error = assertFailsWith<IOException> { migrateToV0_4_7(home, fs) }
            assertFalse(error.toString().contains("fixture-secret"))
            assertFalse(fs.exists(Path(home, "settings.frontend.cli.yml")))
        }
        test("missing source does not turn two targets into permission to ignore invalid hook types") { home ->
            fs.writeString(Path(home, "settings.backend.yml"), "{}")
            val damaged = "hooks: [{name: broken, types: [], command: echo}]"
            fs.writeString(Path(home, "settings.frontend.cli.yml"), damaged)
            assertFailsWith<IOException> { migrateToV0_4_7(home, fs) }
            assertEquals(damaged, fs.readString(Path(home, "settings.frontend.cli.yml")))
        }
        test("private temporary write failure and cancellation leave the source recoverable") { home ->
            fs.writeString(Path(home, "settings.yml"), LegacySettingsFixture)
            val fault = object : CoroutineFileSystem by fs {
                override suspend fun writePrivateString(path: Path, content: String, mustCreate: Boolean) {
                    fs.writePrivateString(path, content, mustCreate)
                    throw CancellationException("cancel before publication")
                }
            }
            assertFailsWith<CancellationException> { migrateToV0_4_7(home, fault) }
            assertFalse(fs.exists(Path(home, "settings.backend.yml")))
            assertNoTemporary(home)
            migrateToV0_4_7(home, fs)
            assertFalse(fs.exists(Path(home, "settings.yml")))
        }
        test("0.4.6 ignores the future entry and keeps the old loader source intact") { home ->
            fs.writeString(Path(home, "settings.yml"), LegacySettingsFixture)
            fs.writeString(Path(home, "version.json"), "\"0.4.5\"")
            prepareKodexHome(home, MigrationVersion("0.4.6"), KodexHomeMigrations, fs).closeAndJoin()
            assertEquals(LegacySettingsFixture, fs.readString(Path(home, "settings.yml")))
            assertFalse(fs.exists(Path(home, "settings.backend.yml")))
            assertEquals("\"0.4.6\"", fs.readString(Path(home, "version.json")))
        }
        test("multi-version upgrade applies the frozen registry before opening the new stores") { home ->
            fs.writeString(Path(home, "version.json"), "\"0.3.2\"")
            fs.writeString(Path(home, "settings.yml"), LegacySettingsFixture)
            val applied = mutableListOf<String>()
            prepareKodexHome(
                home, MigrationVersion("0.4.7"), KodexHomeMigrations, fs,
                onMigrationStarted = { _, next -> applied += next.toString() },
            ).closeAndJoin()
            assertEquals(listOf("0.3.3", "0.3.5", "0.4.3", "0.4.5", "0.4.7"), applied)
            assertEquals(KodexAuthSource.Kodex, openBackendSettings(home, backendDefaults()).settings.value.authSource)
            assertEquals(NewLineKey.Enter, openCliFrontendSettings(home).settings.value.newLineKey)
            assertTrue(fs.exists(Path(home, "skills", "kodex-home", "SKILL.md")))
            assertFalse(fs.exists(Path(home, "settings.yml")))
        }
    }
}

private val fs = SystemCoroutineFileSystem
// Independent expectations, not values decoded by the migration under test or a retired loader.
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
        agentsHomeEnabled = false, workingDirectoryEnabled = false,
        customSources = listOf(AgentContextCustomSource("~/context", enabled = false)),
    ),
    newSession = KodexNewSessionSettings(
        model = OpenAiModelId("fixture-model"), reasoningEffort = ReasoningEffort.Max,
        serviceTier = ServiceTier.Fast, requestUserInputMode = RequestUserInputMode.NoQuestion,
    ),
    sessionTitle = SessionTitleSettings(
        enabled = false, model = OpenAiModelId("title-model"),
        reasoningEffort = ReasoningEffort.Custom("custom-effort"),
    ),
    mcpServers = mapOf(
        "http" to McpServerConfiguration.StreamableHttp(
            url = "https://fixture.invalid/mcp",
            headers = mapOf("Authorization" to McpSecret("fixture-header")),
            oauth = McpOAuthConfiguration.Initialized(
                client = McpOAuthClient(
                    clientId = "fixture-client", clientSecret = McpSecret("fixture-secret"),
                    redirectUri = "http://127.0.0.1:8765/callback",
                ),
                resource = "fixture-resource", scopes = listOf("read", "write"),
                resolvedAuthorizationEndpoint = "https://fixture.invalid/authorize",
                resolvedTokenEndpoint = "https://fixture.invalid/token",
                tokenEndpointAuthMethod = McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
                accessToken = McpSecret("fixture-access"), refreshToken = McpSecret("fixture-refresh"),
                tokenType = "Bearer", expiresAtEpochSeconds = 9999999999L,
            ),
            enabled = false,
        ),
        "process" to McpServerConfiguration.Stdio(
            command = "fixture-command", args = listOf("--flag", "value"),
            environment = mapOf("KEY" to McpSecret("fixture-value")),
            workingDirectory = Path("relative/work"), enabled = false,
        ),
    ),
)
private suspend fun assertNoTemporary(home: Path) {
    assertTrue(fs.list(home).none { it.name.endsWith(".tmp") })
}
private suspend fun remove(path: Path) {
    val metadata = fs.metadataOrNull(path) ?: return
    if (metadata.isDirectory) fs.list(path).forEach { remove(it) }
    fs.delete(path, mustExist = false)
}
