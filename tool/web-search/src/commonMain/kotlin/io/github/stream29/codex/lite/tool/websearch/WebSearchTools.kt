package io.github.stream29.codex.lite.tool.websearch

import io.github.stream29.codex.lite.llmprovider.LlmNamespaceTool
import io.github.stream29.codex.lite.llmprovider.LlmTool

public object WebSearchTools {
    public const val Namespace: String = "web"
    public const val RunToolName: String = "run"

    public const val DefaultNamespaceDescription: String = "Tools in the web namespace."

    public const val DefaultRunDescription: String =
        "Search, open, inspect, and retrieve concise current information from the web."

    public val spec: LlmTool = createSpec()

    public fun createSpec(
        namespaceDescription: String = DefaultNamespaceDescription,
        runDescription: String = DefaultRunDescription,
    ): LlmTool =
        LlmTool.Namespace(
            name = Namespace,
            description = namespaceDescription,
            tools = listOf(
                LlmNamespaceTool.Function(
                    name = RunToolName,
                    description = runDescription,
                    strict = false,
                    parameters = SearchCommandsSchema,
                ),
            ),
        )
}
