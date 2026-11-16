package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.agentstorage.cleanmodels.CleanOpenAiEvent
import kotlinx.serialization.Serializable

/**
 * Completed clean event persisted in either the index or work timeline.
 *
 * Persistence serializes the timeline-specific sealed unions rather than this
 * shared projection contract.
 */
@Serializable
public sealed interface StableCleanEvent : CleanOpenAiEvent, AgentCommit {
    /**
     * Completed clean event produced by a tool handler.
     *
     * This cross-timeline contract prevents tool execution from publishing
     * messages, reasoning, or other non-tool stable events.
     * RPC completion serializes this union; persistence keeps its timeline-specific unions.
     */
    @Serializable
    public sealed interface CompletedTool : StableCleanEvent
}

/**
 * Direct O(1) call identifier for completed local tool events, or `null` for
 * hosted provider tool events ([StableServerToolSearch], [StableWebSearchCall],
 * [StableImageGenerationCall]).
 */
public val StableCleanEvent.CompletedTool.callId: String?
    get() = when (this) {
        is StablePlanUpdate -> callId
        is StableCommandExecutionToolEvent -> callId
        is StablePatchToolEvent -> callId
        is StableReadMediaToolEvent -> callId
        is StableAwaitEventToolEvent -> callId
        is StableInvalidToolCall -> callId
        is StableJsonToolEvent -> callId
        is StableTextToolEvent -> callId
        is StableCustomToolEvent -> callId
        is StableServerToolSearch,
        is StableWebSearchCall,
        is StableImageGenerationCall,
        -> null
    }
