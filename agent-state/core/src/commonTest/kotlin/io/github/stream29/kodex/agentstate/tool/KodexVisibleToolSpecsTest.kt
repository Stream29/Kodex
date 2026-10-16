package io.github.stream29.kodex.agentstate.tool

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpAuthenticationState
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.tool.applypatch.ApplyPatchTools
import io.github.stream29.kodex.tool.currenttime.CurrentTimeTools
import io.github.stream29.kodex.tool.getcontextremaining.GetContextRemainingTools
import io.github.stream29.kodex.tool.plan.PlanTools
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskTools
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputTools
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.kodex.tool.webrun.WebRunTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

val kodexVisibleToolSpecsTest by testSuite {
    test("combines fixed and dynamic specs") {
        val service = TestMcpService()
        val fixedSpecs = listOf(
            ApplyPatchTools.spec,
            CurrentTimeTools.spec,
            GetContextRemainingTools.spec,
            UnifiedExecTools.execCommandSpec,
            UnifiedExecTools.writeStdinSpec,
            WebRunTools.spec,
        )
        val specs = service.visibleToolSpecs(
            KodexAgentSettings(
                model = OpenAiModelId("test-model"),
                reasoning = Reasoning(effort = ReasoningEffort.Max),
            ),
        )

        assertEquals(
            fixedSpecs + PlanTools.spec + RequestUserInputTools.spec + SuggestSubagentTaskTools.spec,
            specs.dropLast(1),
        )
        val toolSearch = assertIs<ToolSpec.ToolSearch>(specs.last())
        assertTrue(toolSearch.description.contains("None currently enabled."))
        assertTrue(toolSearch.description.contains("Kodex local tools").not())
        assertTrue(
            emptyList<McpTool>()
                .toDeferredToolSearchDocuments()
                .all { document -> document.sourceInfo == null },
        )
    }

    test("samples current MCP availability") {
        val service = TestMcpService()

        val withoutMcp = assertIs<ToolSpec.ToolSearch>(service.visibleToolSpecs(testSettings()).last())
        service.clients.value = mapOf("calendar" to TestMcpClient)
        val withMcp = assertIs<ToolSpec.ToolSearch>(service.visibleToolSpecs(testSettings()).last())

        assertTrue(withoutMcp.description.contains("None currently enabled."))
        assertTrue(withMcp.description.contains("- calendar: Calendar tools."))
    }
}

private fun testSettings(): KodexAgentSettings =
    KodexAgentSettings(model = OpenAiModelId("test-model"))

private class TestMcpService : McpService {
    override val clients = MutableStateFlow<Map<String, McpClient>>(emptyMap())
    override val authentication = MutableStateFlow<Map<String, McpAuthenticationState>>(emptyMap())

    override suspend fun invalidate(serverName: String) = Unit

    override suspend fun refresh() = Unit

    override fun close() = Unit
}

private object TestMcpClient : McpClient {
    override val serverName: String = "calendar"
    override val state = MutableStateFlow<McpClientState>(McpClientState.Healthy)

    override fun listTools(): List<McpTool> = listOf(TestMcpTool)

    override suspend fun reconnect() = Unit
}

private object TestMcpTool : McpTool {
    override val serverName: String = "calendar"
    override val serverInstructions: String = "Calendar tools."
    override val spec: ToolSpec = CurrentTimeTools.spec

    override suspend fun handle(pending: PendingToolEvent): StableCleanEvent.CompletedTool =
        error("Tool projection tests never execute MCP tools.")

    override fun close(): Unit = Unit
}
