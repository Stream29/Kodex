package io.github.stream29.codex.lite.llmprovider

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
public data class LlmModels(
    public val models: List<LlmModel> = emptyList(),
)

@Serializable
public data class LlmModel(
    public val id: String? = null,
    public val slug: String? = null,
    public val name: String? = null,
    @SerialName("display_name")
    public val displayName: String? = null,
    public val title: String? = null,
)

@Serializable
public data class LlmResponseRequest(
    public val model: String,
    public val input: List<LlmResponseItem>,
    public val instructions: String? = null,
    public val store: Boolean = false,
    public val stream: Boolean = false,
    @SerialName("previous_response_id")
    public val previousResponseId: String? = null,
    public val tools: List<LlmTool> = emptyList(),
    @SerialName("tool_choice")
    public val toolChoice: LlmToolChoice = LlmToolChoice.Auto,
    @SerialName("parallel_tool_calls")
    public val parallelToolCalls: Boolean = false,
    public val reasoning: LlmReasoning? = null,
    public val include: Set<LlmResponseInclude> = emptySet(),
    @SerialName("service_tier")
    public val serviceTier: String? = null,
    @SerialName("prompt_cache_key")
    public val promptCacheKey: String? = null,
    public val text: LlmTextControls? = null,
    @SerialName("client_metadata")
    public val clientMetadata: Map<String, String> = emptyMap(),
)

@Serializable
public data class LlmResponse(
    public val id: String? = null,
    public val output: List<LlmResponseItem> = emptyList(),
    public val usage: LlmTokenUsage? = null,
    @SerialName("output_text")
    public val outputText: String? = null,
    @SerialName("end_turn")
    public val endTurn: Boolean? = null,
)

@Serializable
public sealed interface LlmResponseStreamEvent {
    @Serializable
    @SerialName("response.created")
    public data class Created(
        public val response: LlmResponse,
    ) : LlmResponseStreamEvent

    @Serializable
    @SerialName("response.in_progress")
    public data class InProgress(
        public val response: LlmResponse,
    ) : LlmResponseStreamEvent

    @Serializable
    @SerialName("response.metadata")
    public data class Metadata(
        @SerialName("response_id")
        public val responseId: String? = null,
        public val headers: JsonObject? = null,
        public val metadata: JsonObject? = null,
    ) : LlmResponseStreamEvent

    @Serializable
    @SerialName("response.output_item.added")
    public data class OutputItemAdded(
        @SerialName("output_index")
        public val outputIndex: Long,
        public val item: LlmResponseItem,
    ) : LlmResponseStreamEvent

    @Serializable
    @SerialName("response.output_item.done")
    public data class OutputItemDone(
        @SerialName("output_index")
        public val outputIndex: Long,
        public val item: LlmResponseItem,
    ) : LlmResponseStreamEvent

    @Serializable
    @SerialName("response.completed")
    public data class Completed(
        public val response: LlmResponse,
    ) : LlmResponseStreamEvent

    @Serializable
    @SerialName("response.failed")
    public data class Failed(
        public val response: LlmFailedResponse,
    ) : LlmResponseStreamEvent

    @Serializable
    @SerialName("response.incomplete")
    public data class Incomplete(
        public val response: LlmIncompleteResponse,
    ) : LlmResponseStreamEvent

    @Serializable
    @SerialName("response.content_part.added")
    public data class ContentPartAdded(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        @SerialName("content_index")
        public val contentIndex: Long,
        public val part: LlmContentItem,
    ) : LlmResponseStreamEvent

