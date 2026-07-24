package io.github.stream29.codex.lite.openai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * @property instructions Request instructions. The empty default is omitted
 * from the wire.
 * @property previousResponseId Nullable because a request may start a new
 * response chain; `null` means no previous response is referenced.
 * @property reasoning Reasoning controls. The default value is omitted from
 * the wire.
 * @property serviceTier Service tier selection. [ServiceTier.Default] is
 * omitted from the wire.
 * @property promptCacheKey Nullable because prompt cache affinity is optional;
 * `null` means no cache key is sent.
 * @property text Text controls. The default value is omitted from the wire.
 * @property clientMetadata Nullable because a plain Responses API request may
 * not belong to a Codex session; `null` means `client_metadata` is omitted.
 */
@Serializable
public data class ResponsesApiRequest(
    public val model: OpenAiModelId,
    public val input: List<ResponseItem>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val instructions: String = "",
    public val store: Boolean = false,
    @SerialName("previous_response_id")
    public val previousResponseId: String? = null,
    public val tools: List<ToolSpec> = emptyList(),
    @SerialName("tool_choice")
    public val toolChoice: ToolChoice = ToolChoice.Auto,
    @SerialName("parallel_tool_calls")
    public val parallelToolCalls: Boolean = false,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val reasoning: Reasoning = Reasoning(),
    public val include: Set<ResponseInclude> = emptySet(),
    @SerialName("service_tier")
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val serviceTier: ServiceTier = ServiceTier.Default,
    @SerialName("prompt_cache_key")
    public val promptCacheKey: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val text: TextControls = TextControls(),
    @SerialName("client_metadata")
    public val clientMetadata: CodexResponsesClientMetadata? = null,
) {
    public val stream: Boolean = true
}

/**
 * @property usage Nullable because providers may omit token usage; `null`
 * means usage was not reported.
 * @property outputText Nullable because not every response includes flattened
 * output text; `null` means only structured output is available.
 * @property endTurn Nullable because the provider may omit turn-end metadata;
 * `null` means no turn-end value was reported.
 */
@Serializable
public data class Response(
    public val id: String,
    public val output: List<ResponseItem> = emptyList(),
    public val usage: TokenUsage? = null,
    @SerialName("output_text")
    public val outputText: String? = null,
    @SerialName("end_turn")
    public val endTurn: Boolean? = null,
)

@Serializable(with = ResponsesStreamEventSerializer::class)
public sealed interface ResponsesStreamEvent {
    /** Serialization helper for every event variant except [Other]. */
    @Serializable
    public sealed interface Known : ResponsesStreamEvent

    @Serializable
    @SerialName("response.created")
    public data class Created(
        public val response: Response,
    ) : Known

    @Serializable
    @SerialName("response.in_progress")
    public data class InProgress(
        public val response: Response,
    ) : Known

    /**
     * Mirrors Rust `ResponsesStreamEvent`, where metadata frames are decoded
     * through optional wire fields before callers inspect the specific metadata
     * keys they care about.
     *
     * @property responseId Nullable because metadata frames may omit it; `null`
     * means the frame did not carry a response id.
     * @property headers Nullable because metadata frames may omit headers;
     * `null` means no headers were carried.
     * @property metadata Nullable because metadata frames may omit user metadata;
     * `null` means no metadata object was carried.
     */
    @Serializable
    @SerialName("response.metadata")
    public data class Metadata(
        @SerialName("response_id")
        public val responseId: String? = null,
        public val headers: JsonObject? = null,
        public val metadata: JsonObject? = null,
    ) : Known

    @Serializable
    @SerialName("response.output_item.added")
    public data class OutputItemAdded(
        @SerialName("output_index")
        public val outputIndex: Long,
        public val item: ResponseItem,
    ) : Known

    @Serializable
    @SerialName("response.output_item.done")
    public data class OutputItemDone(
        @SerialName("output_index")
        public val outputIndex: Long,
        public val item: ResponseItem,
    ) : Known

    @Serializable
    @SerialName("response.web_search_call.in_progress")
    public data class WebSearchCallInProgress(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
    ) : Known

    @Serializable
    @SerialName("response.web_search_call.searching")
    public data class WebSearchCallSearching(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
    ) : Known

