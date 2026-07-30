package io.github.stream29.codex.lite.cli.settings

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.hook.contract.HookConfiguration
import io.github.stream29.codex.lite.mcp.contract.McpServerConfiguration
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.openai.ServiceTier
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookDeclarations
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookHandler
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookLayer
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookMatcher
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookMatcherGroup
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookSourceKind
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

val codexLiteSettingsStoreTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("missing config and override files preserve compiled defaults") {
        withSettingsDirectory("missing") { root ->
            val storage = CodexCliStorage(root)
            val defaults = CodexGlobalSettings(codexHome = root)

            val settings = storage.openGlobalSettings(root, defaults)

            assertEquals(defaults, settings.settings.value)
            assertFalse(SystemCoroutineFileSystem.exists(globalSettingsPath(root)))
        }
    }

    test("inherits the Codex Home config") {
        withSettingsDirectory("layers") { root ->
            val storage = CodexCliStorage(root)
            SystemCoroutineFileSystem.writeString(
                configPath(root),
                """
                model = "user-model"
                model_reasoning_effort = "low"
                service_tier = "flex"
                future_native_setting = true

                [tui.keymap.composer]
                submit = "ctrl-enter"

                [tui.keymap.editor]
                insert_newline = "enter"

                [mcp_servers.docs]
                url = "https://user.example.test/mcp"
                enabled = true

                [mcp_servers.docs.http_headers]
                X-User = "one"
                """.trimIndent(),
            )
            val store = storage.openGlobalSettings(root)
            val effective = store.settings.value

            assertEquals(OpenAiModelId("user-model"), effective.newSession.model)
            assertEquals(ReasoningEffort.Low, effective.newSession.reasoningEffort)
            assertEquals(ServiceTier.Flex, effective.newSession.serviceTier)
            assertEquals(NewLineKey.Enter, effective.newLineKey)
            assertEquals(
                McpServerConfiguration.StreamableHttp(
                    url = "https://user.example.test/mcp",
                    headers = mapOf("X-User" to "one"),
                    enabled = true,
                ),
                effective.mcpServers.getValue("docs"),
            )
        }
    }

    test("inherits supported settings and retains native Hook configuration") {
        withSettingsDirectory("native-shape") { root ->
            val storage = CodexCliStorage(root)
            SystemCoroutineFileSystem.writeString(
                configPath(root),
                """
                approval_policy = "never"
                sandbox_mode = "danger-full-access"
                model = "configured-model"
                model_reasoning_effort = "high"
                service_tier = "fast"

                [projects."/workspace/one"]
                trust_level = "trusted"

                [tui]
                notifications = ["agent-turn-complete"]

                [tui.model_availability_nux]
                "configured-model" = 4

                [mcp_servers.idea]
                url = "http://127.0.0.1:64342/sse"

                [mcp_servers.browser]
                command = "browser-mcp"
                args = ["--headless"]

                [[hooks.Stop]]

                [[hooks.Stop.hooks]]
                type = "command"
                command = "notify"
                timeout = 10
                """.trimIndent(),
            )

            val effective = storage.openGlobalSettings(root).settings.value

            assertEquals(OpenAiModelId("configured-model"), effective.newSession.model)
            assertEquals(ReasoningEffort.High, effective.newSession.reasoningEffort)
            assertEquals(ServiceTier.Fast, effective.newSession.serviceTier)
            val idea = assertIs<McpServerConfiguration.StreamableHttp>(effective.mcpServers.getValue("idea"))
            assertEquals("http://127.0.0.1:64342/sse", idea.url)
            val browser = assertIs<McpServerConfiguration.Stdio>(effective.mcpServers.getValue("browser"))
            assertEquals("browser-mcp", browser.command)
            assertEquals(listOf("--headless"), browser.args)
            val hookSource = effective.hooks.sources.single()
            assertEquals(configPath(root), hookSource.sourcePath)
            val command = assertIs<CodexCliHookHandler.Command>(
                hookSource.hooks.stop.single().hooks.single(),
            )
            assertEquals("notify", command.command)
        }
    }

    test("reloads settings inherited from Codex Home and the project") {
        withSettingsDirectory("hook-refresh") { root ->
            val workingDirectory = Path(root, "workspace")
            val projectCodexDirectory = Path(workingDirectory, ".codex")
            SystemCoroutineFileSystem.createDirectories(projectCodexDirectory)
            val userHooks = Path(root, "hooks.json")
            val projectConfig = Path(projectCodexDirectory, "config.toml")
            SystemCoroutineFileSystem.writeString(userHooks, """{"hooks":{}}""")
            SystemCoroutineFileSystem.writeString(
                projectConfig,
                """
                [[hooks.PreToolUse]]
                matcher = "initial"

                [[hooks.PreToolUse.hooks]]
                type = "command"
                command = "initial-command"
                """.trimIndent(),
            )
            val store: CodexGlobalSettingsStore = CodexCliStorage(root).openGlobalSettings(
                settingsDirectory = Path(root, "codexlite"),
                workingDirectory = workingDirectory,
                defaults = CodexGlobalSettings(codexHome = root),
            )

            val initialSources = store.settings.value.hooks.sources
            assertEquals(
                listOf(CodexCliHookSourceKind.User, CodexCliHookSourceKind.Project),
                initialSources.map(CodexCliHookLayer::sourceKind),
            )
            assertEquals(userHooks, initialSources[0].sourcePath)
            assertEquals(projectConfig, initialSources[1].sourcePath)

            SystemCoroutineFileSystem.writeString(
                projectConfig,
                """
                [[hooks.PreToolUse]]
                matcher = "updated"

                [[hooks.PreToolUse.hooks]]
                type = "command"
                command = "updated-command"
                """.trimIndent(),
            )
            SystemCoroutineFileSystem.writeString(
                configPath(root),
                "model = \"updated-model\"",
            )
            store.reload()

            val refreshedProjectSource = store.settings.value.hooks.sources.last()
            assertEquals(
                "updated",
                refreshedProjectSource.hooks.preToolUse.single().matcher.pattern,
            )
            assertEquals(
                OpenAiModelId("updated-model"),
                store.settings.value.newSession.model,
            )
        }
    }

    test("persists a complete Hook override instead of following later Codex changes") {
        withSettingsDirectory("hook-override") { root ->
            val codexHooks = Path(root, "hooks.json")
            SystemCoroutineFileSystem.writeString(codexHooks, """{"hooks":{"Stop":[]}}""")
            val store = CodexCliStorage(root).openGlobalSettings(root)
            val override = HookConfiguration(
                featureEnabled = false,
                sources = listOf(
                    CodexCliHookLayer(
                        sourcePath = Path(root, "codexlite/custom-hooks.json"),
                        sourceKind = CodexCliHookSourceKind.Session,
                        environment = mapOf("CODEX_LITE_HOOK" to "override"),
                        hooks = CodexCliHookDeclarations(
                            userPromptSubmit = listOf(
                                CodexCliHookMatcherGroup(
                                    matcher = CodexCliHookMatcher.RegularExpression("^custom$"),
                                    hooks = listOf(
                                        CodexCliHookHandler.Command(
                                            command = "custom-command",
                                            timeoutSeconds = 5,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            store.update { current -> current.copy(hooks = override) }
            val yaml = SystemCoroutineFileSystem.readString(globalSettingsPath(root))
            assertTrue(yaml.contains("hooks:"), yaml)
            assertTrue(yaml.contains("custom-command"), yaml)

            SystemCoroutineFileSystem.writeString(codexHooks, """{"hooks":{"Stop":[{"matcher":"changed"}]}}""")
            store.reload()
            assertEquals(override, store.settings.value.hooks)

            val reopened = CodexCliStorage(root).openGlobalSettings(root)
            assertEquals(override, reopened.settings.value.hooks)
            assertTrue(
                reopened.settings.value.hooks.sources
                    .single()
                    .hooks
                    .userPromptSubmit
                    .single()
                    .matcher
                    .matches(listOf("custom")),
            )

            reopened.update { current ->
                current.copy(hooks = HookConfiguration())
            }
            reopened.reload()
            assertEquals(HookConfiguration(), reopened.settings.value.hooks)
        }
    }

    test("writes sparse YAML and restores effective settings after restart") {
        withSettingsDirectory("restart") { root ->
            val storage = CodexCliStorage(root)
            SystemCoroutineFileSystem.writeString(
                configPath(root),
                """
                model = "inherited-model"
                model_reasoning_effort = "medium"
                service_tier = "flex"
                """.trimIndent(),
            )
            val first = storage.openGlobalSettings(root)
            val selectedShell = Shell(ShellType.Bash, Path("/custom/bin/bash"))
            val expected = first.update { current ->
                current.copy(
                    authSource = CodexAuthSource.CodexLite,
                    shell = selectedShell,
                    newLineKey = NewLineKey.Enter,
                    newSession = current.newSession.copy(
                        model = OpenAiModelId("override-model"),
                        mode = ModeKind.Plan,
                    ),
                    sessionTitle = SessionTitleSettings(
                        enabled = false,
                        model = OpenAiModelId("title-model"),
                        reasoningEffort = ReasoningEffort.High,
                    ),
                    mcpServers = emptyMap(),
                )
            }

            val yaml = SystemCoroutineFileSystem.readString(globalSettingsPath(root))
            assertTrue(yaml.contains("schema_version: 2"), yaml)
            assertTrue(yaml.contains("auth_source: codex-lite"), yaml)
            assertTrue(yaml.contains("shell: /custom/bin/bash"), yaml)
            assertTrue(yaml.contains("model: override-model"), yaml)
            assertTrue(yaml.contains("mode: plan"), yaml)
            assertTrue(yaml.contains("session_title:"), yaml)
            assertTrue(yaml.contains("enabled: false"), yaml)
            assertTrue(yaml.contains("model: title-model"), yaml)
            assertTrue(yaml.contains("reasoning_effort: high"), yaml)
            assertTrue(yaml.contains("codex_home: $root"), yaml)
            assertFalse(yaml.contains("service_tier"), yaml)
            assertEquals(emptyList(), temporarySettingsFiles(root))

            SystemCoroutineFileSystem.writeString(
                globalSettingsPath(root),
                yaml + "future_setting: true\n",
            )
            val reopened = CodexCliStorage(root).openGlobalSettings(root)

            assertEquals(expected, reopened.settings.value)
        }
    }

    test("persists HTTP and stdio MCP transport overrides") {
        withSettingsDirectory("mcp-transports") { root ->
            val storage = CodexCliStorage(root)
            val store = storage.openGlobalSettings(root)
            val expected = mapOf(
                "docs" to McpServerConfiguration.StreamableHttp(
                    url = "https://docs.example.test/mcp",
                    headers = mapOf("Authorization" to "Bearer test"),
                ),
                "browser" to McpServerConfiguration.Stdio(
                    command = "browser-mcp",
                    args = listOf("--headless"),
                    environment = mapOf("MCP_TOKEN" to "test-token"),
                    workingDirectory = Path(root, "workspace"),
                ),
            )

            store.update { settings -> settings.copy(mcpServers = expected) }

            assertEquals(expected, storage.openGlobalSettings(root).settings.value.mcpServers)
            val yaml = SystemCoroutineFileSystem.readString(globalSettingsPath(root))
            assertTrue(yaml.contains("""type: "streamable_http""""), yaml)
            assertTrue(yaml.contains("""type: "stdio""""), yaml)
        }
    }

    test("persists the selected authentication source") {
        withSettingsDirectory("auth-source") { root ->
            val storage = CodexCliStorage(root)
            val store = storage.openGlobalSettings(root)

            val updated = store.update { current ->
                current.copy(authSource = CodexAuthSource.CodexLite)
            }

            assertEquals(CodexAuthSource.CodexLite, updated.authSource)
            assertEquals(CodexAuthSource.CodexLite, store.settings.value.authSource)
            assertEquals(
                CodexAuthSource.CodexLite,
                CodexCliStorage(root).openGlobalSettings(root).settings.value.authSource,
            )
            assertTrue(
                SystemCoroutineFileSystem.readString(globalSettingsPath(root))
                    .contains("auth_source: codex-lite"),
            )
        }
    }

    test("updates against the latest inherited settings") {
        withSettingsDirectory("latest-inherited") { root ->
            val storage = CodexCliStorage(root)
            SystemCoroutineFileSystem.writeString(configPath(root), "model = \"first-model\"")
            val store = storage.openGlobalSettings(root)
            SystemCoroutineFileSystem.writeString(configPath(root), "model = \"second-model\"")

            val updated = store.update { current ->
                current.copy(newLineKey = NewLineKey.Enter)
            }

            assertEquals(OpenAiModelId("second-model"), updated.newSession.model)
            assertEquals(OpenAiModelId("second-model"), store.settings.value.newSession.model)
            assertFalse(
                SystemCoroutineFileSystem.readString(globalSettingsPath(root)).contains("model:"),
            )
        }
    }

    test("clears a persisted session title model") {
        withSettingsDirectory("clear-title-model") { root ->
            val storage = CodexCliStorage(root)
            val store = storage.openGlobalSettings(root)
            store.update { current ->
                current.copy(
                    sessionTitle = current.sessionTitle.copy(
                        model = OpenAiModelId("title-model"),
                    ),
                )
            }

            val cleared = store.update { current ->
                current.copy(
                    sessionTitle = current.sessionTitle.copy(model = null),
                )
            }

            assertEquals(null, cleared.sessionTitle.model)
            assertEquals(null, store.settings.value.sessionTitle.model)
            val reopened = storage.openGlobalSettings(root)
            assertEquals(null, reopened.settings.value.sessionTitle.model)
        }
    }

    test("rejects invalid native TOML") {
        withSettingsDirectory("invalid-toml") { root ->
            val storage = CodexCliStorage(root)
            SystemCoroutineFileSystem.writeString(configPath(root), "model = [")

            assertFails {
                storage.openGlobalSettings(root)
            }
        }
    }

    test("rejects invalid YAML and unsupported schema versions") {
        withSettingsDirectory("invalid-yaml") { root ->
            val storage = CodexCliStorage(root)
            SystemCoroutineFileSystem.writeString(globalSettingsPath(root), "schema_version: nope\n")
            assertFailsWith<IllegalArgumentException> { storage.openGlobalSettings(root) }

            SystemCoroutineFileSystem.writeString(globalSettingsPath(root), "schema_version: 99\n")
            val failure = assertFailsWith<IllegalArgumentException> {
                storage.openGlobalSettings(root)
            }
            assertTrue(failure.message.orEmpty().contains("schema 99"))
        }
    }

    test("persists a selected Codex Home separately from Codex configuration") {
        withSettingsDirectory("selected-home") { root ->
            val initialHome = Path(root, "initial-codex")
            val selectedHome = Path(root, "selected-codex")
            val privateSettings = Path(root, "codexlite")
            SystemCoroutineFileSystem.createDirectories(initialHome)
            SystemCoroutineFileSystem.createDirectories(selectedHome)
            SystemCoroutineFileSystem.writeString(
                Path(selectedHome, "config.toml"),
                "model = \"selected-model\"",
            )
            val storage = CodexCliStorage(initialHome)
            val store = storage.openGlobalSettings(
                settingsDirectory = privateSettings,
                workingDirectory = root,
                defaults = CodexGlobalSettings(codexHome = initialHome),
            )

            store.update { current -> current.copy(codexHome = selectedHome) }

            val reopened = CodexCliStorage(initialHome).openGlobalSettings(
                settingsDirectory = privateSettings,
                workingDirectory = root,
                defaults = CodexGlobalSettings(codexHome = initialHome),
            )
            assertEquals(selectedHome, reopened.settings.value.codexHome)
            assertEquals(OpenAiModelId("selected-model"), reopened.settings.value.newSession.model)
            assertTrue(SystemCoroutineFileSystem.exists(Path(privateSettings, "settings.yml")))
            assertFalse(SystemCoroutineFileSystem.exists(Path(selectedHome, "settings.yml")))
        }
    }
}

private fun globalSettingsPath(codexHome: Path): Path =
    Path(Path(codexHome, "codexlite"), "settings.yml")

private fun configPath(codexHome: Path): Path =
    Path(codexHome, "config.toml")

private suspend fun CodexCliStorage.openGlobalSettings(
    codexHome: Path,
    defaults: CodexGlobalSettings = CodexGlobalSettings(codexHome = codexHome),
): CodexLiteSettingsStore =
    openGlobalSettings(
        settingsDirectory = Path(codexHome, "codexlite"),
        workingDirectory = codexHome,
        defaults = defaults,
    )

private suspend fun withSettingsDirectory(
    label: String,
    block: suspend (Path) -> Unit,
) {
    val root = Path(SystemTemporaryDirectory, "codex-settings-$label-${Random.nextLong()}")
    SystemCoroutineFileSystem.createDirectories(root)
    SystemCoroutineFileSystem.createDirectories(Path(root, "codexlite"))
    try {
        block(root)
    } finally {
        deleteSettingsDirectory(root)
    }
}

private suspend fun temporarySettingsFiles(root: Path): List<Path> =
    SystemCoroutineFileSystem.list(Path(root, "codexlite")).filter { path ->
        path.name.startsWith(".settings.yml.") && path.name.endsWith(".tmp")
    }

private suspend fun deleteSettingsDirectory(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteSettingsDirectory(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
