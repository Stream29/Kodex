@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Fine-grained persisted commit discriminator matching `agent_commits.type`.
 */
@Serializable
public enum class AgentCommitType(
    public val wireName: String,
    public val legacyTimelineName: String,
) {
    @SerialName("user_message")
    UserMessage("user_message", "index"),

    @SerialName("assistant_message")
    AssistantMessage("assistant_message", "index"),

    @SerialName("assistant_message_commentary")
    AssistantMessageCommentary("assistant_message_commentary", "index"),

    @SerialName("assistant_message_final")
    AssistantMessageFinal("assistant_message_final", "index"),

    @SerialName("developer_message")
    DeveloperMessage("developer_message", "index"),

    @SerialName("agent_message")
    AgentMessage("agent_message", "index"),

    @SerialName("request_user_input_tool_event")
    RequestUserInputToolEvent("request_user_input_tool_event", "index"),

    @SerialName("suggest_subagent_task_tool_event")
    SuggestSubagentTaskToolEvent("suggest_subagent_task_tool_event", "index"),

    @SerialName("plan_update")
    PlanUpdate("plan_update", "index"),

    @SerialName("compaction_point")
    CompactionPoint("compaction_point", "index"),

    @SerialName("reasoning")
    Reasoning("reasoning", "work"),

    @SerialName("context_compaction")
    ContextCompaction("context_compaction", "work"),

    @SerialName("command_execution_tool_event")
    CommandExecutionToolEvent("command_execution_tool_event", "work"),

    @SerialName("patch_tool_event")
    PatchToolEvent("patch_tool_event", "work"),

    @SerialName("mcp_tool_event")
    McpToolEvent("mcp_tool_event", "work"),

    @SerialName("web_search_tool_event")
    WebSearchToolEvent("web_search_tool_event", "work"),

    @SerialName("image_generation_tool_event")
    ImageGenerationToolEvent("image_generation_tool_event", "work"),

    @SerialName("image_view_tool_event")
    ImageViewToolEvent("image_view_tool_event", "work"),

    @SerialName("tool_search_event")
    ToolSearchEvent("tool_search_event", "work"),

    @SerialName("invalid_tool_call")
    InvalidToolCall("invalid_tool_call", "work"),

    @SerialName("server_tool_search")
    ServerToolSearch("server_tool_search", "work"),

    @SerialName("hosted_web_search")
    HostedWebSearch("hosted_web_search", "work"),

    @SerialName("hosted_image_generation")
    HostedImageGeneration("hosted_image_generation", "work"),

    @SerialName("json_tool_event")
    JsonToolEvent("json_tool_event", "work"),

    @SerialName("text_tool_event")
    TextToolEvent("text_tool_event", "work"),

    @SerialName("custom_tool_event")
    CustomToolEvent("custom_tool_event", "work"),

    @SerialName("settings")
    Settings("settings", "settings"),

    @SerialName("timestamp")
    Timestamp("timestamp", "timestamp"),

    @SerialName("token_count_legacy")
    TokenCountLegacy("token_count_legacy", "token-count"),

    @SerialName("token_count_initialization")
    TokenCountInitialization("token_count_initialization", "token-count"),

    @SerialName("token_count_compaction")
    TokenCountCompaction("token_count_compaction", "token-count"),

    @SerialName("token_count_response")
    TokenCountResponse("token_count_response", "token-count"),

    @SerialName("unstable")
    Unstable("unstable", "unstable");

    public companion object {
        private val byWireName: Map<String, AgentCommitType> = entries.associateBy { it.wireName }

        public val All: Set<AgentCommitType> = entries.toSet()

        public val IndexTypes: Set<AgentCommitType> = setOf(
            UserMessage,
            AssistantMessage,
            AssistantMessageCommentary,
            AssistantMessageFinal,
            DeveloperMessage,
            AgentMessage,
            RequestUserInputToolEvent,
            SuggestSubagentTaskToolEvent,
            PlanUpdate,
            CompactionPoint,
        )

        public val IndexEventTypes: Set<AgentCommitType> = IndexTypes - CompactionPoint

        public val SteerableIndexEventTypes: Set<AgentCommitType> = setOf(
            UserMessage,
            AssistantMessage,
            AssistantMessageCommentary,
            AssistantMessageFinal,
            DeveloperMessage,
            AgentMessage,
        )

        public val TurnStartBoundaryTypes: Set<AgentCommitType> = setOf(
            UserMessage,
            AssistantMessage,
            AssistantMessageCommentary,
            AssistantMessageFinal,
        )

        public val CompactionRetainedTypes: Set<AgentCommitType> = setOf(
            UserMessage,
            PlanUpdate,
            RequestUserInputToolEvent,
            SuggestSubagentTaskToolEvent,
        )

        public val WorkTypes: Set<AgentCommitType> = setOf(
            Reasoning,
            ContextCompaction,
            CommandExecutionToolEvent,
            PatchToolEvent,
            McpToolEvent,
            WebSearchToolEvent,
            ImageGenerationToolEvent,
            ImageViewToolEvent,
            ToolSearchEvent,
            InvalidToolCall,
            ServerToolSearch,
            HostedWebSearch,
            HostedImageGeneration,
            JsonToolEvent,
            TextToolEvent,
            CustomToolEvent,
        )

        public val CleanEventTypes: Set<AgentCommitType> = IndexEventTypes + WorkTypes

        public val TokenCountTypes: Set<AgentCommitType> = setOf(
            TokenCountLegacy,
            TokenCountInitialization,
            TokenCountCompaction,
            TokenCountResponse,
        )

        public val SettingsTypes: Set<AgentCommitType> = setOf(Settings)
        public val TimestampTypes: Set<AgentCommitType> = setOf(Timestamp)
        public val UnstableTypes: Set<AgentCommitType> = setOf(Unstable)

        public fun fromWireName(wireName: String): AgentCommitType =
            byWireName[wireName] ?: throw IllegalArgumentException("Unknown AgentCommitType wireName: $wireName")
    }
}

