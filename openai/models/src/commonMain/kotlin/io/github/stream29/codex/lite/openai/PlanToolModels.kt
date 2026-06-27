package io.github.stream29.codex.lite.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for the Codex `update_plan` tool.
 *
 * Mirrors Rust `UpdatePlanArgs` from
 * `shared-context/codex/codex-rs/protocol/src/plan_tool.rs`.
 *
 * @property explanation Nullable because `update_plan` may omit an explanation;
 * `null` means this update only replaces the plan.
 * @property plan Full replacement plan snapshot.
 */
@Serializable
public data class UpdatePlanArgs(
    public val explanation: String? = null,
    public val plan: List<PlanItemArg>,
)

/**
 * One item in [UpdatePlanArgs.plan].
 *
 * @property step User-facing task step text.
 * @property status Current task-step status.
 */
@Serializable
public data class PlanItemArg(
    public val step: String,
    public val status: StepStatus,
)

/**
 * Status values accepted by the Codex `update_plan` tool.
 */
@Serializable
public enum class StepStatus {
    @SerialName("pending")
    Pending,

    @SerialName("in_progress")
    InProgress,

    @SerialName("completed")
    Completed,
}
