package io.github.stream29.codex.lite.tool.websearch

import io.github.stream29.codex.lite.tool.contract.ResponsesApiNamespace
import io.github.stream29.codex.lite.tool.contract.ResponsesApiTool
import io.github.stream29.codex.lite.tool.contract.ToolSpec

public object WebSearchTools {
    public const val Namespace: String = "web"
    public const val RunToolName: String = "run"

    public const val DefaultNamespaceDescription: String = "Tools in the web namespace."

    public const val DefaultRunDescription: String =
        "Search, open, inspect, and retrieve concise current information from the web."

    public val spec: ToolSpec =
        ResponsesApiNamespace(
            name = Namespace,
            description = DefaultNamespaceDescription,
            tools = listOf(
                ResponsesApiTool(
                    name = RunToolName,
                    description = DefaultRunDescription,
                    strict = false,
                    parameters = SearchCommandsSchema,
                ),
            ),
        )
}
