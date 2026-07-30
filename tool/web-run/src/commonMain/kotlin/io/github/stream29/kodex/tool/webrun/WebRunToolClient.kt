package io.github.stream29.kodex.tool.webrun

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResponseResult
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchRequest
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.SearchSettings
import io.github.stream29.kodex.openai.client.contract.OpenAiClient

public const val WebRunDefaultMaxOutputTokens: Long = 10_000L

/** Typed local facade over the Codex web-search endpoint. */
public class WebRunToolClient(
    private val client: OpenAiClient,
    private val sessionId: String,
    private val modelProvider: suspend () -> OpenAiModelId,
    private val settings: SearchSettings = SearchSettings(),
    private val maxOutputTokens: Long = WebRunDefaultMaxOutputTokens,
) {
    public suspend fun run(commands: SearchCommands): OpenAiResponseResult<SearchResponse> =
        client.search(
            SearchRequest(
                id = sessionId,
                model = modelProvider(),
                commands = commands,
                settings = settings,
                maxOutputTokens = maxOutputTokens,
            ),
        )
}
