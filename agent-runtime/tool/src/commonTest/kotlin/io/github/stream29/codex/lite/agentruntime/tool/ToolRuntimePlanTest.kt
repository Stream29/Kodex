package io.github.stream29.codex.lite.agentruntime.tool

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchResult
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchSourceInfo
import io.github.stream29.codex.lite.tool.toolsearch.SearchToolCallParams
import kotlinx.schema.json.PropertyBuilder
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class PlanTestTool(
    override val spec: ToolSpec,
) : Tool {
    override fun close(): Unit = Unit

    override suspend fun handle(call: ResponseItem.ToolCall): ResponseItem.ToolCallOutput =
        when (call) {
            is ResponseItem.FunctionCall -> ResponseItem.FunctionCallOutput(
                callId = call.callId,
                output = FunctionCallOutputPayload.fromText("done"),
            )

            is ResponseItem.CustomToolCall -> ResponseItem.CustomToolCallOutput(
                callId = call.callId,
                output = FunctionCallOutputPayload.fromText("done"),
            )

            is ResponseItem.ClientToolSearchCall ->
                error("Client tool-search calls are handled by CodexToolRuntime.")
        }
}

private fun planTestTool(name: String): ResponsesApiTool =
    ResponsesApiTool(
        name = name,
        description = "Handle $name requests.",
        parameters = PropertyBuilder().obj {
            additionalProperties = false
        },
    )

val toolRuntimePlanTest by testSuite {
    test("plan keeps deferred handlers executable while hiding their initial schemas") {
        val directSpec = planTestTool("direct_tool")
        val deferredSpec = planTestTool("deferred_tool")
        val directTool = PlanTestTool(directSpec)
        val deferredTool = PlanTestTool(deferredSpec)

        val plan = toolRuntimePlan(
            entries = listOf(
                ToolRuntimeEntry(directTool),
                ToolRuntimeEntry(
                    tool = deferredTool,
                    exposure = ToolExposure.Deferred,
                    sourceInfo = ToolSearchSourceInfo("Test source", "Deferred test tools."),
                ),
            ),
            toolSearchEnabled = true,
        )

        assertEquals(listOf(directSpec), plan.modelVisibleSpecs.dropLast(1))
        assertIs<ToolSpec.ToolSearch>(plan.modelVisibleSpecs.last())
        assertEquals(listOf(directTool, deferredTool), plan.tools)
        val result = assertIs<ToolSearchResult.Success>(
            plan.toolSearchEngine.search(SearchToolCallParams(query = "deferred tool")),
        )
        assertEquals(listOf(deferredSpec.copy(deferLoading = true)), result.tools)
    }

    test("plan promotes deferred schemas when tool search is unavailable") {
        val directSpec = planTestTool("direct_tool")
        val deferredSpec = planTestTool("deferred_tool")

        val plan = toolRuntimePlan(
            entries = listOf(
                ToolRuntimeEntry(PlanTestTool(directSpec)),
                ToolRuntimeEntry(
                    tool = PlanTestTool(deferredSpec),
                    exposure = ToolExposure.Deferred,
                ),
            ),
            toolSearchEnabled = false,
        )

        assertEquals(listOf(directSpec, deferredSpec), plan.modelVisibleSpecs)
        assertEquals(
            emptyList(),
            assertIs<ToolSearchResult.Success>(
                plan.toolSearchEngine.search(SearchToolCallParams(query = "deferred tool")),
            ).tools,
        )
    }
}
