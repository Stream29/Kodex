package io.github.stream29.kodex.openai

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
public sealed interface ToolSpec

/**
 * Mirrors Rust `ResponsesApiTool` from `shared-context/codex/codex-rs/tools/src/responses_api.rs`.
 *
 * @property deferLoading Nullable because the wire format omits false/default;
 * `null` means the tool is loaded normally in the initial tool list.
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
