package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.utils.applypatch.Patch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Complete input of a tool call whose result is still pending.
 *
 * Runtime-only input deltas are not represented here.
 */
@Serializable
public sealed interface PendingToolInvocation {
    /** Dynamic function call with decoded or losslessly retained arguments. */
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val namespace: String? = null,
        public val arguments: PendingFunctionArguments,
    ) : PendingToolInvocation

    /** Dynamic custom-tool call with freeform text input. */
    @Serializable
    @SerialName("custom")
    public data class Custom(
        public val name: String,
        public val namespace: String? = null,
        public val input: String,
    ) : PendingToolInvocation

    /** Parsed `apply_patch` input. */
    @Serializable
    @SerialName("apply_patch")
    public data class ApplyPatch(
        public val diff: Patch,
    ) : PendingToolInvocation

    /** Deferred tool-search input. */
    @Serializable
    @SerialName("tool_search")
    public data class ToolSearch(
        public val execution: PendingToolSearchExecution,
        public val query: String,
        public val limit: Int? = null,
    ) : PendingToolInvocation

    /** `view_image` input. */
    @Serializable
    @SerialName("image_view")
    public data class ImageView(
        public val path: String,
        public val detail: PendingImageDetail? = null,
        @SerialName("environment_id")
        public val environmentId: String? = null,
    ) : PendingToolInvocation

    /** Local or hosted image-generation input. */
    @Serializable
    @SerialName("image_generation")
    public data class ImageGeneration(
        public val request: PendingImageGenerationRequest,
    ) : PendingToolInvocation

    /** Local or hosted command-execution input. */
    @Serializable
    @SerialName("command_execution")
    public data class CommandExecution(
        public val action: PendingCommandExecutionAction,
    ) : PendingToolInvocation

    /** Multi-agent operation input. */
    @Serializable
    @SerialName("multi_agent")
    public data class MultiAgent(
        public val operation: PendingMultiAgentInvocation,
    ) : PendingToolInvocation

    /** `request_user_input` questions and timeout policy. */
    @Serializable
    @SerialName("request_user_input")
    public data class RequestUserInput(
        public val questions: List<PendingRequestUserInputQuestion>,
        @SerialName("auto_resolution_ms")
        public val autoResolutionMillis: Long? = null,
    ) : PendingToolInvocation

    /** Local or hosted web-search input. */
    @Serializable
    @SerialName("web_search")
    public data class WebSearch(
        public val source: PendingWebSearchSource,
        public val operations: List<PendingWebSearchOperation>,
        @SerialName("response_length")
        public val responseLength: PendingWebSearchResponseLength? = null,
    ) : PendingToolInvocation
}

/** Function-call arguments retained before a result is available. */
@Serializable
public sealed interface PendingFunctionArguments {
    /** Successfully decoded JSON arguments. */
    @Serializable
    @SerialName("json")
    public data class Json(
        public val value: JsonElement,
    ) : PendingFunctionArguments

    /** Original argument text retained when JSON decoding failed. */
    @Serializable
    @SerialName("invalid_json")
    public data class InvalidJson(
        public val raw: String,
    ) : PendingFunctionArguments
}
