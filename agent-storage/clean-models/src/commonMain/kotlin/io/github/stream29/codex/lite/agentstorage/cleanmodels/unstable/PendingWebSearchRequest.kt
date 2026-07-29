package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.WebSearchAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Provider-native input for a web-search interaction awaiting completion. */
@Serializable
public sealed interface PendingWebSearchRequest {
    @Serializable
    @SerialName("web_run")
    public data class WebRun(
        public val commands: SearchCommands,
    ) : PendingWebSearchRequest

    @Serializable
    @SerialName("hosted")
    public data class Hosted(
        public val action: WebSearchAction? = null,
    ) : PendingWebSearchRequest
}
