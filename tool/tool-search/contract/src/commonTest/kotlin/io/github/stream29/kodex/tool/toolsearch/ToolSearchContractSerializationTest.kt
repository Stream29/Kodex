package io.github.stream29.kodex.tool.toolsearch

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals

val toolSearchContractSerializationTest by testSuite {
    test("shared search values retain their wire shape") {
        val arguments = SearchToolCallParams(query = "connected tools", limit = 4)
        val result: ToolSearchResult = ToolSearchResult.InvalidArguments("query must not be empty")

        assertEquals(
            """{"query":"connected tools","limit":4}""",
            Json.encodeToString(arguments),
        )
        assertEquals(
            """{"type":"invalid_arguments","message":"query must not be empty"}""",
            Json.encodeToString<ToolSearchResult>(result),
        )
        assertEquals(
            arguments,
            Json.decodeFromString<SearchToolCallParams>(Json.encodeToString(arguments)),
        )
        assertEquals(
            result,
            Json.decodeFromString<ToolSearchResult>(Json.encodeToString<ToolSearchResult>(result)),
        )
    }

    test("execution markers and empty success retain their wire shape") {
        assertEquals("\"client\"", Json.encodeToString(ToolSearchExecution.Client))
        assertEquals("\"server\"", Json.encodeToString(ToolSearchExecution.Server))
        val result: ToolSearchResult = ToolSearchResult.Success(emptyList())
        val encoded = Json.encodeToString<ToolSearchResult>(result)

        assertEquals("""{"type":"success","tools":[]}""", encoded)
        assertEquals(result, Json.decodeFromString<ToolSearchResult>(encoded))
    }

    test("an omitted limit remains nullable and uses the original default") {
        assertEquals(8, ToolSearchDefaultLimit)
        assertEquals(
            SearchToolCallParams("connected tools"),
            Json.decodeFromString<SearchToolCallParams>("""{"query":"connected tools"}"""),
        )
        assertEquals(
            """{"query":"connected tools"}""",
            Json.encodeToString(SearchToolCallParams("connected tools")),
        )
    }
}
