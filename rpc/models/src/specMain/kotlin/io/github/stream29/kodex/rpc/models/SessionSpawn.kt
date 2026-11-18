package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.serialization.Serializable

/** A claimed invocation must match a live root owner on the backend accepting the request. */
@Serializable
public data class SessionSpawnSource(public val agentId: Int, public val invocationId: String)

/** Creation-time overrides only; provider session identities and history are not inputs. */
@Serializable
public data class SessionSpawnRequest(
    public val launchDirectory: String,
    public val workingDirectory: String? = null,
    public val source: SessionSpawnSource? = null,
    public val model: OpenAiModelId? = null,
    public val reasoningEffort: ReasoningEffort? = null,
    public val serviceTier: ServiceTier? = null,
    public val requestUserInputMode: RequestUserInputMode? = null,
)

@Serializable
public sealed interface SessionSpawnResult {
    @Serializable
    public data class Created(
        public val sessionId: Int, public val rootAgentId: Int, public val invocationId: String,
    ) : SessionSpawnResult

    @Serializable
    public data class Rejected(public val reason: SessionSpawnRejection) : SessionSpawnResult
}

@Serializable
public enum class SessionSpawnRejection { InvalidSource, InvalidDirectory, InvalidConfiguration }
