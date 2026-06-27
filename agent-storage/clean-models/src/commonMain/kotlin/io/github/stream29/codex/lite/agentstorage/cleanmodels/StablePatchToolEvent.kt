package io.github.stream29.codex.lite.agentstorage.cleanmodels

import io.github.stream29.codex.lite.utils.applypatch.Patch
import io.github.stream29.codex.lite.utils.applypatch.PatchApplyResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of an `apply_patch` tool interaction.
 *
 * The event stores the complete parsed diff instead of raw patch text only, so
 * consumers can render file-level and chunk-level changes without reparsing the
 * custom-tool payload.
 *
 * @property diff Complete parsed patch input.
 * @property result Tool execution result after attempting to apply [diff].
 */
@Serializable
public data class StablePatchToolEvent(
    public val diff: Patch,
    public val result: StablePatchToolExecutionResult,
)

/**
 * Execution result for an `apply_patch` tool event.
 */
@Serializable
public sealed interface StablePatchToolExecutionResult {
    /**
     * Patch application completed.
     *
     * @property applyResult File-system changes produced by applying the patch.
     */
    @Serializable
    @SerialName("success")
    public data class Success(
        @SerialName("apply_result")
        public val applyResult: PatchApplyResult,
    ) : StablePatchToolExecutionResult

    /**
     * Patch application failed.
     *
     * @property reason User-visible failure reason from parsing or applying the
     * patch.
     */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val reason: String,
    ) : StablePatchToolExecutionResult
}
