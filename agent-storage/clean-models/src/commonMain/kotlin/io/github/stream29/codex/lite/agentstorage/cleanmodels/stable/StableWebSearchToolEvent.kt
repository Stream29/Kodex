package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.SearchResponse
import io.github.stream29.codex.lite.openai.WebSearchAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a completed web-search interaction.
 *
 * Local `web.run` calls retain [commands] and [SearchResponse] without
 * translating either DTO. Hosted calls retain their provider-native [action].
 */
@Serializable
@SerialName("web_search_tool_event")
public data class StableWebSearchToolEvent(
    public val request: StableWebSearchRequest,
    public val result: StableWebSearchResult,
) : StableCleanEvent.CompletedTool

/** Provider-native input available for a web-search interaction. */
@Serializable
public sealed interface StableWebSearchRequest {
    @Serializable
    @SerialName("web_run")
    public data class WebRun(
        public val commands: SearchCommands,
    ) : StableWebSearchRequest

    @Serializable
    @SerialName("hosted")
    public data class Hosted(
        public val action: WebSearchAction? = null,
    ) : StableWebSearchRequest
}

/** Completed outcome of a web-search interaction. */
@Serializable
public sealed interface StableWebSearchResult {
    /**
     * Web search completed.
     *
     * [response] is absent for hosted search because its history item has no
     * separate local `SearchResponse`.
     */
    @Serializable
    @SerialName("success")
    public data class Success(
        public val response: SearchResponse? = null,
    ) : StableWebSearchResult

    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableWebSearchResult
}
