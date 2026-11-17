@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRecord
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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

    @SerialName("event_ask_user")
    EventAskUser("event_ask_user", "index"),

    @SerialName("event_schedule")
    EventSchedule("event_schedule", "index"),

    @SerialName("event_terminal_exited")
    EventTerminalExited("event_terminal_exited", "work"),

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

    @SerialName("plan_update")
    PlanUpdate("plan_update", "index"),

    @SerialName("context_compaction")
    ContextCompaction("context_compaction", "index"),

    @SerialName("reasoning")
    Reasoning("reasoning", "work"),

    @SerialName("command_execution_tool_event")
    CommandExecutionToolEvent("command_execution_tool_event", "work"),

    @SerialName("await_event_tool_event")
    AwaitEventToolEvent("await_event_tool_event", "work"),

    @SerialName("patch_tool_event")
    PatchToolEvent("patch_tool_event", "work"),

    @SerialName("read_media_tool_event")
    ReadMediaToolEvent("read_media_tool_event", "work"),

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

    @SerialName("llm_request_meta_legacy")
    LlmRequestMetaLegacy("llm_request_meta_legacy", "llm-request-meta"),

    @SerialName("llm_request_meta_initialization")
    LlmRequestMetaInitialization("llm_request_meta_initialization", "llm-request-meta"),

    @SerialName("llm_request_meta_compaction")
    LlmRequestMetaCompaction("llm_request_meta_compaction", "llm-request-meta"),

    @SerialName("llm_request_meta_response")
    LlmRequestMetaResponse("llm_request_meta_response", "llm-request-meta"),

    @SerialName("unstable")
    Unstable("unstable", "unstable"),

    @SerialName("steer")
    Steer("steer", "steer"),

    @SerialName("ask_user")
    AskUser("ask_user", "ask-user");

    public companion object {
        private val byWireName: Map<String, AgentCommitType> =
            AgentCommitType.entries.associateBy { it.wireName }

        public val All: Set<AgentCommitType> = entries.toSet()

        public val IndexTypes: Set<AgentCommitType> = setOf(
            UserMessage,
            EventAskUser,
            EventSchedule,
            AssistantMessage,
            AssistantMessageCommentary,
            AssistantMessageFinal,
            DeveloperMessage,
            AgentMessage,
            PlanUpdate,
            ContextCompaction,
        )

        public val TurnStartBoundaryTypes: Set<AgentCommitType> = setOf(
            UserMessage,
            EventAskUser,
            EventSchedule,
            EventTerminalExited,
            AssistantMessage,
            AssistantMessageCommentary,
            AssistantMessageFinal,
        )

        public val CompactionRetainedTypes: Set<AgentCommitType> = setOf(
            UserMessage,
            EventAskUser,
            EventSchedule,
            PlanUpdate,
        )

        public val WorkTypes: Set<AgentCommitType> = setOf(
            EventTerminalExited,
            Reasoning,
            CommandExecutionToolEvent,
            AwaitEventToolEvent,
            PatchToolEvent,
            ReadMediaToolEvent,
            InvalidToolCall,
            ServerToolSearch,
            HostedWebSearch,
            HostedImageGeneration,
            JsonToolEvent,
            TextToolEvent,
            CustomToolEvent,
        )

        public val CleanEventTypes: Set<AgentCommitType> = IndexTypes + WorkTypes

        public val LlmRequestMetaTypes: Set<AgentCommitType> = setOf(
            LlmRequestMetaLegacy,
            LlmRequestMetaInitialization,
            LlmRequestMetaCompaction,
            LlmRequestMetaResponse,
        )

        public val SettingsTypes: Set<AgentCommitType> = setOf(Settings)
        public val UnstableTypes: Set<AgentCommitType> = setOf(Unstable)
        public val SteerTypes: Set<AgentCommitType> = setOf(Steer)
        public val AskUserTypes: Set<AgentCommitType> = setOf(AskUser)

        public fun fromWireName(wireName: String): AgentCommitType =
            byWireName[wireName] ?: throw IllegalArgumentException("Unknown AgentCommitType wireName: $wireName")
    }
}

/**
 * Atomic persisted commit unit in an Agent's commit history.
 */
@Serializable
public sealed interface AgentCommit {
    /** Input that can be delivered to an Agent during a logical turn. */
    @Serializable
    public sealed interface Steerable : AgentCommit

    /** Completed tool interactions that can be produced by a tool handler. */
    @Serializable
    public sealed interface CompletedTool : AgentCommit

