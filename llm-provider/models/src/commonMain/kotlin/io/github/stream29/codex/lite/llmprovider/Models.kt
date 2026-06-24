package io.github.stream29.codex.lite.llmprovider

import io.github.stream29.codex.lite.tool.contract.LoadableToolSpec
import io.github.stream29.codex.lite.tool.contract.ToolSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
public data class ModelsResponse(
    public val models: List<ModelInfo> = emptyList(),
)

/**
 * @property id Nullable because providers may omit this model alias; `null`
 * means that alias is absent, not that the model is invalid.
 * @property slug Nullable because providers may omit this model alias; `null`
 * means that alias is absent, not that the model is invalid.
 * @property name Nullable because providers may omit this model alias; `null`
 * means that alias is absent, not that the model is invalid.
 * @property displayName Nullable because providers may omit this model alias;
 * `null` means that alias is absent, not that the model is invalid.
 * @property title Nullable because providers may omit this model alias; `null`
 * means that alias is absent, not that the model is invalid.
 */
@Serializable
public data class ModelInfo(
    public val id: String? = null,
    public val slug: String? = null,
    public val name: String? = null,
    @SerialName("display_name")
    public val displayName: String? = null,
    public val title: String? = null,
)

/**
 * @property instructions Nullable because request instructions are optional;
 * `null` means omit the field.
 * @property previousResponseId Nullable because a request may start a new
 * response chain; `null` means no previous response is referenced.
 * @property reasoning Nullable because reasoning controls are optional; `null`
 * means use provider/session defaults.
 * @property serviceTier Nullable because service tier is optional; `null`
 * means use the provider default.
 * @property promptCacheKey Nullable because prompt cache affinity is optional;
 * `null` means no cache key is sent.
 * @property text Nullable because text controls are optional; `null` means use
 * provider/session defaults.
 */
@Serializable
public data class ResponsesApiRequest(
    public val model: String,
    public val input: List<ResponseItem>,
    public val instructions: String? = null,
    public val store: Boolean = false,
    public val stream: Boolean = false,
    @SerialName("previous_response_id")
    public val previousResponseId: String? = null,
    public val tools: List<ToolSpec> = emptyList(),
    @SerialName("tool_choice")
    public val toolChoice: ToolChoice = ToolChoice.Auto,
    @SerialName("parallel_tool_calls")
    public val parallelToolCalls: Boolean = false,
    public val reasoning: Reasoning? = null,
    public val include: Set<ResponseInclude> = emptySet(),
    @SerialName("service_tier")
    public val serviceTier: String? = null,
    @SerialName("prompt_cache_key")
    public val promptCacheKey: String? = null,
    public val text: TextControls? = null,
    @SerialName("client_metadata")
    public val clientMetadata: Map<String, String> = emptyMap(),
)

/**
 * @property id Nullable because partial or compatibility responses may omit id;
 * `null` means the provider did not include it.
 * @property usage Nullable because providers may omit token usage; `null`
 * means usage was not reported.
 * @property outputText Nullable because not every response includes flattened
 * output text; `null` means only structured output is available.
 * @property endTurn Nullable because the provider may omit turn-end metadata;
 * `null` means no turn-end value was reported.
 */
@Serializable
public data class Response(
    public val id: String? = null,
    public val output: List<ResponseItem> = emptyList(),
    public val usage: TokenUsage? = null,
    @SerialName("output_text")
    public val outputText: String? = null,
    @SerialName("end_turn")
    public val endTurn: Boolean? = null,
)

@Serializable
public sealed interface ResponsesStreamEvent {
    @Serializable
    @SerialName("response.created")
    public data class Created(
        public val response: Response,
    ) : ResponsesStreamEvent

    @Serializable
    @SerialName("response.in_progress")
    public data class InProgress(
        public val response: Response,
    ) : ResponsesStreamEvent

