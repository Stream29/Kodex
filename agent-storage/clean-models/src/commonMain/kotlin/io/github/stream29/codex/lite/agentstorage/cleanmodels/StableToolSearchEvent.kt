package io.github.stream29.codex.lite.agentstorage.cleanmodels

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a completed deferred-tool search.
 *
 * @property execution Side that executed the search.
 * @property query Search text supplied by the model.
 * @property limit Nullable because the model may use the configured default.
 * @property result Strongly typed search result.
 */
@Serializable
@SerialName("tool_search_event")
public data class StableToolSearchEvent(
    public val execution: StableToolSearchExecution,
    public val query: String,
    public val limit: Int? = null,
    public val result: StableToolSearchResult,
) : StableToolEvent

/** Side responsible for executing a deferred-tool search. */
@Serializable
public enum class StableToolSearchExecution {
    @SerialName("client")
    Client,

    @SerialName("server")
    Server,
}

/** Completed outcome of a deferred-tool search. */
@Serializable
public sealed interface StableToolSearchResult {
    /** Search completed and loaded [tools], possibly an empty list. */
    @Serializable
    @SerialName("success")
    public data class Success(
        public val tools: List<StableToolSearchTool>,
    ) : StableToolSearchResult

    /** Search failed before a usable tool list was produced. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String? = null,
    ) : StableToolSearchResult
}

/** Tool declaration returned by deferred-tool search. */
@Serializable
public sealed interface StableToolSearchTool {
    /** A standalone function tool. */
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val description: String,
        public val strict: Boolean = false,
        @SerialName("defer_loading")
        public val deferLoading: Boolean? = null,
        public val parameters: ObjectPropertyDefinition,
    ) : StableToolSearchTool

    /** A namespace containing one or more loaded function tools. */
    @Serializable
    @SerialName("namespace")
    public data class Namespace(
        public val name: String,
        public val description: String,
        public val tools: List<Function>,
    ) : StableToolSearchTool
}