    @Serializable
    @SerialName("response.content_part.done")
    public data class ContentPartDone(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        @SerialName("content_index")
        public val contentIndex: Long,
        public val part: LlmContentItem,
    ) : LlmResponseStreamEvent

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
    ) : LlmResponseStreamEvent

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
    ) : LlmResponseStreamEvent

    @Serializable
    @SerialName("response.custom_tool_call_input.delta")
    public data class ToolCallInputDelta(
        @SerialName("item_id")
        public val itemId: String? = null,
        @SerialName("call_id")
        public val callId: String? = null,
        public val delta: String,
    ) : LlmResponseStreamEvent

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
    ) : LlmResponseStreamEvent

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
    ) : LlmResponseStreamEvent

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
    ) : LlmResponseStreamEvent

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
    ) : LlmResponseStreamEvent

    @Serializable
    @SerialName("response.reasoning_summary_part.added")
    public data class ReasoningSummaryPartAdded(
        @SerialName("item_id")
        public val itemId: String,
        @SerialName("output_index")
        public val outputIndex: Long,
        @SerialName("summary_index")
        public val summaryIndex: Long,
        public val part: LlmReasoningSummaryItem,
    ) : LlmResponseStreamEvent
}

@Serializable
public data class LlmFailedResponse(
    public val error: LlmResponseError? = null,
)

@Serializable
public data class LlmIncompleteResponse(
    @SerialName("incomplete_details")
    public val incompleteDetails: LlmIncompleteDetails? = null,
)

@Serializable
public data class LlmIncompleteDetails(
    public val reason: String? = null,
)

@Serializable
public data class LlmResponseError(
    public val message: String? = null,
    public val code: String? = null,
    public val type: String? = null,
)

@Serializable
public data class LlmCompactionRequest(
    public val model: String,
    public val input: List<LlmResponseItem>,
    public val instructions: String? = null,
    public val tools: List<LlmTool> = emptyList(),
    @SerialName("parallel_tool_calls")
    public val parallelToolCalls: Boolean = false,
    public val reasoning: LlmReasoning? = null,
    @SerialName("service_tier")
    public val serviceTier: String? = null,
    @SerialName("prompt_cache_key")
    public val promptCacheKey: String? = null,
    public val text: LlmTextControls? = null,
)

@Serializable
public data class LlmCompactionResponse(
    public val output: List<LlmResponseItem> = emptyList(),
)

/**
 * Mirrors Rust `ResponseItem`.
 *
 * Unknown wire variants decode to `Other`.
 */
@Serializable
public sealed interface LlmResponseItem {
    @Serializable
    @SerialName("message")
    public data class Message(
        public val id: String? = null,
        public val role: LlmMessageRole,
        public val content: List<LlmContentItem>,
        public val phase: LlmMessagePhase? = null,
    ) : LlmResponseItem

    @Serializable
    @SerialName("agent_message")
    public data class AgentMessage(
        public val author: String,
        public val recipient: String,
        public val content: List<LlmAgentMessageInputContent>,
    ) : LlmResponseItem

    @Serializable
    @SerialName("reasoning")
    public data class Reasoning(
        public val id: String? = null,
        public val summary: List<LlmReasoningSummaryItem> = emptyList(),
        public val content: List<LlmReasoningContentItem>? = null,
        @SerialName("encrypted_content")
        public val encryptedContent: String? = null,
    ) : LlmResponseItem

    @Serializable
    @SerialName("local_shell_call")
    public data class LocalShellCall(
        public val id: String? = null,
        @SerialName("call_id")
        public val callId: String? = null,
        public val status: LlmLocalShellStatus,
        public val action: LlmLocalShellAction,
    ) : LlmResponseItem

    @Serializable
    @SerialName("function_call")
    public data class FunctionCall(
        public val id: String? = null,
        public val name: String,
        public val namespace: String? = null,
        public val arguments: String,
        @SerialName("call_id")
        public val callId: String,
    ) : LlmResponseItem

    @Serializable
    @SerialName("tool_search_call")
    public data class ToolSearchCall(
        public val id: String? = null,
        @SerialName("call_id")
        public val callId: String? = null,
        public val status: String? = null,
        public val execution: String,
        public val arguments: JsonElement,
    ) : LlmResponseItem