    /**
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
    ) : ResponsesStreamEvent

    @Serializable
    @SerialName("response.output_item.added")
    public data class OutputItemAdded(
        @SerialName("output_index")
        public val outputIndex: Long,
        public val item: ResponseItem,
    ) : ResponsesStreamEvent

    @Serializable
    @SerialName("response.output_item.done")
    public data class OutputItemDone(
        @SerialName("output_index")
        public val outputIndex: Long,
        public val item: ResponseItem,
    ) : ResponsesStreamEvent

    @Serializable
    @SerialName("response.completed")
    public data class Completed(
        public val response: Response,
    ) : ResponsesStreamEvent

    @Serializable
    @SerialName("response.failed")
    public data class Failed(
        public val response: FailedResponse,
    ) : ResponsesStreamEvent

    @Serializable
    @SerialName("response.incomplete")
    public data class Incomplete(
        public val response: IncompleteResponse,
    ) : ResponsesStreamEvent

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
    ) : ResponsesStreamEvent

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
    ) : ResponsesStreamEvent

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
    ) : ResponsesStreamEvent

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
    ) : ResponsesStreamEvent

    /**
     * @property itemId Nullable because custom-tool delta frames may omit it;
     * `null` means the stream did not repeat the item id on this chunk.
     * @property callId Nullable because custom-tool delta frames may omit it;
     * `null` means the stream did not repeat the call id on this chunk.
     */
    @Serializable
    @SerialName("response.custom_tool_call_input.delta")
    public data class ToolCallInputDelta(
        @SerialName("item_id")
        public val itemId: String? = null,
        @SerialName("call_id")
        public val callId: String? = null,
        public val delta: String,
    ) : ResponsesStreamEvent

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
    ) : ResponsesStreamEvent

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
    ) : ResponsesStreamEvent

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
    ) : ResponsesStreamEvent

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
    ) : ResponsesStreamEvent

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

/**
 * @property instructions Nullable because compaction instructions are optional;
 * `null` means omit the field.
 * @property reasoning Nullable because reasoning controls are optional; `null`
 * means use provider/session defaults.
 * @property serviceTier Nullable because service tier is optional; `null`
 * means use the provider default.
 * @property promptCacheKey Nullable because prompt cache affinity is optional;
 * `null` means no cache key is sent.
 * @property text Nullable because text controls are optional; `null` means use
 * provider/session defaults.
 */
@Serializable
public data class CompactionInput(
    public val model: String,
    public val input: List<ResponseItem>,
    public val instructions: String? = null,
    public val tools: List<ToolSpec> = emptyList(),
    @SerialName("parallel_tool_calls")
    public val parallelToolCalls: Boolean = false,
    public val reasoning: Reasoning? = null,
    @SerialName("service_tier")
    public val serviceTier: String? = null,
    @SerialName("prompt_cache_key")
    public val promptCacheKey: String? = null,
    public val text: TextControls? = null,
)

@Serializable
public data class CompactionResponse(
    public val output: List<ResponseItem> = emptyList(),
)

/**
 * Mirrors Rust `ResponseItem`.
 *
 * Unknown wire variants decode to `Other`.
 */
@Serializable
public sealed interface ResponseItem {
    /**
     * @property id Nullable because providers may omit message ids; `null`
     * means no id was provided.
     * @property phase Nullable because providers may omit message phase; `null`
     * means no phase was provided.
     */
    @Serializable
    @SerialName("message")
    public data class Message(
        public val id: String? = null,
        public val role: MessageRole,
        public val content: List<ContentItem>,
        public val phase: MessagePhase? = null,
    ) : ResponseItem

    @Serializable
    @SerialName("agent_message")
    public data class AgentMessage(
        public val author: String,
        public val recipient: String,
        public val content: List<AgentMessageInputContent>,
    ) : ResponseItem

    /**
     * @property id Nullable because providers may omit reasoning ids; `null`
     * means no id was provided.
     * @property content Nullable because reasoning items may only contain
     * summary; `null` means no full reasoning content was provided.
     * @property encryptedContent Nullable because encrypted reasoning is
     * optional; `null` means no encrypted payload was provided.
     */
    @Serializable
    @SerialName("reasoning")
    public data class Reasoning(
        public val id: String? = null,
        public val summary: List<ReasoningItemReasoningSummary> = emptyList(),
        public val content: List<ReasoningItemContent>? = null,
        @SerialName("encrypted_content")
        public val encryptedContent: String? = null,
    ) : ResponseItem

