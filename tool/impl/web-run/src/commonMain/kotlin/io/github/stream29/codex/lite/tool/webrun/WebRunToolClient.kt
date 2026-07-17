package io.github.stream29.codex.lite.tool.webrun

import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResponseResult
import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.SearchRequest
import io.github.stream29.codex.lite.openai.SearchResponse
import io.github.stream29.codex.lite.openai.SearchSettings
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient

public const val WebRunDefaultMaxOutputTokens: Long = 10_000L

/** Typed local facade over the Codex web-search endpoint. */
public class WebRunToolClient(
    private val client: OpenAiClient,
    private val sessionId: String,
    private val model: OpenAiModelId,
    private val settings: SearchSettings = SearchSettings(),
    private val maxOutputTokens: Long = WebRunDefaultMaxOutputTokens,
) {
    public suspend fun run(commands: SearchCommands): OpenAiResponseResult<SearchResponse> =
        client.search(
            SearchRequest(
                id = sessionId,
                model = model,
                commands = commands,
                settings = settings,
                maxOutputTokens = maxOutputTokens,
            ),
        )
}