    @Serializable
    @SerialName("function_call_output")
    public data class FunctionCallOutput(
        @SerialName("call_id")
        public val callId: String,
        public val output: LlmFunctionCallOutputPayload,
    ) : LlmResponseItem

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
        public val output: LlmMcpCallToolResult,
    ) : LlmResponseItem

    @Serializable
    @SerialName("custom_tool_call")
    public data class CustomToolCall(
        public val id: String? = null,
        public val status: String? = null,
        @SerialName("call_id")
        public val callId: String,
        public val name: String,
        public val input: String,
    ) : LlmResponseItem

    @Serializable
    @SerialName("custom_tool_call_output")
    public data class CustomToolCallOutput(
        @SerialName("call_id")
        public val callId: String,
        public val name: String? = null,
        public val output: LlmFunctionCallOutputPayload,
    ) : LlmResponseItem

    @Serializable
    @SerialName("tool_search_output")
    public data class ToolSearchOutput(
        @SerialName("call_id")
        public val callId: String? = null,
        public val status: String,
        public val execution: String,
        public val tools: List<LlmLoadableToolSpec>,
    ) : LlmResponseItem

    @Serializable
    @SerialName("web_search_call")
    public data class WebSearchCall(
        public val id: String? = null,
        public val status: String? = null,
        public val action: LlmWebSearchAction? = null,
    ) : LlmResponseItem

    @Serializable
    @SerialName("image_generation_call")
    public data class ImageGenerationCall(
        public val id: String,
        public val status: String,
        @SerialName("revised_prompt")
        public val revisedPrompt: String? = null,
        public val result: String,
    ) : LlmResponseItem

    /**
     * Preserves the Kotlin split between `compaction` and `compaction_summary`.
     */
    @Serializable
    @SerialName("compaction")
    public data class Compaction(
        @SerialName("encrypted_content")
        public val encryptedContent: String,
    ) : LlmResponseItem

    /**
     * Preserves the Kotlin split between `compaction` and `compaction_summary`.
     */
    @Serializable
    @SerialName("compaction_summary")
    public data class CompactionSummary(
        @SerialName("encrypted_content")
        public val encryptedContent: String,
    ) : LlmResponseItem

    @Serializable
    @SerialName("compaction_trigger")
    public data object CompactionTrigger : LlmResponseItem

    @Serializable
    @SerialName("context_compaction")
    public data class ContextCompaction(
        @SerialName("encrypted_content")
        public val encryptedContent: String? = null,
    ) : LlmResponseItem

    @Serializable
    @SerialName("other")
    public data object Other : LlmResponseItem
}

@Serializable
public sealed interface LlmContentItem {
    @Serializable
    @SerialName("input_text")
    public data class InputText(public val text: String) : LlmContentItem

    @Serializable
    @SerialName("input_image")
    public data class InputImage(
        @SerialName("image_url")
        public val imageUrl: String,
        public val detail: LlmImageDetail? = null,
    ) : LlmContentItem

    @Serializable
    @SerialName("output_text")
    public data class OutputText(public val text: String) : LlmContentItem
}

/**
 * Mirrors Rust `AgentMessageInputContent`.
 */
@Serializable
public sealed interface LlmAgentMessageInputContent {
    @Serializable
    @SerialName("input_text")
    public data class InputText(public val text: String) : LlmAgentMessageInputContent

    @Serializable
    @SerialName("encrypted_content")
    public data class EncryptedContent(
        @SerialName("encrypted_content")
        public val encryptedContent: String,
    ) : LlmAgentMessageInputContent
}

@Serializable
public enum class LlmImageDetail {
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
public enum class LlmMessagePhase {
    @SerialName("commentary")
    Commentary,

    @SerialName("final_answer")
    FinalAnswer,
}

@Serializable
public sealed interface LlmReasoningSummaryItem {
    @Serializable
    @SerialName("summary_text")
    public data class SummaryText(public val text: String) : LlmReasoningSummaryItem
}

/**
 * Mirrors Rust `ReasoningItemContent`.
 */
@Serializable
public sealed interface LlmReasoningContentItem {
    @Serializable
    @SerialName("reasoning_text")
    public data class ReasoningText(public val text: String) : LlmReasoningContentItem

    @Serializable
    @SerialName("text")
    public data class Text(public val text: String) : LlmReasoningContentItem
}

/**
 * Status values for `LlmResponseItem.LocalShellCall`.
 */
@Serializable
public enum class LlmLocalShellStatus {
    @SerialName("completed")
    Completed,

