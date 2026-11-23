package io.github.stream29.kodex.cli.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Valid codec/default assertions for the unified settings store. */
val settingsCompatibilityEvidenceTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("loading reload and clearing MCP never consult Codex configuration or old Hooks") {
        withSettingsDirectory { directory ->
            val codex = Path(directory, "codex")
            writeFixture(Path(codex, "config.toml"), "this is invalid TOML")
            writeFixture(Path(codex, "hooks.json"), "this is invalid JSON")
            val defaults = backendDefaults().copy(
                newSession = KodexNewSessionSettings(model = OpenAiModelId("compiled-model")),
                mcpServers = mapOf("compiled" to McpServerConfiguration.Stdio(command = "unused")),
            )
            val unified = UnifiedSettingsStore.open(directory, defaults)
            assertEquals(defaults, unified.backendSettings.value)
            assertEquals(defaults, unified.readCandidate().backend)
            assertEquals(emptyList(), unified.frontendSettings.value.hooks)
            unified.backendStore.update { it.copy(mcpServers = emptyMap()) }
            assertEquals(emptyMap(), unified.readCandidate().backend.mcpServers)
            assertEquals(emptyMap(), UnifiedSettingsStore.open(directory, defaults).backendSettings.value.mcpServers)
        }
    }

    test("recent settings aliases are read without rewriting the actual Home file") {
        withSettingsDirectory { directory ->
            val path = Path(directory, "settings.yml")
            val fixture = """
                backend:
                  new_session: {reasoning_effort: ultra, service_tier: fast}
                  session_title: {reasoning_effort: ultra}
                frontend:
                  sidebars: {left: history_index, right: terminal_sessions}
                  private_extension: keep
            """.trimIndent()
            writeFixture(path, fixture)
            val store = UnifiedSettingsStore.open(directory, backendDefaults())
            assertEquals(ReasoningEffort.Max, store.backendSettings.value.newSession.reasoningEffort)
            assertEquals(ReasoningEffort.Max, store.backendSettings.value.sessionTitle.reasoningEffort)
            assertEquals(ServiceTier.Fast, store.backendSettings.value.newSession.serviceTier)
            assertEquals(ToolWindowId.TerminalSessions, store.frontendSettings.value.sidebars.activeRight)
            assertEquals(fixture, SystemCoroutineFileSystem.readString(path))
        }
    }

    test("only the published recent sidebar options are accepted and explicit current keys win") {
        val defaults = CliFrontendSettings()
        fun activeRight(entries: String) =
            decodeCliFrontendSettingsFile(yamlMap("sidebars: {$entries}"), defaults).sidebars.activeRight

        assertEquals(ToolWindowId.HistoryIndex, activeRight("left: history_index, right: none"))
        assertEquals(null, activeRight("left: history_index, active_right: null"))
        assertEquals(null, activeRight("left: none, right: none"))
        assertEquals(ToolWindowId.HistoryIndex, activeRight("right: terminal_sessions, active_right: history_index"))
        assertFailsWith<IllegalArgumentException> { activeRight("right: unsupported") }
        withSettingsDirectory { directory ->
            val path = Path(directory, "settings.yml")
            val fixture = "backend:\n  new_session: {service_tier: unavailable}\n"
            writeFixture(path, fixture)
            assertFailsWith<IllegalArgumentException> { UnifiedSettingsStore.open(directory, backendDefaults()) }
            assertEquals(fixture, SystemCoroutineFileSystem.readString(path))
        }
    }

    test("missing question mode retains opening defaults and compiled AskUser on reopening") {
        withSettingsDirectory { directory ->
            val path = Path(directory, "settings.yml")
            writeFixture(path, "backend:\n  new_session: {model: configured-model}\n")
            val compiled = backendDefaults()
            assertEquals(
                RequestUserInputMode.AskUser,
                UnifiedSettingsStore.open(directory, compiled).backendSettings.value.newSession.requestUserInputMode,
            )
            val supplied = compiled.copy(
                newSession = compiled.newSession.copy(requestUserInputMode = RequestUserInputMode.NoQuestion),
            )
            assertEquals(
                RequestUserInputMode.NoQuestion,
                UnifiedSettingsStore.open(directory, supplied).backendSettings.value.newSession.requestUserInputMode,
            )
        }
    }

    test("backend updates canonicalize settings.yml while preserving frontend values without creating context files") {
        withSettingsDirectory { directory ->
            val path = Path(directory, "settings.yml")
            writeFixture(
                path,
                "backend:\n  schema_version: 2\n  obsolete: preserve-until-update\n  new_session: {model: configured-model, mode: plan}\nfrontend:\n  new_line_key: enter\n",
            )
            val contextPath = Path(directory, "custom-context")
            val unified = UnifiedSettingsStore.open(directory, backendDefaults())
            val expectedContext = AgentContextSourceSettings(
                agentsHomeEnabled = false,
                codexHomeEnabled = false,
                customSources = listOf(AgentContextCustomSource(contextPath.toString())),
            )
            val updated = unified.backendStore.update { it.copy(contextSources = expectedContext) }
            val text = SystemCoroutineFileSystem.readString(path)
            assertFalse("schema_version:" in text)
            assertFalse("obsolete:" in text)
            assertTrue(text.lineSequence().none { it.trimStart().startsWith("mode:") })
            assertTrue("model: configured-model" in text)
            assertEquals(updated, UnifiedSettingsStore.open(directory, backendDefaults()).backendSettings.value)
            assertEquals(expectedContext, updated.contextSources)
            assertFalse(SystemCoroutineFileSystem.exists(contextPath))
            assertEquals(NewLineKey.Enter, unified.frontendSettings.value.newLineKey)
        }
    }

    test("original snake-case file golden remains distinct from camel-case RPC values") {
        val backendGolden = """
            auth_source: kodex
            new_session:
              model: golden-model
              reasoning_effort: high
              service_tier: flex
              request_user_input_mode: no_question
            session_title:
              enabled: false
              model: null
              reasoning_effort: medium
            mcp_servers: {}
        """.trimIndent()
        val defaults = backendDefaults()
        val expected = defaults.copy(
            authSource = KodexAuthSource.Kodex,
            newSession = KodexNewSessionSettings(
                OpenAiModelId("golden-model"), ReasoningEffort.High, ServiceTier.Flex, RequestUserInputMode.NoQuestion,
            ),
            sessionTitle = SessionTitleSettings(false, null, ReasoningEffort.Medium),
        )
        assertEquals(expected, decodeBackendSettingsFile(yamlMap(backendGolden), defaults))
        val encoded = encodeBackendSettingsFile(expected)
        assertTrue("auth_source: kodex" in encoded)
        assertTrue("request_user_input_mode: no_question" in encoded)
        assertTrue("model: null" in encoded)
        assertEquals(expected, decodeBackendSettingsFile(yamlMap(encoded), defaults))
        val frontendGolden = "new_line_key: enter\nsidebars: {active_left: null, active_right: history_index}\nhooks: []"
        val frontend = decodeCliFrontendSettingsFile(yamlMap(frontendGolden), CliFrontendSettings())
        assertEquals(NewLineKey.Enter, frontend.newLineKey)
        assertEquals(null, frontend.sidebars.activeLeft)
        assertEquals(ToolWindowId.HistoryIndex, frontend.sidebars.activeRight)
        assertEquals(emptyList(), frontend.hooks)
        assertEquals(frontend, decodeCliFrontendSettingsFile(
            yamlMap(encodeCliFrontendSettingsFile(frontend)), CliFrontendSettings(),
        ))
    }
}

private fun yamlMap(text: String): YamlMap = Yaml.default.parseToYamlNode(text) as YamlMap