    @Serializable
    @SerialName("response.web_search_call.completed")
    public data class WebSearchCallCompleted(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
    ) : Known

    @Serializable
    @SerialName("response.completed")
    public data class Completed(
        public val response: Response,
    ) : Known

    @Serializable
    @SerialName("response.failed")
    public data class Failed(
        public val response: FailedResponse,
    ) : Known

    @Serializable
    @SerialName("response.incomplete")
    public data class Incomplete(
        public val response: IncompleteResponse,
    ) : Known

    @Serializable
    @SerialName("response.content_part.added")
    public data class ContentPartAdded(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        @SerialName("content_index")
        public val contentIndex: Long,
        public val part: ContentItem,
    ) : Known

    @Serializable
    @SerialName("response.content_part.done")
    public data class ContentPartDone(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        @SerialName("content_index")
        public val contentIndex: Long,
        public val part: ContentItem,
    ) : Known

    @Serializable
    @SerialName("response.output_text.delta")
    public data class OutputTextDelta(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        @SerialName("content_index")
        public val contentIndex: Long,
        public val delta: String,
    ) : Known

    @Serializable
    @SerialName("response.output_text.done")
    public data class OutputTextDone(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        @SerialName("content_index")
        public val contentIndex: Long,
        public val text: String,
    ) : Known

    /**
     * Mirrors Rust `ResponseEvent::ToolCallInputDelta`: `item_id` and
     * `call_id` arrive as optional wire fields, and consumers should require
     * at least one identifier before treating the event as an actionable tool
     * call delta.
     *
     * @property itemId Nullable because some custom-tool delta frames only
     * carry `call_id`; `null` means no item id was repeated on this chunk.
     * @property callId Nullable because some custom-tool delta frames only
     * carry `item_id`; `null` means no call id was repeated on this chunk.
     */
    @Serializable
    @SerialName("response.custom_tool_call_input.delta")
    public data class ToolCallInputDelta(
        @SerialName("item_id")
        public val itemId: String? = null,
        @SerialName("call_id")
        public val callId: String? = null,
        public val delta: String,
    ) : Known

    @Serializable
    @SerialName("response.reasoning_summary_text.delta")
    public data class ReasoningSummaryTextDelta(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        public val delta: String,
        @SerialName("summary_index")
        public val summaryIndex: Long,
    ) : Known

    @Serializable
    @SerialName("response.reasoning_summary_text.done")
    public data class ReasoningSummaryTextDone(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        public val text: String,
        @SerialName("summary_index")
        public val summaryIndex: Long,
    ) : Known

    @Serializable
    @SerialName("response.reasoning_text.delta")
    public data class ReasoningTextDelta(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        public val delta: String,
        @SerialName("content_index")
        public val contentIndex: Long,
    ) : Known

    @Serializable
    @SerialName("response.reasoning_text.done")
    public data class ReasoningTextDone(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        public val text: String,
        @SerialName("content_index")
        public val contentIndex: Long,
    ) : Known

    @Serializable
    @SerialName("response.reasoning_summary_part.added")
    public data class ReasoningSummaryPartAdded(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        @SerialName("summary_index")
        public val summaryIndex: Long,
        public val part: ReasoningItemReasoningSummary,
    ) : Known

    /**
     * A server event outside the currently modeled Responses stream protocol.
     * The raw payload remains available to stream observers and is never sent
     * back to the API as an input event.
     */
    public data class Other(
        public val payload: JsonElement,
    ) : ResponsesStreamEvent
}

/**
 * @property error Nullable because failed response frames can omit structured error
 * details; `null` means no structured error body was provided.
 */
@Serializable
public data class FailedResponse(
    public val error: ResponseError? = null,
)

/**
 * @property incompleteDetails Nullable because incomplete responses may omit a
 * reason object; `null` means the provider did not explain the incompletion.
 */
@Serializable
public data class IncompleteResponse(
    @SerialName("incomplete_details")
    public val incompleteDetails: IncompleteDetails? = null,
)

/**
 * @property reason Nullable because provider incomplete-detail objects may be
 * empty; `null` means the reason is unknown.
 */
@Serializable
public data class IncompleteDetails(
    public val reason: String? = null,
)

