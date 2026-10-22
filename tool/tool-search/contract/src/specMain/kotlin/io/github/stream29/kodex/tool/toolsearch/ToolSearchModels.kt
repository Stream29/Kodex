package io.github.stream29.kodex.tool.toolsearch

import io.github.stream29.kodex.openai.LoadableToolSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @property limit Nullable because the model may omit it; `null` means use
 * `ToolSearchDefaultLimit`.
 */
@Serializable
public data class SearchToolCallParams(
    public val query: String,
    public val limit: Int? = null,
)

/** Side responsible for executing a deferred-tool search. */
@Serializable
public enum class ToolSearchExecution {
    @SerialName("client")
    Client,

    @SerialName("server")
    Server,
}

/** Result of searching deferred tool metadata. */
@Serializable
public sealed interface ToolSearchResult {
    /** A completed search, including an empty result set. */
    @Serializable
    @SerialName("success")
    public data class Success(
        public val tools: List<LoadableToolSpec>,
    ) : ToolSearchResult

    /** The model supplied syntactically valid JSON with invalid search values. */
    @Serializable
    @SerialName("invalid_arguments")
    public data class InvalidArguments(
        public val message: String,
    ) : ToolSearchResult
}