    /**
     * @property id Nullable because hosted shell-call metadata may omit id;
     * `null` means no item id was provided.
     * @property callId Nullable because hosted shell-call metadata may omit call
     * id; `null` means no call id was provided.
     */
    @Serializable
    @SerialName("local_shell_call")
    public data class LocalShellCall(
        public val id: String? = null,
        @SerialName("call_id")
        public val callId: String? = null,
        public val status: LocalShellStatus,
        public val action: LocalShellAction,
    ) : ResponseItem

    /**
     * @property id Nullable because function-call item metadata may omit id;
     * `null` means no item id was provided.
     * @property namespace Nullable because plain functions are not namespaced;
     * `null` means route by function name only.
     */
    @Serializable
    @SerialName("function_call")
    public data class FunctionCall(
        public val id: String? = null,
        public val name: String,
        public val namespace: String? = null,
        public val arguments: String,
        @SerialName("call_id")
        public val callId: String,
    ) : ResponseItem

    /**
     * @property id Nullable because tool-search call metadata may omit item id;
     * `null` means no item id was provided.
     * @property callId Nullable because some tool-search call items may omit
     * call id; `null` means no originating call id is available.
     * @property status Nullable because tool-search call metadata may omit
     * status; `null` means no status was provided.
     */
    @Serializable
    @SerialName("tool_search_call")
    public data class ToolSearchCall(
        public val id: String? = null,
        @SerialName("call_id")
        public val callId: String? = null,
        public val status: String? = null,
        public val execution: String,
        public val arguments: JsonElement,
    ) : ResponseItem

    @Serializable
    @SerialName("function_call_output")
    public data class FunctionCallOutput(
        @SerialName("call_id")
        public val callId: String,
        public val output: FunctionCallOutputPayload,
    ) : ResponseItem

    /**
     * Mirrors Rust `ResponseInputItem::McpToolCallOutput`.
     *
     * `llm-provider:api` converts this to `function_call_output` before sending
     * Responses API requests.
     */
    @Serializable
    @SerialName("mcp_tool_call_output")
    public data class McpToolCallOutput(
        @SerialName("call_id")
        public val callId: String,
        public val output: CallToolResult,
    ) : ResponseItem

    /**
     * @property id Nullable because custom-tool call metadata may omit item id;
     * `null` means no item id was provided.
     * @property status Nullable because custom-tool call metadata may omit
     * status; `null` means no status was provided.
     */
    @Serializable
    @SerialName("custom_tool_call")
    public data class CustomToolCall(
        public val id: String? = null,
        public val status: String? = null,
        @SerialName("call_id")
        public val callId: String,
        public val name: String,
        public val input: String,
    ) : ResponseItem

    /**
     * @property name Nullable because tool-call outputs may only need `call_id`;
     * `null` means no redundant tool name is sent.
     */
    @Serializable
    @SerialName("custom_tool_call_output")
    public data class CustomToolCallOutput(
        @SerialName("call_id")
        public val callId: String,
        public val name: String? = null,
        public val output: FunctionCallOutputPayload,
    ) : ResponseItem

    /**
     * @property callId Nullable because historical or normalized tool-search output
     * items may lack it; `null` means no originating call id is available.
     */
    @Serializable
    @SerialName("tool_search_output")
    public data class ToolSearchOutput(
        @SerialName("call_id")
        public val callId: String? = null,
        public val status: String,
        public val execution: String,
        public val tools: List<LoadableToolSpec>,
    ) : ResponseItem

    /**
     * @property id Nullable because web-search call metadata may omit item id;
     * `null` means no item id was provided.
     * @property status Nullable because web-search call metadata may omit
     * status; `null` means no status was provided.
     * @property action Nullable because web-search call metadata may omit
     * action details; `null` means no action was exposed.
     */
    @Serializable
    @SerialName("web_search_call")
    public data class WebSearchCall(
        public val id: String? = null,
        public val status: String? = null,
        public val action: WebSearchAction? = null,
    ) : ResponseItem

    /**
     * @property revisedPrompt Nullable because image generation may not revise the
     * prompt; `null` means no revised prompt was returned.
     */
    @Serializable
    @SerialName("image_generation_call")
    public data class ImageGenerationCall(
        public val id: String,
        public val status: String,
        @SerialName("revised_prompt")
        public val revisedPrompt: String? = null,
        public val result: String,
    ) : ResponseItem

