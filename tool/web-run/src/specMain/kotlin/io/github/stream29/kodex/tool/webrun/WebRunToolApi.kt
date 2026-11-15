@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.webrun

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResponseResult
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.SearchSettings
import io.github.stream29.kodex.openai.client.contract.OpenAiClient

public const val WebRunDefaultMaxOutputTokens: Long = 10_000L

/** Typed backend facade over the Codex web-search endpoint used by `AgentRuntimeRpc.runWebSearch` and `kodex search`. */
public expect class WebRunToolClient(
    client: OpenAiClient,
    sessionId: String,
    modelProvider: suspend () -> OpenAiModelId,
    settings: SearchSettings = SearchSettings(),
    maxOutputTokens: Long = WebRunDefaultMaxOutputTokens,
) {
    public suspend fun run(commands: SearchCommands): OpenAiResponseResult<SearchResponse>
}