    public val commitType: AgentCommitType
        get() = when (this) {
            is StableUserMessage -> AgentCommitType.UserMessage
            is StableAskUserEvent -> AgentCommitType.EventAskUser
            is StableScheduleEvent -> AgentCommitType.EventSchedule
            is StableTerminalExitedEvent -> AgentCommitType.EventTerminalExited
            is StableAssistantMessage -> when (phase) {
                MessagePhase.Commentary -> AgentCommitType.AssistantMessageCommentary
                MessagePhase.FinalAnswer -> AgentCommitType.AssistantMessageFinal
                null -> AgentCommitType.AssistantMessage
            }
            is StableDeveloperMessage -> AgentCommitType.DeveloperMessage
            is StableAgentMessage -> AgentCommitType.AgentMessage
            is StablePlanUpdate -> AgentCommitType.PlanUpdate
            is StableContextCompaction -> AgentCommitType.ContextCompaction
            is StableReasoning -> AgentCommitType.Reasoning
            is StableCommandExecution -> AgentCommitType.CommandExecutionToolEvent
            is StableAwait -> AgentCommitType.AwaitEventToolEvent
            is StablePatch -> AgentCommitType.PatchToolEvent
            is StableReadMedia -> AgentCommitType.ReadMediaToolEvent
            is StableInvalidToolCall -> AgentCommitType.InvalidToolCall
            is StableServerToolSearch -> AgentCommitType.ServerToolSearch
            is StableWebSearchCall -> AgentCommitType.HostedWebSearch
            is StableImageGenerationCall -> AgentCommitType.HostedImageGeneration
            is StableJsonTool -> AgentCommitType.JsonToolEvent
            is StableTextTool -> AgentCommitType.TextToolEvent
            is StableCustomTool -> AgentCommitType.CustomToolEvent
            is KodexAgentSettings -> AgentCommitType.Settings
            is LlmRequestMeta.Legacy -> AgentCommitType.LlmRequestMetaLegacy
            is LlmRequestMeta.Initialization -> AgentCommitType.LlmRequestMetaInitialization
            is LlmRequestMeta.Compaction -> AgentCommitType.LlmRequestMetaCompaction
            is LlmRequestMeta.Response -> AgentCommitType.LlmRequestMetaResponse
            is UnstableSnapshot -> AgentCommitType.Unstable
            is SteerSnapshot -> AgentCommitType.Steer
            is AskUserSnapshot -> AgentCommitType.AskUser
        }
}

/**
 * One [AgentCommit] paired with its logical storage [index] and creation [createdAt] timestamp.
 */
@Serializable
public data class IndexedAgentCommit(
    public val index: Int,
    public val commit: AgentCommit,
    public val createdAt: Instant = Instant.DISTANT_PAST,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IndexedAgentCommit) return false
        if (index != other.index) return false
        if (commit != other.commit) return false
        if (createdAt != Instant.DISTANT_PAST && other.createdAt != Instant.DISTANT_PAST && createdAt != other.createdAt) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + commit.hashCode()
        return result
    }
}

/**
 * Persisted snapshot of pending unstable events at a logical storage index.
 */
@Serializable
@SerialName("unstable")
public data class UnstableSnapshot(
    public val events: List<PendingRecord>,
) : AgentCommit

/**
 * Persisted snapshot of unconsumed steerable events at a logical storage index.
 */
@Serializable
@SerialName("steer")
public data class SteerSnapshot(
    public val events: List<AgentCommit.Steerable>,
) : AgentCommit

private val UnstableEventListSerializer: KSerializer<List<PendingRecord>> =
    ListSerializer(PendingRecord.serializer())

private val SteerableEventListSerializer: KSerializer<List<AgentCommit.Steerable>> =
    ListSerializer(AgentCommit.Steerable.serializer())

private val AskUserEntryListSerializer: KSerializer<List<AskUserEntry>> =
    ListSerializer(AskUserEntry.serializer())

/**
 * Encodes the payload JSON stored in `agent_commits.payload_json` and `.kodex/sessions` mirror files.
 */
public fun AgentCommit.encodePayloadJson(json: Json = OpenAiJsonCodec): String =
    when (this) {
        is IndexCommit -> json.encodeToString(IndexCommit.serializer(), this)
        is WorkCommit -> json.encodeToString(WorkCommit.serializer(), this)
        is KodexAgentSettings -> json.encodeToString(KodexAgentSettings.serializer(), this)
        is LlmRequestMeta -> json.encodeToString(LlmRequestMeta.serializer(), this)
        is UnstableSnapshot -> json.encodeToString(UnstableEventListSerializer, events)
        is SteerSnapshot -> json.encodeToString(SteerableEventListSerializer, events)
        is AskUserSnapshot -> json.encodeToString(AskUserEntryListSerializer, entries)
    }

/**
 * Decodes `agent_commits.payload_json` for this [AgentCommitType].
 */
public fun AgentCommitType.decodePayloadJson(
    payloadJson: String,
    json: Json = OpenAiJsonCodec,
): AgentCommit {
    val decoded: AgentCommit = when (this) {
        in AgentCommitType.IndexTypes -> json.decodeFromString(IndexCommit.serializer(), payloadJson)
        in AgentCommitType.WorkTypes -> json.decodeFromString(WorkCommit.serializer(), payloadJson)
        AgentCommitType.Settings -> json.decodeFromString(KodexAgentSettings.serializer(), payloadJson)
        AgentCommitType.LlmRequestMetaLegacy ->
            json.decodeFromString(LlmRequestMeta.Legacy.serializer(), payloadJson)
        AgentCommitType.LlmRequestMetaInitialization ->
            json.decodeFromString(LlmRequestMeta.Initialization.serializer(), payloadJson)
        AgentCommitType.LlmRequestMetaCompaction ->
            json.decodeFromString(LlmRequestMeta.Compaction.serializer(), payloadJson)
        AgentCommitType.LlmRequestMetaResponse ->
            json.decodeFromString(LlmRequestMeta.Response.serializer(), payloadJson)
        AgentCommitType.Unstable -> UnstableSnapshot(json.decodeFromString(UnstableEventListSerializer, payloadJson))
        AgentCommitType.Steer -> SteerSnapshot(json.decodeFromString(SteerableEventListSerializer, payloadJson))
        AgentCommitType.AskUser -> AskUserSnapshot(json.decodeFromString(AskUserEntryListSerializer, payloadJson))
        else -> error("Unhandled AgentCommitType: $this")
    }
    require(decoded.commitType == this) {
        "Commit payload type mismatch: expected $wireName, decoded ${decoded.commitType.wireName}"
    }
    return decoded
}
