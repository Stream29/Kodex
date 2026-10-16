package io.github.stream29.kodex.cli.settings

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Valid codec/default assertions ported from the retired combined-store suite. */
val settingsCompatibilityEvidenceTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("loading reload and clearing MCP never consult Codex configuration or old Hooks") {
        withSplitSettingsDirectory { directory ->
            val codex = Path(directory, "codex")
            writeFixture(Path(codex, "config.toml"), "this is invalid TOML")
            writeFixture(Path(codex, "hooks.json"), "this is invalid JSON")
            val defaults = backendDefaults().copy(
                newSession = KodexNewSessionSettings(model = OpenAiModelId("compiled-model")),
                mcpServers = mapOf("compiled" to McpServerConfiguration.Stdio(command = "unused")),
            )
            val backend = openBackendSettings(directory, defaults)
            val frontend = openCliFrontendSettings(directory)
            assertEquals(defaults, backend.settings.value)
            assertEquals(defaults, backend.reload())
            assertEquals(emptyList(), frontend.reload().hooks)
            backend.update { it.copy(mcpServers = emptyMap()) }
            assertEquals(emptyMap(), backend.reload().mcpServers)
            assertEquals(emptyMap(), openBackendSettings(directory, defaults).settings.value.mcpServers)
        }
    }

    test("legacy ultra reasoning normalizes to max in the actual backend file codec") {
        withSplitSettingsDirectory { directory ->
            val path = Path(directory, "settings.backend.yml")
            writeFixture(path, "new_session: {reasoning_effort: ultra}\nsession_title: {reasoning_effort: ultra}\n")
            val store = openBackendSettings(directory, backendDefaults())
            assertEquals(ReasoningEffort.Max, store.settings.value.newSession.reasoningEffort)
            assertEquals(ReasoningEffort.Max, store.settings.value.sessionTitle.reasoningEffort)
            store.update { it }
            val text = SystemCoroutineFileSystem.readString(path)
            assertFalse("reasoning_effort: ultra" in text)
            assertEquals(2, text.lineSequence().count { it.trim() == "reasoning_effort: max" })
        }
    }

    test("missing question mode retains opening defaults and compiled AskUser on reopening") {
        withSplitSettingsDirectory { directory ->
            val path = Path(directory, "settings.backend.yml")
            writeFixture(path, "new_session: {model: configured-model}\n")
            val compiled = backendDefaults()
            assertEquals(
                RequestUserInputMode.AskUser,
                openBackendSettings(directory, compiled).settings.value.newSession.requestUserInputMode,
            )
            val supplied = compiled.copy(
                newSession = compiled.newSession.copy(requestUserInputMode = RequestUserInputMode.NoQuestion),
            )
            assertEquals(
                RequestUserInputMode.NoQuestion,
                openBackendSettings(directory, supplied).settings.value.newSession.requestUserInputMode,
            )
        }
    }

    test("backend updates canonicalize only its file and preserve configured values without creating context files") {
        withSplitSettingsDirectory { directory ->
            val path = Path(directory, "settings.backend.yml")
            val frontendPath = Path(directory, "settings.frontend.cli.yml")
            val frontendText = "future_frontend: preserve\n"
            writeFixture(frontendPath, frontendText)
            writeFixture(path, "schema_version: 2\nobsolete: preserve-until-update\nnew_session: {model: configured-model, mode: plan}\n")
            val contextPath = Path(directory, "custom-context")
            val store = openBackendSettings(directory, backendDefaults())
            val expectedContext = AgentContextSourceSettings(
                agentsHomeEnabled = false,
                codexHomeEnabled = false,
                customSources = listOf(AgentContextCustomSource(contextPath.toString())),
            )
            val updated = store.update { it.copy(contextSources = expectedContext) }
            val text = SystemCoroutineFileSystem.readString(path)
            assertFalse("schema_version:" in text)
            assertFalse("obsolete:" in text)
            assertTrue(text.lineSequence().none { it.trimStart().startsWith("mode:") })
            assertTrue("model: configured-model" in text)
            assertEquals(updated, openBackendSettings(directory, backendDefaults()).settings.value)
            assertEquals(expectedContext, updated.contextSources)
            assertFalse(SystemCoroutineFileSystem.exists(contextPath))
            assertEquals(frontendText, SystemCoroutineFileSystem.readString(frontendPath))
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
        assertEquals(expected, decodeBackendSettingsFile(backendGolden, defaults))
        val encoded = encodeBackendSettingsFile(expected)
        assertTrue("auth_source: kodex" in encoded)
        assertTrue("request_user_input_mode: no_question" in encoded)
        assertTrue("model: null" in encoded)
        assertEquals(expected, decodeBackendSettingsFile(encoded, defaults))
        val frontendGolden = "new_line_key: enter\nsidebars: {left: none, right: history_index}\nhooks: []"
        val frontend = decodeCliFrontendSettingsFile(frontendGolden, CliFrontendSettings())
        assertEquals(NewLineKey.Enter, frontend.newLineKey)
        assertEquals(SidebarContent.None, frontend.sidebars.left)
        assertEquals(SidebarContent.HistoryIndex, frontend.sidebars.right)
        assertEquals(emptyList(), frontend.hooks)
        assertEquals(frontend, decodeCliFrontendSettingsFile(
            encodeCliFrontendSettingsFile(frontend), CliFrontendSettings(),
        ))
    }
}