    /**
     * Preserves the Kotlin split between `compaction` and `compaction_summary`.
     */
    @Serializable
    @SerialName("compaction")
    public data class Compaction(
        @SerialName("encrypted_content")
        public val encryptedContent: String,
    ) : ResponseItem

    /**
     * Preserves the Kotlin split between `compaction` and `compaction_summary`.
     */
    @Serializable
    @SerialName("compaction_summary")
    public data class CompactionSummary(
        @SerialName("encrypted_content")
        public val encryptedContent: String,
    ) : ResponseItem

    @Serializable
    @SerialName("compaction_trigger")
    public data object CompactionTrigger : ResponseItem

    /**
     * @property encryptedContent Nullable because context compaction triggers can be
     * structural; `null` means no encrypted payload accompanies the item.
     */
    @Serializable
    @SerialName("context_compaction")
    public data class ContextCompaction(
        @SerialName("encrypted_content")
        public val encryptedContent: String? = null,
    ) : ResponseItem

    @Serializable
    @SerialName("other")
    public data object Other : ResponseItem
}

@Serializable
public sealed interface ContentItem {
    @Serializable
    @SerialName("input_text")
    public data class InputText(public val text: String) : ContentItem

    /**
     * @property detail Nullable because image detail can be omitted from the wire;
     * `null` means the provider/default detail should apply.
     */
    @Serializable
    @SerialName("input_image")
    public data class InputImage(
        @SerialName("image_url")
        public val imageUrl: String,
        public val detail: ImageDetail? = null,
    ) : ContentItem

    @Serializable
    @SerialName("output_text")
    public data class OutputText(public val text: String) : ContentItem
}

/**
 * Mirrors Rust `AgentMessageInputContent`.
 */
@Serializable
public sealed interface AgentMessageInputContent {
    @Serializable
    @SerialName("input_text")
    public data class InputText(public val text: String) : AgentMessageInputContent

    @Serializable
    @SerialName("encrypted_content")
    public data class EncryptedContent(
        @SerialName("encrypted_content")
        public val encryptedContent: String,
    ) : AgentMessageInputContent
}

@Serializable
public enum class ImageDetail {
    @SerialName("auto")
    Auto,

    @SerialName("low")
    Low,

    @SerialName("high")
    High,

    @SerialName("original")
    Original,
}

@Serializable
public enum class MessagePhase {
    @SerialName("commentary")
    Commentary,

    @SerialName("final_answer")
    FinalAnswer,
}

@Serializable
public sealed interface ReasoningItemReasoningSummary {
    @Serializable
    @SerialName("summary_text")
    public data class SummaryText(public val text: String) : ReasoningItemReasoningSummary
}

/**
 * Mirrors Rust `ReasoningItemContent`.
 */
@Serializable
public sealed interface ReasoningItemContent {
    @Serializable
    @SerialName("reasoning_text")
    public data class ReasoningText(public val text: String) : ReasoningItemContent

    @Serializable
    @SerialName("text")
    public data class Text(public val text: String) : ReasoningItemContent
}

/**
 * Status values for `ResponseItem.LocalShellCall`.
 */
@Serializable
public enum class LocalShellStatus {
    @SerialName("completed")
    Completed,

    @SerialName("in_progress")
    InProgress,

    @SerialName("incomplete")
    Incomplete,
}

/**
 * Action payload for `ResponseItem.LocalShellCall`.
 */
@Serializable
public sealed interface LocalShellAction {
    /**
     * @property timeoutMs Nullable because timeout is optional; `null` means use
     * the runtime default.
     * @property workingDirectory Nullable because cwd override is optional;
     * `null` means use the runtime default working directory.
     * @property env Nullable because environment overrides are optional; `null`
     * means send no environment override map.
     * @property user Nullable because user override is optional; `null` means
     * use the runtime default user.
     */
    @Serializable
    @SerialName("exec")
    public data class Exec(
        public val command: List<String>,
        @SerialName("timeout_ms")
        public val timeoutMs: Long? = null,
        @SerialName("working_directory")
        public val workingDirectory: String? = null,
        public val env: Map<String, String>? = null,
        public val user: String? = null,
    ) : LocalShellAction
}

/**
 * Action payload for `ResponseItem.WebSearchCall`.
 *
 * Unknown wire variants decode to `Other`.
 */
