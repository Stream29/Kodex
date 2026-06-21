package io.github.stream29.codex.lite.llmprovider

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
public sealed interface LlmTool {
    /**
     * Mirrors Rust `ResponsesApiTool`.
     */
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val description: String,
        public val strict: Boolean = false,
        @SerialName("defer_loading")
        public val deferLoading: Boolean? = null,
        public val parameters: ObjectPropertyDefinition,
    ) : LlmTool

    @Serializable
    @SerialName("namespace")
    public data class Namespace(
        public val name: String,
        public val description: String,
        public val tools: List<LlmNamespaceTool>,
    ) : LlmTool

    @Serializable
    @SerialName("tool_search")
    public data class ToolSearch(
        public val execution: String,
        public val description: String,
        public val parameters: ObjectPropertyDefinition,
    ) : LlmTool

    @Serializable
    @SerialName("image_generation")
    public data class ImageGeneration(
        @SerialName("output_format")
        public val outputFormat: String,
    ) : LlmTool

    @Serializable
    @SerialName("web_search")
    public data class WebSearch(
        @SerialName("external_web_access")
        public val externalWebAccess: Boolean? = null,
        public val filters: LlmWebSearchFilters? = null,
        @SerialName("user_location")
        public val userLocation: LlmWebSearchUserLocation? = null,
        @SerialName("search_context_size")
        public val searchContextSize: LlmWebSearchContextSize? = null,
        @SerialName("search_content_types")
        public val searchContentTypes: List<String>? = null,
    ) : LlmTool

    /**
     * Mirrors Rust `FreeformTool`.
     */
    @Serializable
    @SerialName("custom")
    public data class Custom(
        public val name: String,
        public val description: String,
        public val format: LlmCustomToolFormat,
    ) : LlmTool
}

/**
 * Mirrors loadable tool specs returned by `tool_search_output`.
 */
@Serializable
public sealed interface LlmLoadableToolSpec {
    /**
     * Mirrors Rust `ResponsesApiTool`.
     */
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val description: String,
        public val strict: Boolean = false,
        @SerialName("defer_loading")
        public val deferLoading: Boolean? = null,
        public val parameters: ObjectPropertyDefinition,
    ) : LlmLoadableToolSpec

    @Serializable
    @SerialName("namespace")
    public data class Namespace(
        public val name: String,
        public val description: String,
        public val tools: List<LlmLoadableNamespaceTool>,
    ) : LlmLoadableToolSpec
}

/**
 * Function entry inside a loadable namespace tool.
 */
@Serializable
public sealed interface LlmLoadableNamespaceTool {
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val description: String,
        public val strict: Boolean = false,
        @SerialName("defer_loading")
        public val deferLoading: Boolean? = null,
        public val parameters: ObjectPropertyDefinition,
    ) : LlmLoadableNamespaceTool
}

/**
 * Function entry inside `LlmTool.Namespace`; mirrors Rust `ResponsesApiTool`.
 */
@Serializable
public sealed interface LlmNamespaceTool {
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val description: String,
        public val strict: Boolean = false,
        @SerialName("defer_loading")
        public val deferLoading: Boolean? = null,
        public val parameters: ObjectPropertyDefinition,
    ) : LlmNamespaceTool
}

@Serializable
public data class LlmCustomToolFormat(
    public val type: String,
    public val syntax: String,
    public val definition: String,
)

@Serializable
public data class LlmWebSearchFilters(
    @SerialName("allowed_domains")
    public val allowedDomains: List<String>? = null,
)

@Serializable
public data class LlmWebSearchUserLocation(
    public val type: LlmWebSearchUserLocationType = LlmWebSearchUserLocationType.Approximate,
    public val country: String? = null,
    public val region: String? = null,
    public val city: String? = null,
    public val timezone: String? = null,
)

@Serializable
public enum class LlmWebSearchUserLocationType {
    @SerialName("approximate")
    Approximate,
}

@Serializable
public enum class LlmWebSearchContextSize {
    @SerialName("low")
    Low,

    @SerialName("medium")
    Medium,

    @SerialName("high")
    High,
}