/**
 * Mirrors Rust SSE `Error`, which treats response error fields as optional so
 * `response.failed` handling can preserve fallback behavior for partial or
 * compatibility error payloads.
 *
 * @property message Nullable because provider errors may omit message; `null`
 * means no message string was provided.
 * @property code Nullable because provider errors may omit code; `null` means
 * no code was provided.
 * @property type Nullable because provider errors may omit type; `null` means
 * no type was provided.
 */
@Serializable
public data class ResponseError(
    public val message: String? = null,
    public val code: String? = null,
    public val type: String? = null,
)

@Serializable(with = ToolChoiceSerializer::class)
public sealed interface ToolChoice {
    public val wireName: String

    public data object Auto : ToolChoice {
        override val wireName: String = "auto"
    }

    public data object None : ToolChoice {
        override val wireName: String = "none"
    }

    public data object Required : ToolChoice {
        override val wireName: String = "required"
    }
}

@Serializable
public enum class ResponseInclude(public val wireName: String) {
    @SerialName("reasoning.encrypted_content")
    ReasoningEncryptedContent("reasoning.encrypted_content"),
}

/**
 * @property effort Reasoning effort. The default value is omitted from the
 * wire.
 * @property summary Reasoning summary policy. The default value is omitted
 * from the wire.
 * @property context Reasoning context policy. The default value is omitted
 * from the wire.
 */
@Serializable
public data class Reasoning(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val effort: ReasoningEffort = ReasoningEffort.Medium,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val summary: ReasoningSummary = ReasoningSummary.Auto,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val context: ReasoningContext = ReasoningContext.Auto,
)

/**
 * A reasoning strength accepted by Codex model metadata or request controls.
 *
 * The backend may introduce model-defined strengths. [Custom] preserves their
 * wire value instead of rejecting an otherwise usable model catalog.
 */
@Serializable(with = ReasoningEffortSerializer::class)
public sealed interface ReasoningEffort {
    /** Exact string representation used by Codex and the Responses API. */
    public val wireName: String

    /** Built-in effort values understood by this client. */
    public sealed interface Known : ReasoningEffort

    public data object None : Known {
        override val wireName: String = "none"
    }

    public data object Minimal : Known {
        override val wireName: String = "minimal"
    }

    public data object Low : Known {
        override val wireName: String = "low"
    }

    public data object Medium : Known {
        override val wireName: String = "medium"
    }

    public data object High : Known {
        override val wireName: String = "high"
    }

    public data object XHigh : Known {
        override val wireName: String = "xhigh"
    }

    public data object Max : Known {
        override val wireName: String = "max"
    }

    public data object Ultra : Known {
        override val wireName: String = "ultra"
    }

    /** A future server-defined reasoning strength. */
    public data class Custom(
        override val wireName: String,
    ) : ReasoningEffort
}

@Serializable
public enum class ReasoningSummary {
    @SerialName("auto")
    Auto,

    @SerialName("concise")
    Concise,

    @SerialName("detailed")
    Detailed,
}

@Serializable
public enum class ReasoningContext {
    @SerialName("auto")
    Auto,

    @SerialName("current_turn")
    CurrentTurn,

    @SerialName("all_turns")
    AllTurns,
}

/**
 * @property verbosity Text verbosity. The default value is omitted from the
 * wire.
 * @property format Nullable because text output format is optional; `null`
 * means omit the setting and request plain model output.
 */
@Serializable
public data class TextControls(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val verbosity: OpenAiVerbosity = OpenAiVerbosity.Medium,
    public val format: TextFormat? = null,
)

@Serializable
public enum class OpenAiVerbosity {
    @SerialName("low")
    Low,

    @SerialName("medium")
    Medium,

    @SerialName("high")
    High,
}

@Serializable
public data class TextFormat(
    public val name: String,
    public val schema: JsonObject,
    public val strict: Boolean = true,
    public val type: TextFormatType = TextFormatType.JsonSchema,
)

@Serializable
public enum class TextFormatType {
    @SerialName("json_schema")
    JsonSchema,
}

@Serializable
public data class TokenUsage(
    @SerialName("input_tokens")
    public val inputTokens: Long,
    @SerialName("output_tokens")
    public val outputTokens: Long,
    @SerialName("total_tokens")
    public val totalTokens: Long,
)
