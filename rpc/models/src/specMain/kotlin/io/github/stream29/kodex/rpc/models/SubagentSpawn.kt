package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class SubagentSpawnRequest(
    public val source: SessionSpawnSource,
    public val message: String,
    public val model: OpenAiModelId? = null,
    public val reasoningEffort: ReasoningEffort? = null,
    public val workingDirectory: String? = null,
)

@Serializable
public sealed interface SubagentSpawnResult {
    /** The child was initialized, recorded on the parent, and its first input was accepted. */
    @Serializable
    public data class Created(
        public val sessionId: Int,
        public val parentAgentId: Int,
        public val agentId: Int,
    ) : SubagentSpawnResult

    @Serializable
    public data class Rejected(public val reason: SubagentSpawnRejection) : SubagentSpawnResult

    /** The child exists; retrying spawn could create another child. */
    @Serializable
    public data class Partial(
        public val sessionId: Int,
        public val parentAgentId: Int,
        public val agentId: Int,
        public val stage: SubagentSpawnStage,
    ) : SubagentSpawnResult
}

@Serializable
public enum class SubagentSpawnRejection {
    InvalidSource,
    CreationRejected,
    InvalidDirectory,
    InvalidConfiguration,
    InvalidMessage,
}

@Serializable
public enum class SubagentSpawnStage {
    @SerialName("record")
    Record,

    @SerialName("submit")
    Submit,
}
