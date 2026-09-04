package io.github.stream29.kodex.tool.multiagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

/** Arguments emitted by the model for the host-owned subagent suggestion tool. */
@Serializable
public data class SuggestSubagentTaskArgs(
    public val tasks: List<SuggestedSubagentTask>,
)

/** One new Session proposal. */
@Serializable
public data class SuggestedSubagentTask(
    public val name: String,
    public val prompt: String,
)

/** Result of the user's decision about a complete task batch. */
@Serializable
public sealed interface SuggestSubagentTaskResponse {
    public val feedback: String?

    @Serializable
    @SerialName("accepted")
    public data class Accepted(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val feedback: String?,
        public val sessions: List<SuggestedSessionMeta>,
    ) : SuggestSubagentTaskResponse {
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        public val decision: SuggestSubagentTaskDecision =
            SuggestSubagentTaskDecision.Accepted
    }

    @Serializable
    @SerialName("rejected")
    public data class Rejected(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        override val feedback: String?,
    ) : SuggestSubagentTaskResponse {
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        public val decision: SuggestSubagentTaskDecision =
            SuggestSubagentTaskDecision.Rejected
    }
}

@Serializable
public enum class SuggestSubagentTaskDecision {
    @SerialName("accepted")
    Accepted,

    @SerialName("rejected")
    Rejected,
}

/** Tool-owned metadata for a newly created Session. */
@Serializable
public data class SuggestedSessionMeta(
    public val uri: String,
    public val name: String,
)
