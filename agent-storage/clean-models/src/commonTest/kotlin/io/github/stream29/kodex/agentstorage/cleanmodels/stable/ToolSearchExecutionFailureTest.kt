package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.tool.toolsearch.SearchToolCallParams
import io.github.stream29.kodex.tool.toolsearch.ToolSearchResult
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertIs

val toolSearchExecutionFailureTest by testSuite {
    test("search execution failure stays distinct and projects an incomplete output") {
        val event = StableToolSearchEvent(
            callId = "call-one",
            arguments = SearchToolCallParams("example"),
            result = ToolSearchResult.ExecutionFailure("Tool execution failed."),
        )
        val encoded = Json.encodeToString(StableToolSearchEvent.serializer(), event)
        assertEquals(event, Json.decodeFromString(StableToolSearchEvent.serializer(), encoded))
        val output = assertIs<ResponseItem.ClientToolSearchOutput>(event.toResponseHistoryItems().last())
        assertEquals("incomplete", output.status)
        assertEquals(emptyList(), output.tools)
    }
}
