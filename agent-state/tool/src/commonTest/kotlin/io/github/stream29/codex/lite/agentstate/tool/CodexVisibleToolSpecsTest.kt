package io.github.stream29.codex.lite.agentstate.tool

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.mcp.contract.McpService
import io.github.stream29.codex.lite.mcp.contract.McpTool
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchTools
import io.github.stream29.codex.lite.tool.currenttime.CurrentTimeTools
import io.github.stream29.codex.lite.tool.getcontextremaining.GetContextRemainingTools
import io.github.stream29.codex.lite.tool.multiagent.MultiAgentTools
import io.github.stream29.codex.lite.tool.plan.PlanTools
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputTools
import io.github.stream29.codex.lite.tool.contract.ToolCallResult
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.codex.lite.tool.webrun.WebRunTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

val codexVisibleToolSpecsTest by testSuite {
    test("combines fixed mode-dependent and dynamic specs") {
        val service = TestMcpService()
        val fixedSpecs = listOf(
            ApplyPatchTools.spec,
            CurrentTimeTools.spec,
            GetContextRemainingTools.spec,
            UnifiedExecTools.execCommandSpec,
            UnifiedExecTools.writeStdinSpec,
            WebRunTools.spec,
        ) + MultiAgentTools.specs
        val defaultSpecs = service.visibleToolSpecs(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                collaborationMode = ModeKind.Default,
            ),
        )
        val planSpecs = service.visibleToolSpecs(
            CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                collaborationMode = ModeKind.Plan,
            ),
        )

        assertEquals(
            fixedSpecs + PlanTools.spec + RequestUserInputTools.spec,
            defaultSpecs.dropLast(1),
        )
        assertEquals(
            fixedSpecs + RequestUserInputTools.spec,
            planSpecs.dropLast(1),
        )
        val defaultToolSearch = assertIs<ToolSpec.ToolSearch>(defaultSpecs.last())
        assertEquals(defaultToolSearch, planSpecs.last())
        assertTrue(defaultToolSearch.description.contains("None currently enabled."))
        assertTrue(defaultToolSearch.description.contains("Codex Lite local tools").not())
        assertTrue(
            emptyList<McpTool>()
                .toDeferredToolSearchDocuments()
                .all { document -> document.sourceInfo == null },
        )
    }

    test("samples current MCP availability") {
        val service = TestMcpService()

        val withoutMcp = assertIs<ToolSpec.ToolSearch>(service.visibleToolSpecs(testSettings()).last())
        service.tools.value = listOf(TestMcpTool)
        val withMcp = assertIs<ToolSpec.ToolSearch>(service.visibleToolSpecs(testSettings()).last())

        assertTrue(withoutMcp.description.contains("None currently enabled."))
        assertTrue(withMcp.description.contains("- calendar: Calendar tools."))
    }
}

private fun testSettings(): CodexAgentSettings =
    CodexAgentSettings(model = OpenAiModelId("test-model"))

private class TestMcpService : McpService {
    override val tools = MutableStateFlow<List<McpTool>>(emptyList())

    override suspend fun refresh() = Unit

    override fun close() = Unit
}

private object TestMcpTool : McpTool {
    override val serverName: String = "calendar"
    override val serverInstructions: String = "Calendar tools."
    override val spec: ToolSpec = CurrentTimeTools.spec

    override suspend fun handle(call: ResponseItem.ToolCall): ToolCallResult =
        error("Tool projection tests never execute MCP tools.")

    override fun close(): Unit = Unit
}
