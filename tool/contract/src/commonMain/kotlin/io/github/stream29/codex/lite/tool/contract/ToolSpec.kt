package io.github.stream29.codex.lite.tool.contract

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors Rust `ToolSpec` from `shared-context/codex/codex-rs/tools/src/tool_spec.rs`.
 *
 * Tool parameter schemas are represented with `kotlinx.schema.json.ObjectPropertyDefinition`.
 * Do not add a project-local JSON Schema model for this field.
 */
@Serializable
public sealed interface ToolSpec {
    @Serializable
    @SerialName("tool_search")
    public data class ToolSearch(
        public val execution: String,
        public val description: String,
        public val parameters: ObjectPropertyDefinition,
    ) : ToolSpec

    @Serializable
    @SerialName("image_generation")
    public data class ImageGeneration(
        @SerialName("output_format")
        public val outputFormat: String,
    ) : ToolSpec

    @Serializable
    @SerialName("web_search")
    public data class WebSearch(
        @SerialName("external_web_access")
        public val externalWebAccess: Boolean? = null,
        public val filters: ResponsesApiWebSearchFilters? = null,
        @SerialName("user_location")
        public val userLocation: ResponsesApiWebSearchUserLocation? = null,
        @SerialName("search_context_size")
        public val searchContextSize: WebSearchContextSize? = null,
        @SerialName("search_content_types")
        public val searchContentTypes: List<String>? = null,
    ) : ToolSpec
}

/**
 * Mirrors Rust `ResponsesApiTool` from `shared-context/codex/codex-rs/tools/src/responses_api.rs`.
 */
@Serializable
@SerialName("function")
public data class ResponsesApiTool(
    public val name: String,
    public val description: String,
    public val strict: Boolean = false,
    @SerialName("defer_loading")
    public val deferLoading: Boolean? = null,
    public val parameters: ObjectPropertyDefinition,
) : ToolSpec, LoadableToolSpec, ResponsesApiNamespaceTool

/**
 * Mirrors Rust `ResponsesApiNamespace` from `shared-context/codex/codex-rs/tools/src/responses_api.rs`.
 */
@Serializable
@SerialName("namespace")
public data class ResponsesApiNamespace(
    public val name: String,
    public val description: String,
    public val tools: List<ResponsesApiNamespaceTool>,
) : ToolSpec, LoadableToolSpec

/**
 * Mirrors Rust `FreeformTool`; serialized as Responses API `"custom"`.
 */
@Serializable
@SerialName("custom")
public data class FreeformTool(
    public val name: String,
    public val description: String,
    public val format: FreeformToolFormat,
) : ToolSpec

@Serializable
public data class FreeformToolFormat(
    public val type: String,
    public val syntax: String,
    public val definition: String,
)

/**
 * Mirrors Rust `LoadableToolSpec` returned by `tool_search_output`.
 */
@Serializable
public sealed interface LoadableToolSpec

/**
 * Mirrors Rust `ResponsesApiNamespaceTool`.
 */
@Serializable
public sealed interface ResponsesApiNamespaceTool

@Serializable
public data class ResponsesApiWebSearchFilters(
    @SerialName("allowed_domains")
    public val allowedDomains: List<String>? = null,
)

@Serializable
public data class ResponsesApiWebSearchUserLocation(
    public val type: WebSearchUserLocationType = WebSearchUserLocationType.Approximate,
    public val country: String? = null,
    public val region: String? = null,
    public val city: String? = null,
    public val timezone: String? = null,
)

@Serializable
public enum class WebSearchUserLocationType {
    @SerialName("approximate")
    Approximate,
}

@Serializable
public enum class WebSearchContextSize {
    @SerialName("low")
    Low,

    @SerialName("medium")
    Medium,

    @SerialName("high")
    High,
}