@Serializable
public sealed interface WebSearchAction {
    /**
     * @property query Nullable because search actions may carry a single query
     * or a list; `null` means no single-query representation was provided.
     * @property queries Nullable because search actions may carry a single query
     * or a list; `null` means no multi-query representation was provided.
     */
    @Serializable
    @SerialName("search")
    public data class Search(
        public val query: String? = null,
        public val queries: List<String>? = null,
    ) : WebSearchAction

    /**
     * @property url Nullable because hosted search actions may redact or omit it;
     * `null` means no page URL was exposed.
     */
    @Serializable
    @SerialName("open_page")
    public data class OpenPage(
        public val url: String? = null,
    ) : WebSearchAction

    /**
     * @property url Nullable because hosted search actions may redact or omit
     * it; `null` means no page URL was exposed.
     * @property pattern Nullable because hosted search actions may omit the
     * pattern; `null` means no find pattern was exposed.
     */
    @Serializable
    @SerialName("find_in_page")
    public data class FindInPage(
        public val url: String? = null,
        public val pattern: String? = null,
    ) : WebSearchAction

    @Serializable
    @SerialName("other")
    public data object Other : WebSearchAction
}

/**
 * @property success Nullable because legacy function outputs may omit outcome
 * metadata; `null` means success was not explicitly reported.
 */
@Serializable(with = FunctionCallOutputPayloadSerializer::class)
public data class FunctionCallOutputPayload(
    public val body: FunctionCallOutputBody = FunctionCallOutputBody.Text(""),
    public val success: Boolean? = null,
) {
    public companion object {
        public fun fromText(text: String): FunctionCallOutputPayload =
            FunctionCallOutputPayload(FunctionCallOutputBody.Text(text))

        public fun fromContentItems(
            contentItems: List<FunctionCallOutputContentItem>,
        ): FunctionCallOutputPayload =
            FunctionCallOutputPayload(FunctionCallOutputBody.ContentItems(contentItems))
    }
}

public sealed interface FunctionCallOutputBody {
    public data class Text(public val text: String) : FunctionCallOutputBody
    public data class ContentItems(
        public val items: List<FunctionCallOutputContentItem>,
    ) : FunctionCallOutputBody
}

/**
 * Mirrors MCP `CallToolResult`.
 *
 * `toFunctionCallOutputPayload` follows Rust `CallToolResult::into_function_call_output_payload`.
 */
/**
 * @property structuredContent Nullable because MCP results may omit structured
 * content; `null` means no structured content was provided.
 * @property isError Nullable because MCP results may omit the error flag;
 * `null` means the result did not explicitly report error status.
 * @property meta Nullable because MCP results may omit metadata; `null` means
 * no metadata was provided.
 */
@Serializable
public data class CallToolResult(
    public val content: List<JsonElement>,
    public val structuredContent: JsonElement? = null,
    public val isError: Boolean? = null,
    @SerialName("_meta")
    public val meta: JsonElement? = null,
) {
    public fun toFunctionCallOutputPayload(json: Json): FunctionCallOutputPayload {
        val success = isError != true
        val structured = structuredContent
        if (structured != null && structured !is JsonNull) {
            return FunctionCallOutputPayload(
                body = FunctionCallOutputBody.Text(json.encodeToString(JsonElement.serializer(), structured)),
                success = success,
            )
        }

        val contentItems = content.toFunctionCallOutputContentItems(json)
        return FunctionCallOutputPayload(
            body = contentItems?.let(FunctionCallOutputBody::ContentItems)
                ?: FunctionCallOutputBody.Text(json.encodeToString(contentSerializer, content)),
            success = success,
        )
    }
}

@Serializable
public sealed interface FunctionCallOutputContentItem {
    @Serializable
    @SerialName("input_text")
    public data class InputText(public val text: String) : FunctionCallOutputContentItem

    /**
     * @property detail Nullable because image detail is optional in function-call
     * output content; `null` means default image detail should apply.
     */
    @Serializable
    @SerialName("input_image")
    public data class InputImage(
        @SerialName("image_url")
        public val imageUrl: String,
        public val detail: ImageDetail? = null,
    ) : FunctionCallOutputContentItem

    @Serializable
    @SerialName("encrypted_content")
    public data class EncryptedContent(
        @SerialName("encrypted_content")
        public val encryptedContent: String,
    ) : FunctionCallOutputContentItem
}

