package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.hook.contract.HookCodexImportIdentity
import io.github.stream29.kodex.hook.contract.HookCodexSourceKind
import io.github.stream29.kodex.hook.contract.HookCommandDefinition
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookDeclarations
import io.github.stream29.kodex.hook.contract.HookEnvironmentValue
import io.github.stream29.kodex.hook.contract.HookMatcher
import io.github.stream29.kodex.hook.contract.HookMatcherGroup
import io.github.stream29.kodex.hook.contract.HookSourceConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenEndpointAuthMethod
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

val kodexSettingsStoreTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("missing private settings preserve compiled defaults") {
        withSettingsDirectory("missing") { root ->
            val defaults = KodexGlobalSettings(
                codexHome = Path(root, "codex"),
                newSession = KodexNewSessionSettings(
                    model = OpenAiModelId("compiled-model"),
                ),
            )

            val settings = openStore(root, defaults)

            assertEquals(defaults, settings.settings.value)
            assertFalse(SystemCoroutineFileSystem.exists(settingsPath(root)))
        }
    }

    test("regular loading and reload ignore Codex configuration") {
        withSettingsDirectory("independent") { root ->
            val codexHome = Path(root, "codex")
            SystemCoroutineFileSystem.createDirectories(codexHome)
            SystemCoroutineFileSystem.writeString(
                Path(codexHome, "config.toml"),
                """
                model = "codex-model"

                [mcp_servers.codex]
                url = "https://codex.example.test/mcp"
                """.trimIndent(),
            )
            SystemCoroutineFileSystem.writeString(
                Path(codexHome, "hooks.json"),
                """{"hooks":{"Stop":[{"hooks":[]}]}}""",
            )
            val defaults = KodexGlobalSettings(
                codexHome = codexHome,
                newSession = KodexNewSessionSettings(
                    model = OpenAiModelId("kodex-model"),
                ),
            )
            val store = openStore(root, defaults)

            assertEquals(defaults, store.settings.value)
            assertEquals(emptyMap(), store.settings.value.mcpServers)
            assertEquals(HookConfiguration(), store.settings.value.hooks)

            SystemCoroutineFileSystem.writeString(
                Path(codexHome, "config.toml"),
                "this is invalid TOML",
            )
            assertEquals(defaults, store.reload())
        }
    }

    test("writes and restores a complete schema five snapshot") {
        withSettingsDirectory("restart") { root ->
            val selectedShell = Shell(ShellType.Bash, Path("/custom/bin/bash"))
            val expected = KodexGlobalSettings(
                codexHome = Path(root, "selected-codex"),
                authSource = KodexAuthSource.Kodex,
                shell = selectedShell,
                newLineKey = NewLineKey.Enter,
                newSession = KodexNewSessionSettings(
                    model = OpenAiModelId("session-model"),
                    reasoningEffort = ReasoningEffort.High,
                    serviceTier = ServiceTier.Flex,
                    agentMode = AgentMode.Multi,
                    requestUserInputMode = RequestUserInputMode.NoQuestion,
                ),
                sessionTitle = SessionTitleSettings(
                    enabled = false,
                    model = OpenAiModelId("title-model"),
                    reasoningEffort = ReasoningEffort.Medium,
                ),
                hooks = HookConfiguration(featureEnabled = false),
            )
            val store = openStore(
                root = root,
                defaults = KodexGlobalSettings(codexHome = Path(root, "initial-codex")),
            )

            store.update { expected }

            val yaml = SystemCoroutineFileSystem.readString(settingsPath(root))
            assertTrue(yaml.contains("schema_version: 5"), yaml)
            assertTrue(yaml.contains("codex_home: ${expected.codexHome}"), yaml)
            assertTrue(yaml.contains("auth_source: kodex"), yaml)
            assertTrue(yaml.contains("service_tier: flex"), yaml)
            assertTrue(yaml.contains("request_user_input_mode: no_question"), yaml)
            assertTrue(yaml.contains("mcp_servers: {}"), yaml)
            assertTrue(yaml.contains("hooks:"), yaml)
            assertEquals(emptyList(), temporarySettingsFiles(root))
            assertEquals(expected, openStore(root).settings.value)
        }
    }

    test("schema five without request user input mode defaults to ask user") {
        withSettingsDirectory("schema-five-question-default") { root ->
            val store = openStore(root)
            store.update { settings ->
                settings.copy(
                    newSession = settings.newSession.copy(
                        requestUserInputMode = RequestUserInputMode.NoQuestion,
                    ),
                )
            }
            val legacyText = SystemCoroutineFileSystem.readString(settingsPath(root))
                .lineSequence()
                .filterNot { line -> "request_user_input_mode:" in line }
                .joinToString(separator = "\n", postfix = "\n")
            SystemCoroutineFileSystem.writeString(settingsPath(root), legacyText)

            val reopened = openStore(root)

            assertEquals(
                RequestUserInputMode.AskUser,
                reopened.settings.value.newSession.requestUserInputMode,
            )
        }
    }

    test("clearing MCP settings never falls back to Codex") {
        withSettingsDirectory("clear-mcp") { root ->
            val codexHome = Path(root, "codex")
            SystemCoroutineFileSystem.createDirectories(codexHome)
            SystemCoroutineFileSystem.writeString(
                Path(codexHome, "config.toml"),
                """
                [mcp_servers.codex]
                command = "codex-server"
                """.trimIndent(),
            )
            val store = openStore(
                root,
                KodexGlobalSettings(
                    codexHome = codexHome,
                    mcpServers = mapOf(
                        "compiled" to McpServerConfiguration.Stdio(command = "compiled-server"),
                    ),
                ),
            )

            store.update { current -> current.copy(mcpServers = emptyMap()) }
            store.reload()

            assertEquals(emptyMap(), store.settings.value.mcpServers)
            assertEquals(emptyMap(), openStore(root).settings.value.mcpServers)
        }
    }

    test("round trips HTTP stdio and OAuth credentials") {
        withSettingsDirectory("mcp-credentials") { root ->
            val oauthClient = McpOAuthClient(
                clientId = "client-id",
                clientSecret = McpSecret("client-secret"),
                redirectUri = "http://127.0.0.1:8765/callback",
                authorizationEndpoint = "https://issuer.example.test/authorize",
                tokenEndpoint = "https://issuer.example.test/token",
            )
            val expected = mapOf(
                "docs" to McpServerConfiguration.StreamableHttp(
                    url = "https://docs.example.test/mcp",
                    headers = mapOf("Authorization" to McpSecret("header-secret")),
                    oauth = McpOAuthConfiguration.Initialized(
                        client = oauthClient,
                        resource = "https://docs.example.test",
                        scopes = listOf("tools.read", "tools.call"),
                        resolvedAuthorizationEndpoint =
                            "https://issuer.example.test/authorize",
                        resolvedTokenEndpoint = "https://issuer.example.test/token",
                        tokenEndpointAuthMethod =
                            McpOAuthTokenEndpointAuthMethod.ClientSecretBasic,
                        accessToken = McpSecret("access-token"),
                        refreshToken = McpSecret("refresh-token"),
                        expiresAtEpochSeconds = 1_800_000_000,
                    ),
                ),
                "browser" to McpServerConfiguration.Stdio(
                    command = "browser-mcp",
                    args = listOf("--headless"),
                    environment = mapOf("MCP_TOKEN" to McpSecret("environment-secret")),
                    workingDirectory = Path(root, "workspace"),
                    enabled = false,
                ),
            )
            val store = openStore(root)

            store.update { settings -> settings.copy(mcpServers = expected) }

            val reopened = openStore(root)
            assertEquals(expected, reopened.settings.value.mcpServers)
            val yaml = SystemCoroutineFileSystem.readString(settingsPath(root))
            assertTrue(yaml.contains("access-token"), yaml)
            assertTrue(yaml.contains("refresh-token"), yaml)
            assertFalse("access-token" in reopened.settings.value.mcpServers.toString())
            assertFalse("client-secret" in reopened.settings.value.mcpServers.toString())
            assertFalse("environment-secret" in reopened.settings.value.mcpServers.toString())
        }
    }

    test("round trips Kodex Hook sources with stable identity and environment values") {
        withSettingsDirectory("hook-sources") { root ->
            val expected = HookConfiguration(
                featureEnabled = false,
                sources = listOf(
                    HookSourceConfiguration(
                        id = "stable-source-id",
                        name = "Project checks",
                        enabled = false,
                        importIdentity = HookCodexImportIdentity(
                            sourceKind = HookCodexSourceKind.Project,
                            normalizedPath = Path(root, ".codex", "hooks.json").toString(),
                        ),
                        environment = mapOf(
                            "HOOK_TOKEN" to HookEnvironmentValue("private-hook-token"),
                        ),
                        hooks = HookDeclarations(
                            preToolUse = listOf(
                                HookMatcherGroup(
                                    matcher = HookMatcher.Exact("shell|Bash"),
                                    hooks = listOf(
                                        HookCommandDefinition(
                                            command = "check-command",
                                            timeoutSeconds = 9,
                                            enabled = false,
                                            statusMessage = "Checking",
                                            additionalContextLimit = 1200,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val store = openStore(root)

            store.update { settings -> settings.copy(hooks = expected) }

            val reopened = openStore(root)
            assertEquals(expected, reopened.settings.value.hooks)
            val yaml = SystemCoroutineFileSystem.readString(settingsPath(root))
            assertTrue("stable-source-id" in yaml)
            assertTrue("private-hook-token" in yaml)
            assertFalse("private-hook-token" in reopened.settings.value.hooks.toString())
        }
    }

    test("migrates sparse schema three without inherited MCP or Hooks") {
        withSettingsDirectory("schema-three") { root ->
            val defaults = KodexGlobalSettings(
                codexHome = Path(root, "default-codex"),
                newSession = KodexNewSessionSettings(
                    model = OpenAiModelId("default-model"),
                ),
                mcpServers = mapOf(
                    "legacy-inherited" to McpServerConfiguration.Stdio(command = "legacy"),
                ),
                hooks = HookConfiguration(featureEnabled = false),
            )
            SystemCoroutineFileSystem.writeString(
                settingsPath(root),
                """
                schema_version: 3
                codex_home: ${Path(root, "selected-codex")}
                new_line_key: enter
                new_session:
                  model: migrated-model
                  agent_mode: multi
                """.trimIndent() + "\n",
            )

            val migrated = openStore(root, defaults).settings.value

            assertEquals(Path(root, "selected-codex"), migrated.codexHome)
            assertEquals(NewLineKey.Enter, migrated.newLineKey)
            assertEquals(OpenAiModelId("migrated-model"), migrated.newSession.model)
            assertEquals(AgentMode.Multi, migrated.newSession.agentMode)
            assertEquals(RequestUserInputMode.AskUser, migrated.newSession.requestUserInputMode)
            assertEquals(emptyMap(), migrated.mcpServers)
            assertEquals(HookConfiguration(), migrated.hooks)
            val yaml = SystemCoroutineFileSystem.readString(settingsPath(root))
            assertTrue(yaml.contains("schema_version: 5"), yaml)
            assertTrue(yaml.contains("mcp_servers: {}"), yaml)
        }
    }

    test("migrates schema three Hook layers into stable Kodex sources") {
        withSettingsDirectory("schema-three-hooks") { root ->
            val sourcePath = Path(root, "codex", "hooks.json")
            SystemCoroutineFileSystem.createDirectories(Path(root, "codex"))
            SystemCoroutineFileSystem.writeString(sourcePath, "{}")
            SystemCoroutineFileSystem.writeString(
                settingsPath(root),
                """
                schema_version: 3
                hooks:
                  featureEnabled: false
                  sources:
                    - sourcePath: $sourcePath
                      sourceKind: user
                      environment:
                        TOKEN: legacy-secret
                      description: Legacy checks
                      hooks:
                        Stop:
                          - matcher: shell|Bash
                            hooks:
                              - type: command
                                command: echo ${'$'}{TOKEN}
                                timeout: 7
                """.trimIndent() + "\n",
            )

            val migrated = openStore(root).settings.value.hooks

            assertFalse(migrated.featureEnabled)
            val source = migrated.sources.single()
            assertEquals("migrated-hook-source-1", source.id)
            assertEquals("Legacy checks", source.name)
            assertEquals(
                HookCodexImportIdentity(
                    sourceKind = HookCodexSourceKind.User,
                    normalizedPath = SystemCoroutineFileSystem.resolve(sourcePath).toString(),
                ),
                source.importIdentity,
            )
            assertEquals(HookEnvironmentValue("legacy-secret"), source.environment.getValue("TOKEN"))
            val group = source.hooks.stop.single()
            assertIs<HookMatcher.Exact>(group.matcher)
            assertEquals("echo legacy-secret", group.hooks.single().command)
            assertEquals(7L, group.hooks.single().timeoutSeconds)
            val yaml = SystemCoroutineFileSystem.readString(settingsPath(root))
            assertTrue("schema_version: 5" in yaml)
            assertTrue("migrated-hook-source-1" in yaml)
        }
    }

    test("migrates an early schema four snapshot with the current Hook shape") {
        withSettingsDirectory("schema-four-current-hooks") { root ->
            val expected = HookConfiguration(
                sources = listOf(
                    HookSourceConfiguration(
                        id = "schema-four-source",
                        name = "Schema four",
                        hooks = HookDeclarations(
                            stop = listOf(
                                HookMatcherGroup(
                                    hooks = listOf(HookCommandDefinition("finish")),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            openStore(root).update { settings -> settings.copy(hooks = expected) }
            val schemaFour = SystemCoroutineFileSystem.readString(settingsPath(root))
                .replaceFirst("schema_version: 5", "schema_version: 4")
            SystemCoroutineFileSystem.writeString(settingsPath(root), schemaFour)

            val migrated = openStore(root).settings.value

            assertEquals(expected, migrated.hooks)
            assertTrue(
                "schema_version: 5" in SystemCoroutineFileSystem.readString(settingsPath(root)),
            )
        }
    }

    test("updates from the latest private snapshot") {
        withSettingsDirectory("latest-private") { root ->
            val first = openStore(root)
            first.update { current ->
                current.copy(newSession = current.newSession.copy(model = OpenAiModelId("first")))
            }
            val second = openStore(root)
            second.update { current ->
                current.copy(newSession = current.newSession.copy(model = OpenAiModelId("second")))
            }

            val updated = first.update { current ->
                current.copy(newLineKey = NewLineKey.Enter)
            }

            assertEquals(OpenAiModelId("second"), updated.newSession.model)
            assertEquals(NewLineKey.Enter, updated.newLineKey)
        }
    }

    test("rejects invalid YAML and unsupported schema versions") {
        withSettingsDirectory("invalid") { root ->
            SystemCoroutineFileSystem.writeString(settingsPath(root), "schema_version: nope\n")
            assertFailsWith<IllegalArgumentException> { openStore(root) }

            SystemCoroutineFileSystem.writeString(settingsPath(root), "schema_version: 99\n")
            val failure = assertFailsWith<IllegalArgumentException> { openStore(root) }
            assertTrue(failure.message.orEmpty().contains("schema 99"))
        }
    }

    test("persists selected Codex Home without applying its configuration") {
        withSettingsDirectory("selected-home") { root ->
            val selectedHome = Path(root, "selected-codex")
            SystemCoroutineFileSystem.createDirectories(selectedHome)
            SystemCoroutineFileSystem.writeString(
                Path(selectedHome, "config.toml"),
                "model = \"selected-model\"",
            )
            val store = openStore(
                root,
                KodexGlobalSettings(codexHome = Path(root, "initial-codex")),
            )

            store.update { current -> current.copy(codexHome = selectedHome) }

            val reopened = openStore(root)
            assertEquals(selectedHome, reopened.settings.value.codexHome)
            assertEquals(
                KodexNewSessionSettings().model,
                reopened.settings.value.newSession.model,
            )
            assertFalse(SystemCoroutineFileSystem.exists(Path(selectedHome, "settings.yml")))
        }
    }
}

private fun settingsPath(root: Path): Path = Path(Path(root, "kodex"), "settings.yml")

private suspend fun openStore(
    root: Path,
    defaults: KodexGlobalSettings = KodexGlobalSettings(codexHome = Path(root, "codex")),
): KodexSettingsStore =
    openGlobalSettings(
        settingsDirectory = Path(root, "kodex"),
        defaults = defaults,
    )

private suspend fun withSettingsDirectory(
    label: String,
    block: suspend (Path) -> Unit,
) {
    val root = Path(SystemTemporaryDirectory, "kodex-settings-$label-${Random.nextLong()}")
    SystemCoroutineFileSystem.createDirectories(Path(root, "kodex"))
    try {
        block(root)
    } finally {
        deleteSettingsDirectory(root)
    }
}

private suspend fun temporarySettingsFiles(root: Path): List<Path> =
    SystemCoroutineFileSystem.list(Path(root, "kodex")).filter { path ->
        path.name.startsWith(".settings.yml.") && path.name.endsWith(".tmp")
    }

private suspend fun deleteSettingsDirectory(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteSettingsDirectory(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
