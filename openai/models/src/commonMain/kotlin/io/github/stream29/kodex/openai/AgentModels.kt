package io.github.stream29.kodex.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Selects whether an Agent may ask the user a structured question. */
@Serializable
public enum class RequestUserInputMode {
    @SerialName("ask_user")
    AskUser,

    @SerialName("no_question")
    NoQuestion,
}

/**
 * Persisted goal state for the storage-backed thread.
 *
 * This is the state portion of Rust's `ThreadGoal`: the containing storage
 * owns the thread identity, and its timestamp timeline records when each goal
 * snapshot became visible.
 *
 * @property tokenBudget Nullable because a goal may have no token budget;
 * `null` means the runtime does not apply a token-budget limit.
 */
@Serializable
public data class ThreadGoal(
    public val objective: String,
    public val status: ThreadGoalStatus,
    public val tokenBudget: Long? = null,
    public val tokensUsed: Long = 0,
    public val timeUsedSeconds: Long = 0,
)

/**
 * Current lifecycle state of one [ThreadGoal].
 *
 * This mirrors Rust's persisted `ThreadGoalStatus` state model.
 */
@Serializable
public enum class ThreadGoalStatus {
    @SerialName("active")
    Active,

    @SerialName("paused")
    Paused,

    @SerialName("blocked")
    Blocked,

    @SerialName("usageLimited")
    UsageLimited,

    @SerialName("budgetLimited")
    BudgetLimited,

    @SerialName("complete")
    Complete,
}
