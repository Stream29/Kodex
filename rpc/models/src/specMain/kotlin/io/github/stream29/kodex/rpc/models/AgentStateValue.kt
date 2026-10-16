package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Transport projection of the original KodexAgentStateValue.
 *
 * Preserves its branches and tool values, replacing only active output SharedFlows with opaque
 * random Long markers. No storage index, Job or frontend presentation state is introduced.
 * Backend state conversion, marker generation and stream binding are not implemented here.
 */
@Serializable
public sealed interface AgentStateValue {
    @Serializable
    @SerialName("empty")
    public data object Empty : AgentStateValue

    @Serializable
    @SerialName("user_message")
    public data object UserMessage : AgentStateValue

    @Serializable
    @SerialName("assistant_message")
    public data object AssistantMessage : AgentStateValue

    @Serializable
    @SerialName("tool_pending")
    public data class ToolPending(
        public val events: List<PendingToolEvent>,
    ) : AgentStateValue {
        init {
            require(events.isNotEmpty()) {
                "ToolPending requires at least one pending local tool event."
            }
        }
    }

    @Serializable
    @SerialName("tool_completed")
    public data object ToolCompleted : AgentStateValue

    @Serializable
    @SerialName("external_write")
    public data object ExternalWrite : AgentStateValue

    @Serializable
    @SerialName("compacting")
    public data object Compacting : AgentStateValue

    /**
     * One active Responses request. Started has no output stream.
     * Each other branch identifies one current output stream instance; retries and subsequent
     * output items receive fresh random markers. Deltas within that instance keep its marker.
     * Markers are neither ordered counters nor credentials; all Long values are representable.
     */
    @Serializable
    public sealed interface RequestResponse : AgentStateValue {
        @Serializable
        @SerialName("request_started")
        public data object Started : RequestResponse

        @Serializable
        @SerialName("request_message")
        public data class Message(public val nonce: Long) : RequestResponse

        @Serializable
        @SerialName("request_agent_message")
        public data class AgentMessage(public val nonce: Long) : RequestResponse

        @Serializable
        @SerialName("request_reasoning")
        public data class Reasoning(public val nonce: Long) : RequestResponse

        @Serializable
        @SerialName("request_tool_call")
        public data class ToolCall(public val nonce: Long) : RequestResponse

        @Serializable
        @SerialName("request_unknown")
        public data class Unknown(public val nonce: Long) : RequestResponse
    }
}