/**
 * Atomic persisted commit unit in an Agent's commit history.
 */
@Serializable
public sealed interface AgentCommit {
    public val commitType: AgentCommitType
        get() = when (this) {
            is StableUserMessage -> AgentCommitType.UserMessage
            is StableAssistantMessage -> when (phase) {
                MessagePhase.Commentary -> AgentCommitType.AssistantMessageCommentary
                MessagePhase.FinalAnswer -> AgentCommitType.AssistantMessageFinal
                null -> AgentCommitType.AssistantMessage
            }
            is StableDeveloperMessage -> AgentCommitType.DeveloperMessage
            is StableAgentMessage -> AgentCommitType.AgentMessage
            is StableRequestUserInputToolEvent -> AgentCommitType.RequestUserInputToolEvent
            is StableSuggestSubagentTaskToolEvent -> AgentCommitType.SuggestSubagentTaskToolEvent
            is StablePlanUpdate -> AgentCommitType.PlanUpdate
            CleanCompactionPoint -> AgentCommitType.CompactionPoint
            is StableReasoning -> AgentCommitType.Reasoning
            is StableContextCompaction -> AgentCommitType.ContextCompaction
            is StableCommandExecutionToolEvent -> AgentCommitType.CommandExecutionToolEvent
            is StablePatchToolEvent -> AgentCommitType.PatchToolEvent
            is StableMcpToolEvent -> AgentCommitType.McpToolEvent
            is StableWebSearchToolEvent -> AgentCommitType.WebSearchToolEvent
            is StableImageGenerationToolEvent -> AgentCommitType.ImageGenerationToolEvent
            is StableImageViewToolEvent -> AgentCommitType.ImageViewToolEvent
            is StableToolSearchEvent -> AgentCommitType.ToolSearchEvent
            is StableInvalidToolCall -> AgentCommitType.InvalidToolCall
            is StableServerToolSearch -> AgentCommitType.ServerToolSearch
            is StableWebSearchCall -> AgentCommitType.HostedWebSearch
            is StableImageGenerationCall -> AgentCommitType.HostedImageGeneration
            is StableJsonToolEvent -> AgentCommitType.JsonToolEvent
            is StableTextToolEvent -> AgentCommitType.TextToolEvent
            is StableCustomToolEvent -> AgentCommitType.CustomToolEvent
            is KodexAgentSettings -> AgentCommitType.Settings
            is CommitTimestamp -> AgentCommitType.Timestamp
            is TokenCountSnapshot.Legacy -> AgentCommitType.TokenCountLegacy
            is TokenCountSnapshot.Initialization -> AgentCommitType.TokenCountInitialization
            is TokenCountSnapshot.Compaction -> AgentCommitType.TokenCountCompaction
            is TokenCountSnapshot.Response -> AgentCommitType.TokenCountResponse
            is UnstableSnapshot -> AgentCommitType.Unstable
        }
}