private val contentSerializer = ListSerializer(JsonElement.serializer())

/**
 * @return Nullable because only mixed content containing images needs content
 * item conversion; `null` means callers should fall back to serialized text.
 */
private fun List<JsonElement>.toFunctionCallOutputContentItems(
    json: Json,
): List<FunctionCallOutputContentItem>? {
    var sawImage = false
    val items = map { content ->
        val contentObject = content as? JsonObject
        when (contentObject?.string("type")) {
            "text" -> FunctionCallOutputContentItem.InputText(
                text = contentObject.string("text").orEmpty(),
            )

            "image" -> {
                sawImage = true
                val data = contentObject.string("data").orEmpty()
                val imageUrl = if (data.startsWith("data:")) {
                    data
                } else {
                    val mimeType = contentObject.string("mimeType")
                        ?: contentObject.string("mime_type")
                        ?: "application/octet-stream"
                    "data:$mimeType;base64,$data"
                }
                FunctionCallOutputContentItem.InputImage(
                    imageUrl = imageUrl,
                    detail = contentObject["_meta"]
                        ?.let { it as? JsonObject }
                        ?.string("codex/imageDetail")
                        ?.let(::imageDetailFromWireName)
                        ?: ImageDetail.High,
                )
            }

            else -> FunctionCallOutputContentItem.InputText(
                text = json.encodeToString(JsonElement.serializer(), content),
            )
        }
    }

    return items.takeIf { sawImage }
}

/**
 * @return Nullable because the JSON member may be absent, non-primitive, or
 * JSON null; `null` means no string value is available.
 */
private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull

/**
 * @return Nullable because the wire value may not be recognized; `null` means
 * no `ImageDetail` mapping exists.
 */
private fun imageDetailFromWireName(value: String): ImageDetail? =
    when (value) {
        "auto" -> ImageDetail.Auto
        "low" -> ImageDetail.Low
        "high" -> ImageDetail.High
        "original" -> ImageDetail.Original
        else -> null
    }

@Serializable
public enum class MessageRole(public val wireName: String) {
    @SerialName("user")
    User("user"),

    @SerialName("assistant")
    Assistant("assistant"),

    @SerialName("system")
    System("system"),

    @SerialName("tool")
    Tool("tool"),
}

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
 * @property effort Nullable because reasoning effort is optional; `null` means
 * omit the setting and use the provider default.
 * @property summary Nullable because reasoning summary is optional; `null`
 * means omit the setting and use the provider default.
 * @property context Nullable because reasoning context is optional; `null`
 * means omit the setting and use the provider default.
 */
@Serializable
public data class Reasoning(
    public val effort: ReasoningEffort? = null,
    public val summary: ReasoningSummary? = null,
    public val context: ReasoningContext? = null,
)

@Serializable
public enum class ReasoningEffort {
    @SerialName("minimal")
    Minimal,

    @SerialName("low")
    Low,

    @SerialName("medium")
    Medium,

    @SerialName("high")
    High,
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
 * @property verbosity Nullable because text verbosity is optional; `null` means
 * omit the setting and use the provider default.
 * @property format Nullable because text output format is optional; `null`
 * means omit the setting and request plain model output.
 */
@Serializable
public data class TextControls(
    public val verbosity: OpenAiVerbosity? = null,
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

/**
 * @property inputTokens Nullable because providers may omit this usage counter;
 * `null` means input tokens were not reported.
 * @property outputTokens Nullable because providers may omit this usage counter;
 * `null` means output tokens were not reported.
 * @property totalTokens Nullable because providers may omit this usage counter;
 * `null` means total tokens were not reported.
 */
@Serializable
public data class TokenUsage(
    @SerialName("input_tokens")
    public val inputTokens: Long? = null,
    @SerialName("output_tokens")
    public val outputTokens: Long? = null,
    @SerialName("total_tokens")
    public val totalTokens: Long? = null,
)

public fun textInput(text: String): List<ResponseItem> =
    messageInput(MessageRole.User, text)

public fun messageInput(role: MessageRole, content: String): List<ResponseItem> =
    listOf(
        ResponseItem.Message(
            role = role,
            content = listOf(ContentItem.InputText(content)),
        ),
    )
