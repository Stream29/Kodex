package io.github.stream29.codex.lite.openai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable(with = ResponseItemSerializer::class)
public sealed interface ResponseItem {
    /**
     * Serialization helper for every variant except `Other`.
     */
    @Serializable
    public sealed interface Known : ResponseItem

    /**
     * Response items that are valid entries in persisted agent history.
     */
    @Serializable
    public sealed interface HistoryItem : Known

    /**
     * Model-visible items that represent compaction state changes.
     */
    @Serializable
    public sealed interface CompactionItem : HistoryItem

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
    ) : HistoryItem

    @Serializable
    @SerialName("agent_message")
    public data class AgentMessage(
        public val author: String,
        public val recipient: String,
        public val content: List<AgentMessageInputContent>,
    ) : HistoryItem

    /**
     * @property id Empty when the wire omits a reasoning id, matching Rust's
     * `#[serde(default)]` compatibility behavior.
     * @property content Nullable because reasoning items may only contain
     * summary; `null` means no full reasoning content was provided.
     * @property encryptedContent Nullable because encrypted reasoning is
     * optional; `null` means no encrypted payload was provided.
     */
    @Serializable
    @SerialName("reasoning")
    public data class Reasoning(
        @OptIn(ExperimentalSerializationApi::class)
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        public val id: String = "",
        public val summary: List<ReasoningItemReasoningSummary> = emptyList(),
        public val content: List<ReasoningItemContent>? = null,
        @SerialName("encrypted_content")
        public val encryptedContent: String? = null,
    ) : HistoryItem

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
    ) : HistoryItem

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
    ) : HistoryItem

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
    ) : HistoryItem

    @Serializable
    @SerialName("function_call_output")
    public data class FunctionCallOutput(
        @SerialName("call_id")
        public val callId: String,
        public val output: FunctionCallOutputPayload,
    ) : HistoryItem

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
    ) : HistoryItem

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
    ) : HistoryItem

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
    ) : HistoryItem

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
    ) : HistoryItem

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
    ) : HistoryItem

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
    ) : HistoryItem

    /**
     * Preserves the Kotlin split between `compaction` and `compaction_summary`.
     */
    @Serializable
    @SerialName("compaction")
    public data class Compaction(
        @SerialName("encrypted_content")
        public val encryptedContent: String,
    ) : CompactionItem

    /**
     * Preserves the Kotlin split between `compaction` and `compaction_summary`.
     */
    @Serializable
    @SerialName("compaction_summary")
    public data class CompactionSummary(
        @SerialName("encrypted_content")
        public val encryptedContent: String,
    ) : CompactionItem

    @Serializable
    @SerialName("compaction_trigger")
    public data object CompactionTrigger : Known

    /**
     * @property encryptedContent Nullable because context compaction triggers can be
     * structural; `null` means no encrypted payload accompanies the item.
     */
    @Serializable
    @SerialName("context_compaction")
    public data class ContextCompaction(
        @SerialName("encrypted_content")
        public val encryptedContent: String? = null,
    ) : CompactionItem

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
@Serializable(with = WebSearchActionSerializer::class)
public sealed interface WebSearchAction {
    /**
     * Serialization helper for every variant except `Other`.
     */
    @Serializable
    public sealed interface Known : WebSearchAction

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
    ) : Known

    /**
     * @property url Nullable because hosted search actions may redact or omit it;
     * `null` means no page URL was exposed.
     */
    @Serializable
    @SerialName("open_page")
    public data class OpenPage(
        public val url: String? = null,
    ) : Known

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
    ) : Known

    @Serializable
    @SerialName("other")
    public data object Other : WebSearchAction
}

@Serializable
public enum class MessageRole(public val wireName: String) {
    @SerialName("user")
    User("user"),

    @SerialName("assistant")
    Assistant("assistant"),

    @SerialName("tool")
    Tool("tool"),
}
