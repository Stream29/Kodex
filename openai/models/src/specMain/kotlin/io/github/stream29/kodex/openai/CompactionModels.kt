package io.github.stream29.kodex.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of a remote compaction v2 Responses stream.
 *
 * @property compactionOutput Compaction item returned by the service.
 * @property completedResponse Nullable because a valid compaction stream may
 * end after the compaction item without a `response.completed` event; `null`
 * means no completed response was observed.
 */
public data class RemoteCompactionV2Response(
    public val compactionOutput: ResponseItem.Compaction,
    public val completedResponse: Response?,
)

@Serializable
public enum class CompactionTrigger {
    @SerialName("auto")
    Auto,

    @SerialName("manual")
    Manual,
}

@Serializable
public enum class CompactionReason {
    @SerialName("user_requested")
    UserRequested,

    @SerialName("context_limit")
    ContextLimit,

    @SerialName("model_downshift")
    ModelDownshift,

    @SerialName("comp_hash_changed")
    CompHashChanged,
}

@Serializable
public enum class CompactionPhase {
    @SerialName("standalone_turn")
    StandaloneTurn,

    @SerialName("pre_turn")
    PreTurn,

    @SerialName("mid_turn")
    MidTurn,
}