/**
 * One [AgentCommit] paired with its logical storage [index].
 */
@Serializable
public data class IndexedAgentCommit(
    public val index: Int,
    public val commit: AgentCommit,
)

/**
 * Persisted timestamp commit at a logical storage index.
 */
@Serializable
@SerialName("timestamp")
public data class CommitTimestamp(
    public val instant: Instant,
) : AgentCommit

/**
 * Persisted snapshot of pending unstable events at a logical storage index.
 */
@Serializable
@SerialName("unstable")
public data class UnstableSnapshot(
    public val events: List<UnstableCleanEvent>,
) : AgentCommit

private val UnstableEventListSerializer: KSerializer<List<UnstableCleanEvent>> =
    ListSerializer(UnstableCleanEvent.serializer())

/**
 * Encodes the payload JSON stored in `agent_commits.payload_json` and `.kodex/sessions` mirror files.
 */
public fun AgentCommit.encodePayloadJson(json: Json = OpenAiJsonCodec): String =
    when (this) {
        is CleanIndexEntry -> json.encodeToString(CleanIndexEntry.serializer(), this)
        is StableWorkEvent -> json.encodeToString(StableWorkEvent.serializer(), this)
        is KodexAgentSettings -> json.encodeToString(KodexAgentSettings.serializer(), this)
        is CommitTimestamp -> json.encodeToString(Instant.serializer(), instant)
        is TokenCountSnapshot -> json.encodeToString(TokenCountSnapshot.serializer(), this)
        is UnstableSnapshot -> json.encodeToString(UnstableEventListSerializer, events)
    }

/**
 * Decodes `agent_commits.payload_json` for this [AgentCommitType].
 */
public fun AgentCommitType.decodePayloadJson(
    payloadJson: String,
    json: Json = OpenAiJsonCodec,
): AgentCommit {
    val decoded: AgentCommit = when (this) {
        in AgentCommitType.IndexTypes -> json.decodeFromString(CleanIndexEntry.serializer(), payloadJson)
        in AgentCommitType.WorkTypes -> json.decodeFromString(StableWorkEvent.serializer(), payloadJson)
        AgentCommitType.Settings -> json.decodeFromString(KodexAgentSettings.serializer(), payloadJson)
        AgentCommitType.Timestamp -> CommitTimestamp(json.decodeFromString(Instant.serializer(), payloadJson))
        AgentCommitType.TokenCountLegacy -> json.decodeFromString(TokenCountSnapshot.Legacy.serializer(), payloadJson)
        AgentCommitType.TokenCountInitialization ->
            json.decodeFromString(TokenCountSnapshot.Initialization.serializer(), payloadJson)
        AgentCommitType.TokenCountCompaction ->
            json.decodeFromString(TokenCountSnapshot.Compaction.serializer(), payloadJson)
        AgentCommitType.TokenCountResponse ->
            json.decodeFromString(TokenCountSnapshot.Response.serializer(), payloadJson)
        AgentCommitType.Unstable -> UnstableSnapshot(json.decodeFromString(UnstableEventListSerializer, payloadJson))
        else -> error("Unhandled AgentCommitType: $this")
    }
    require(decoded.commitType == this) {
        "Commit payload type mismatch: expected $wireName, decoded ${decoded.commitType.wireName}"
    }
    return decoded
}
