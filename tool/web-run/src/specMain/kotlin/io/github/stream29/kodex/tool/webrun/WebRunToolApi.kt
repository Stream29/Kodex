@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.webrun

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResponseResult
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.SearchSettings
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.tool.contract.Tool

public const val WebRunDefaultMaxOutputTokens: Long = 10_000L
public const val WebRunNamespace: String = "web"
public const val WebRunToolName: String = "run"

/** Typed local facade over the Codex web-search endpoint. */
public expect class WebRunToolClient(
    client: OpenAiClient,
    sessionId: String,
    modelProvider: suspend () -> OpenAiModelId,
    settings: SearchSettings = SearchSettings(),
    maxOutputTokens: Long = WebRunDefaultMaxOutputTokens,
) {
    public suspend fun run(commands: SearchCommands): OpenAiResponseResult<SearchResponse>
}

public object WebRunTools {
    public const val NamespaceDescription: String = "Tools in the web namespace."

    public val description: String
        get() = WebRunToolImplementation.description
    public val spec: ToolSpec
        get() = WebRunToolImplementation.spec

    public fun createTool(client: WebRunToolClient): Tool =
        WebRunToolImplementation.createTool(client)
}

internal expect object WebRunToolImplementation {
    val description: String
    val spec: ToolSpec
    fun createTool(client: WebRunToolClient): Tool
}