    @SerialName("in_progress")
    InProgress,

    @SerialName("incomplete")
    Incomplete,
}

/**
 * Action payload for `LlmResponseItem.LocalShellCall`.
 */
@Serializable
public sealed interface LlmLocalShellAction {
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
    ) : LlmLocalShellAction
}

/**
 * Action payload for `LlmResponseItem.WebSearchCall`.
 *
 * Unknown wire variants decode to `Other`.
 */
@Serializable
public sealed interface LlmWebSearchAction {
    @Serializable
    @SerialName("search")
    public data class Search(
        public val query: String? = null,
        public val queries: List<String>? = null,
    ) : LlmWebSearchAction

    @Serializable
    @SerialName("open_page")
    public data class OpenPage(
        public val url: String? = null,
    ) : LlmWebSearchAction

    @Serializable
    @SerialName("find_in_page")
    public data class FindInPage(
        public val url: String? = null,
        public val pattern: String? = null,
    ) : LlmWebSearchAction

    @Serializable
    @SerialName("other")
    public data object Other : LlmWebSearchAction
}

@Serializable(with = LlmFunctionCallOutputPayloadSerializer::class)
public data class LlmFunctionCallOutputPayload(
    public val body: LlmFunctionCallOutputBody = LlmFunctionCallOutputBody.Text(""),
    public val success: Boolean? = null,
) {
    public companion object {
        public fun fromText(text: String): LlmFunctionCallOutputPayload =
            LlmFunctionCallOutputPayload(LlmFunctionCallOutputBody.Text(text))

        public fun fromContentItems(
            contentItems: List<LlmFunctionCallOutputContentItem>,
        ): LlmFunctionCallOutputPayload =
            LlmFunctionCallOutputPayload(LlmFunctionCallOutputBody.ContentItems(contentItems))
    }
}

public sealed interface LlmFunctionCallOutputBody {
    public data class Text(public val text: String) : LlmFunctionCallOutputBody
    public data class ContentItems(
        public val items: List<LlmFunctionCallOutputContentItem>,
    ) : LlmFunctionCallOutputBody
}

/**
 * Mirrors MCP `CallToolResult`.
 *
 * `toFunctionCallOutputPayload` follows Rust `CallToolResult::into_function_call_output_payload`.
 */
@Serializable
public data class LlmMcpCallToolResult(
    public val content: List<JsonElement>,
    public val structuredContent: JsonElement? = null,
    public val isError: Boolean? = null,
    @SerialName("_meta")
    public val meta: JsonElement? = null,
) {
    public fun toFunctionCallOutputPayload(json: Json): LlmFunctionCallOutputPayload {
        val success = isError != true
        val structured = structuredContent
        if (structured != null && structured !is JsonNull) {
            return LlmFunctionCallOutputPayload(
                body = LlmFunctionCallOutputBody.Text(json.encodeToString(JsonElement.serializer(), structured)),
                success = success,
            )
        }

        val contentItems = content.toFunctionCallOutputContentItems(json)
        return LlmFunctionCallOutputPayload(
            body = contentItems?.let(LlmFunctionCallOutputBody::ContentItems)
                ?: LlmFunctionCallOutputBody.Text(json.encodeToString(contentSerializer, content)),
            success = success,
        )
    }
}

@Serializable
public sealed interface LlmFunctionCallOutputContentItem {
    @Serializable
    @SerialName("input_text")
    public data class InputText(public val text: String) : LlmFunctionCallOutputContentItem

    @Serializable
    @SerialName("input_image")
    public data class InputImage(
        @SerialName("image_url")
        public val imageUrl: String,
        public val detail: LlmImageDetail? = null,
    ) : LlmFunctionCallOutputContentItem

    @Serializable
    @SerialName("encrypted_content")
    public data class EncryptedContent(
        @SerialName("encrypted_content")
        public val encryptedContent: String,
    ) : LlmFunctionCallOutputContentItem
}

