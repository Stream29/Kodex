package io.github.stream29.codex.lite.tool.toolsearch

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ToolSpec
import kotlinx.schema.json.PropertyBuilder
import kotlin.test.assertEquals
import kotlin.test.assertIs



private fun tool(name: String, description: String): ResponsesApiTool =
    ResponsesApiTool(
        name = name,
        description = description,
        parameters = PropertyBuilder().obj {
            additionalProperties = false
            property("query") { string { this.description = "Query text" } }
        },
    )

val toolSearchToolsTest by testSuite {
    test("create tool search tool deduplicates and renders enabled sources") {
        val spec = ToolSearchTools.createToolSearchSpec(
            searchableSources = listOf(
                ToolSearchSourceInfo(
                    name = "Google Drive",
                    description = "Use Google Drive as the single entrypoint for Drive, Docs, Sheets, and Slides work.",
                ),
                ToolSearchSourceInfo(name = "Google Drive"),
                ToolSearchSourceInfo(name = "docs"),
            ),
        )

        assertEquals("client", spec.execution)
        assertEquals(
            "# Tool discovery\n\n" +
                "Searches over deferred tool metadata with BM25 and exposes matching tools for the next model call.\n\n" +
                "You have access to tools from the following sources:\n" +
                "- Google Drive: Use Google Drive as the single entrypoint for Drive, Docs, Sheets, and Slides work.\n" +
                "- docs\n" +
                "Some of the tools may not have been provided to you upfront, and you should use this tool (`tool_search`) to search for the required tools. " +
                "For MCP tool discovery, always use `tool_search` instead of `list_mcp_resources` or `list_mcp_resource_templates`.",
            spec.description,
        )
    }

    test("namespace search result coalesces by namespace") {
        val createEvent = tool("create_event", "Create events")
        val listEvents = tool("list_events", "List events")
        val engine = ToolSearchEngine(
            listOf(
                ToolSearchDocument(
                    searchText = "calendar event",
                    output = ResponsesApiToolWithNamespace(
                        namespaceName = "mcp__calendar",
                        namespaceDescription = "Calendar tools",
                        tool = createEvent,
                    ),
                ),
                ToolSearchDocument(
                    searchText = "calendar automation",
                    output = ResponsesApiToolWithNamespace(
                        namespaceName = "codex_app",
                        namespaceDescription = "Automation tools",
                        tool = tool("automation_update", "Update automations"),
                    ),
                ),
                ToolSearchDocument(
                    searchText = "calendar event",
                    output = ResponsesApiToolWithNamespace(
                        namespaceName = "mcp__calendar",
                        namespaceDescription = "Calendar tools",
                        tool = listEvents,
                    ),
                ),
            ),
        )
        val results = engine.search(SearchToolCallParams(query = "calendar"))

        assertEquals(2, results.size)
        val calendar = results
            .filterIsInstance<ResponsesApiNamespace>()
            .single { it.name == "mcp__calendar" }
        assertEquals("mcp__calendar", calendar.name)
        assertEquals(setOf(createEvent, listEvents), calendar.tools.toSet())
    }

    test("search returns loadable tools") {
        val engine = ToolSearchEngine(
            ResponsesApiNamespace(
                name = "calendar",
                description = "Calendar tools",
                tools = listOf(tool("create_event", "Create events")),
            ).toToolSearchDocuments()
        )

        val result = engine.search(SearchToolCallParams(query = "create calendar event"))

        val namespace = assertIs<ResponsesApiNamespace>(result.single())
        assertEquals("calendar", namespace.name)
        assertEquals(true, (namespace.tools.single() as ResponsesApiTool).deferLoading)
    }

    test("search returns standalone loadable tool") {
        val standaloneTool = tool("request_user_input", "Ask user a question")
        val engine = ToolSearchEngine(
            listOf<ToolSpec>(
                ResponsesApiNamespace(
                    name = "calendar",
                    description = "Calendar tools",
                    tools = listOf(tool("create_event", "Create events")),
                ),
                standaloneTool,
            ).flatMap { it.toToolSearchDocuments() },
        )

        val result = engine.search(SearchToolCallParams(query = "user question"))

        val tool = assertIs<ResponsesApiTool>(result.single())
        assertEquals("request_user_input", tool.name)
        assertEquals(true, tool.deferLoading)
    }

    test("namespace spec creates one search document per tool") {
        val documents = ResponsesApiNamespace(
            name = "calendar",
            description = "Calendar tools",
            tools = listOf(
                tool("create_event", "Create events"),
                tool("list_events", "List events"),
            ),
        ).toToolSearchDocuments()

        assertEquals(2, documents.size)
        val firstOutput = assertIs<ResponsesApiToolWithNamespace>(documents.first().output)
        assertEquals("calendar", firstOutput.namespaceName)
        assertEquals("create_event", firstOutput.tool.name)

        val secondOutput = assertIs<ResponsesApiToolWithNamespace>(documents.last().output)
        assertEquals("calendar", secondOutput.namespaceName)
        assertEquals("list_events", secondOutput.tool.name)
    }
}
