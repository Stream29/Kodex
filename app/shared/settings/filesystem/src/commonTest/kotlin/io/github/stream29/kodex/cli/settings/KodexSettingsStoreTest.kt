package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.hook.contract.HookBody
import io.github.stream29.kodex.hook.contract.HookType
import io.github.stream29.kodex.mcp.contract.McpOAuthClient
import io.github.stream29.kodex.mcp.contract.McpOAuthConfiguration
import io.github.stream29.kodex.mcp.contract.McpOAuthTokenEndpointAuthMethod
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
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
            assertEquals(emptyMap(), store.settings.value.hooks)

            SystemCoroutineFileSystem.writeString(
                Path(codexHome, "config.toml"),
                "this is invalid TOML",
            )
            assertEquals(defaults, store.reload())
        }
    }

    test("writes and restores a complete settings snapshot") {
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
                    requestUserInputMode = RequestUserInputMode.NoQuestion,
                ),
                sessionTitle = SessionTitleSettings(
                    enabled = false,
                    model = OpenAiModelId("title-model"),
                    reasoningEffort = ReasoningEffort.Medium,
                ),
                sidebars = SidebarSettings(
                    left = SidebarContent.HistoryIndex,
                    right = SidebarContent.TerminalSessions,
                    leftWidth = 34,
                    rightWidth = 21,
                ),
                hooks = mapOf(
                    "finish_check" to HookBody(
                        type = HookType.Stop,
                        command = "finish-command",
                    ),
                ),
            )
            val store = openStore(
                root = root,
                defaults = KodexGlobalSettings(codexHome = Path(root, "initial-codex")),
            )

            store.update { expected }

            val yaml = SystemCoroutineFileSystem.readString(settingsPath(root))
            assertFalse("schema_version:" in yaml, yaml)
            assertTrue(yaml.contains("codex_home: ${expected.codexHome}"), yaml)
            assertTrue(yaml.contains("auth_source: kodex"), yaml)
            assertTrue(yaml.contains("service_tier: flex"), yaml)
            assertTrue(yaml.contains("request_user_input_mode: no_question"), yaml)
            assertTrue(yaml.contains("left: history_index"), yaml)
            assertTrue(yaml.contains("right: terminal_sessions"), yaml)
            assertTrue(yaml.contains("left_width: 34"), yaml)
            assertTrue(yaml.contains("right_width: 21"), yaml)
            assertTrue(yaml.contains("mcp_servers: {}"), yaml)
            assertTrue(yaml.contains("hooks:"), yaml)
            assertEquals(emptyList(), temporarySettingsFiles(root))
            assertEquals(expected, openStore(root).settings.value)
        }
    }

    test("sparse sidebar settings use current defaults independently") {
        withSettingsDirectory("sidebar-defaults") { root ->
            val defaults = KodexGlobalSettings(
                codexHome = Path(root, "codex"),
                sidebars = SidebarSettings(
                    left = SidebarContent.None,
                    right = SidebarContent.TerminalSessions,
                    leftWidth = 31,
                    rightWidth = 37,
                ),
            )
            SystemCoroutineFileSystem.writeString(
                settingsPath(root),
                """
                sidebars:
                  left: terminal_sessions
                """.trimIndent() + "\n",
            )

            val loaded = openStore(root, defaults).settings.value

            assertEquals(SidebarContent.TerminalSessions, loaded.sidebars.left)
            assertEquals(SidebarContent.TerminalSessions, loaded.sidebars.right)
            assertEquals(31, loaded.sidebars.leftWidth)
            assertEquals(37, loaded.sidebars.rightWidth)
        }
    }

    test("rejects sidebar widths below the component minimum") {
        withSettingsDirectory("invalid-sidebar-width") { root ->
            SystemCoroutineFileSystem.writeString(
                settingsPath(root),
                """
                sidebars:
                  left_width: 3
                """.trimIndent() + "\n",
            )

            assertFailsWith<IllegalArgumentException> {
                openStore(root)
            }
        }
    }

    test("missing request user input mode uses the current default") {
        withSettingsDirectory("question-default") { root ->
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

    test("normalizes legacy ultra reasoning settings to max") {
        withSettingsDirectory("legacy-ultra") { root ->
            SystemCoroutineFileSystem.writeString(
                settingsPath(root),
                """
                new_session:
                  reasoning_effort: ultra
                session_title:
                  reasoning_effort: ultra
                """.trimIndent() + "\n",
            )
            val store = openStore(root)

            assertEquals(ReasoningEffort.Max, store.settings.value.newSession.reasoningEffort)
            assertEquals(ReasoningEffort.Max, store.settings.value.sessionTitle.reasoningEffort)

            store.update { current -> current }

            val yaml = SystemCoroutineFileSystem.readString(settingsPath(root))
            assertTrue(
                yaml.lineSequence().none { line -> line.trim() == "reasoning_effort: ultra" },
                yaml,
            )
            assertEquals(
                2,
                yaml.lineSequence().count { line -> line.trim() == "reasoning_effort: max" },
                yaml,
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

    test("round trips the ordered native Hook name map") {
        withSettingsDirectory("hooks") { root ->
            val expected = linkedMapOf(
                "guard_tools" to HookBody(
                    type = HookType.PreToolUse,
                    command = "check-command",
                ),
                "verify_stop" to HookBody(
                    type = HookType.Stop,
                    command = "verify-command",
                ),
            )
            val store = openStore(root)

            store.update { settings -> settings.copy(hooks = expected) }

            val reopened = openStore(root)
            assertEquals(expected, reopened.settings.value.hooks)
            val yaml = SystemCoroutineFileSystem.readString(settingsPath(root))
            assertTrue("guard_tools:" in yaml, yaml)
            assertTrue("type: pre_tool_use" in yaml, yaml)
            assertTrue("command: check-command" in yaml, yaml)
            assertFalse("sources:" in yaml, yaml)
            assertFalse("matcher:" in yaml, yaml)
        }
    }

    test("sparse settings use current defaults and ignore unknown keys without rewriting") {
        withSettingsDirectory("tolerant-read") { root ->
            val defaultShell = Shell(ShellType.Bash, Path("/default/bin/bash"))
            val defaults = KodexGlobalSettings(
                codexHome = Path(root, "default-codex"),
                authSource = KodexAuthSource.Kodex,
                shell = defaultShell,
                newSession = KodexNewSessionSettings(
                    model = OpenAiModelId("default-model"),
                    reasoningEffort = ReasoningEffort.High,
                    serviceTier = ServiceTier.Flex,
                    requestUserInputMode = RequestUserInputMode.NoQuestion,
                ),
                sessionTitle = SessionTitleSettings(
                    enabled = false,
                    model = OpenAiModelId("default-title-model"),
                    reasoningEffort = ReasoningEffort.Medium,
                ),
                sidebars = SidebarSettings(
                    left = SidebarContent.None,
                    right = SidebarContent.TerminalSessions,
                ),
                mcpServers = mapOf(
                    "default" to McpServerConfiguration.Stdio(command = "default-server"),
                ),
                hooks = mapOf(
                    "default_hook" to HookBody(
                        type = HookType.Stop,
                        command = "default-command",
                    ),
                ),
            )
            val original = """
                schema_version: 2
                codex_home: ${Path(root, "selected-codex")}
                new_line_key: enter
                new_session:
                  model: configured-model
                  mode: build
                  future_nested_key: ignored
                session_title:
                  enabled: true
                future_section:
                  enabled: true
                """.trimIndent() + "\n"
            SystemCoroutineFileSystem.writeString(
                settingsPath(root),
                original,
            )

            val loaded = openStore(root, defaults).settings.value

            assertEquals(Path(root, "selected-codex"), loaded.codexHome)
            assertEquals(KodexAuthSource.Kodex, loaded.authSource)
            assertEquals(defaultShell, loaded.shell)
            assertEquals(NewLineKey.Enter, loaded.newLineKey)
            assertEquals(OpenAiModelId("configured-model"), loaded.newSession.model)
            assertEquals(ReasoningEffort.High, loaded.newSession.reasoningEffort)
            assertEquals(ServiceTier.Flex, loaded.newSession.serviceTier)
            assertEquals(RequestUserInputMode.NoQuestion, loaded.newSession.requestUserInputMode)
            assertEquals(defaults.sessionTitle.copy(enabled = true), loaded.sessionTitle)
            assertEquals(defaults.sidebars, loaded.sidebars)
            assertEquals(defaults.mcpServers, loaded.mcpServers)
            assertEquals(defaults.hooks, loaded.hooks)
            assertEquals(original, SystemCoroutineFileSystem.readString(settingsPath(root)))
        }
    }

    test("normal updates replace unknown keys with the canonical settings shape") {
        withSettingsDirectory("canonical-update") { root ->
            SystemCoroutineFileSystem.writeString(
                settingsPath(root),
                """
                schema_version: 2
                obsolete_option: retained-until-update
                new_session:
                  model: configured-model
                  mode: plan
                """.trimIndent() + "\n",
            )
            val store = openStore(root)

            val updated = store.update { current ->
                current.copy(newLineKey = NewLineKey.Enter)
            }

            val yaml = SystemCoroutineFileSystem.readString(settingsPath(root))
            assertFalse("schema_version:" in yaml, yaml)
            assertFalse("obsolete_option:" in yaml, yaml)
            assertTrue(
                yaml.lineSequence().none { line -> line.trimStart().startsWith("mode:") },
                yaml,
            )
            assertTrue("new_line_key: enter" in yaml, yaml)
            assertTrue("model: configured-model" in yaml, yaml)
            assertTrue("mcp_servers: {}" in yaml, yaml)
            assertEquals(updated, openStore(root).settings.value)
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

    test("rejects malformed YAML and invalid known settings") {
        withSettingsDirectory("invalid") { root ->
            SystemCoroutineFileSystem.writeString(settingsPath(root), "new_session: [\n")
            assertFailsWith<IllegalArgumentException> { openStore(root) }

            SystemCoroutineFileSystem.writeString(
                settingsPath(root),
                """
                new_session:
                  service_tier: unsupported
                """.trimIndent() + "\n",
            )
            val failure = assertFailsWith<IllegalArgumentException> { openStore(root) }
            assertTrue(failure.message.orEmpty().contains("Unsupported service tier"))
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