private val contentSerializer = ListSerializer(JsonElement.serializer())

private fun List<JsonElement>.toFunctionCallOutputContentItems(
    json: Json,
): List<LlmFunctionCallOutputContentItem>? {
    var sawImage = false
    val items = map { content ->
        val contentObject = content as? JsonObject
        when (contentObject?.string("type")) {
            "text" -> LlmFunctionCallOutputContentItem.InputText(
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
                LlmFunctionCallOutputContentItem.InputImage(
                    imageUrl = imageUrl,
                    detail = contentObject["_meta"]
                        ?.let { it as? JsonObject }
                        ?.string("codex/imageDetail")
                        ?.let(::imageDetailFromWireName)
                        ?: LlmImageDetail.High,
                )
            }

            else -> LlmFunctionCallOutputContentItem.InputText(
                text = json.encodeToString(JsonElement.serializer(), content),
            )
        }
    }

    return items.takeIf { sawImage }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull

private fun imageDetailFromWireName(value: String): LlmImageDetail? =
    when (value) {
        "auto" -> LlmImageDetail.Auto
        "low" -> LlmImageDetail.Low
        "high" -> LlmImageDetail.High
        "original" -> LlmImageDetail.Original
        else -> null
    }

@Serializable
public enum class LlmMessageRole(public val wireName: String) {
    @SerialName("user")
    User("user"),

    @SerialName("assistant")
    Assistant("assistant"),

    @SerialName("system")
    System("system"),

    @SerialName("tool")
    Tool("tool"),
}

@Serializable(with = LlmToolChoiceSerializer::class)
public sealed interface LlmToolChoice {
    public val wireName: String

    public data object Auto : LlmToolChoice {
        override val wireName: String = "auto"
    }

    public data object None : LlmToolChoice {
        override val wireName: String = "none"
    }

    public data object Required : LlmToolChoice {
        override val wireName: String = "required"
    }
}

@Serializable
public enum class LlmResponseInclude(public val wireName: String) {
    @SerialName("reasoning.encrypted_content")
    ReasoningEncryptedContent("reasoning.encrypted_content"),
}

@Serializable
public data class LlmReasoning(
    public val effort: LlmReasoningEffort? = null,
    public val summary: LlmReasoningSummary? = null,
    public val context: LlmReasoningContext? = null,
)

@Serializable
public enum class LlmReasoningEffort {
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
public enum class LlmReasoningSummary {
    @SerialName("auto")
    Auto,

    @SerialName("concise")
    Concise,

    @SerialName("detailed")
    Detailed,
}

@Serializable
public enum class LlmReasoningContext {
    @SerialName("auto")
    Auto,

    @SerialName("current_turn")
    CurrentTurn,

    @SerialName("all_turns")
    AllTurns,
}

@Serializable
public data class LlmTextControls(
    public val verbosity: LlmVerbosity? = null,
    public val format: LlmTextFormat? = null,
)

@Serializable
public enum class LlmVerbosity {
    @SerialName("low")
    Low,

    @SerialName("medium")
    Medium,

    @SerialName("high")
    High,
}

@Serializable
public data class LlmTextFormat(
    public val name: String,
    public val schema: JsonObject,
    public val strict: Boolean = true,
    public val type: LlmTextFormatType = LlmTextFormatType.JsonSchema,
)

@Serializable
public enum class LlmTextFormatType {
    @SerialName("json_schema")
    JsonSchema,
}

@Serializable
public data class LlmTokenUsage(
    @SerialName("input_tokens")
    public val inputTokens: Long? = null,
    @SerialName("output_tokens")
    public val outputTokens: Long? = null,
    @SerialName("total_tokens")
    public val totalTokens: Long? = null,
)

public fun textInput(text: String): List<LlmResponseItem> =
    messageInput(LlmMessageRole.User, text)

public fun messageInput(role: LlmMessageRole, content: String): List<LlmResponseItem> =
    listOf(
        LlmResponseItem.Message(
            role = role,
            content = listOf(LlmContentItem.InputText(content)),
        ),
    )
