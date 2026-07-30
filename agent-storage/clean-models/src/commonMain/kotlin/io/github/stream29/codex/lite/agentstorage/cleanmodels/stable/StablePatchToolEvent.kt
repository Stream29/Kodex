package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
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
 * @property callId Correlates the projected call and output.
 * @property itemId Provider item id of the custom-tool call, if present.
 * @property diff Complete parsed patch input.
 * @property result Tool execution result after attempting to apply [diff].
 */
@Serializable
@SerialName("patch_tool_event")
public data class StablePatchToolEvent(
    @SerialName("call_id")
    public val callId: String,
    @SerialName("item_id")
    public val itemId: ResponseItemId? = null,
    public val diff: Patch,
    public val result: StablePatchToolExecutionResult,
) : StableCleanEvent.CompletedTool {
    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            ResponseItem.CustomToolCall(
                id = itemId,
                callId = callId,
                name = "apply_patch",
                input = diff.patch,
            ),
            ResponseItem.CustomToolCallOutput(
                callId = callId,
                output = result.toFunctionCallOutputPayload(),
            ),
        )
}

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

private fun StablePatchToolExecutionResult.toFunctionCallOutputPayload(): FunctionCallOutputPayload =
    when (this) {
        is StablePatchToolExecutionResult.Success ->
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.Text("Success. Patch applied."),
                success = true,
            )

        is StablePatchToolExecutionResult.Failure ->
            FunctionCallOutputPayload(
                body = FunctionCallOutputBody.Text(reason),
                success = false,
            )
    }
