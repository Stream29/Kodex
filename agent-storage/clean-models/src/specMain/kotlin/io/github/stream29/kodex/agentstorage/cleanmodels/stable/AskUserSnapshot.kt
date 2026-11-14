@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Immutable completion payload recorded once an `ask_user` entry has been answered. */
@Serializable
public data class AskUserAnswer(
    public val response: RequestUserInputResponse,
    @SerialName("answered_at")
    public val answeredAt: Instant,
)

/**
 * One agent-local `ask_user` interaction stored on the `ask_user` snapshot timeline in `agent_commits`.
 *
 * Because `AskUserSnapshot` lives directly in `agent_commits`, `createFork` naturally copies pending
 * and completed entries as an independent snapshot on the target Agent, and `revert` restores the
 * interaction state to the target history boundary.
 */
@Serializable
public data class AskUserEntry(
    @SerialName("ask_id")
    public val askId: Int,
    public val questions: RequestUserInputArgs,
    public val answer: AskUserAnswer? = null,
    @SerialName("created_at")
    public val createdAt: Instant,
)

/**
 * Persisted snapshot of `ask_user` interactions at a logical storage index (`ask_user` commit type).
 */
@Serializable
@SerialName("ask_user")
public data class AskUserSnapshot(
    public val entries: List<AskUserEntry>,
) : AgentCommit
