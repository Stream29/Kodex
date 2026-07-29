package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import io.github.stream29.codex.lite.tool.viewimage.ViewImageToolArguments
import io.github.stream29.codex.lite.tool.viewimage.ViewImageToolOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a completed `view_image` interaction.
 *
 * @property arguments Tool-native image-view arguments.
 * @property result Image inspection outcome.
 */
@Serializable
@SerialName("image_view_tool_event")
public data class StableImageViewToolEvent(
    public val arguments: ViewImageToolArguments,
    public val result: StableImageViewResult,
) : StableCleanEvent.CompletedTool

/** Completed outcome of an image inspection. */
@Serializable
public sealed interface StableImageViewResult {
    /** Image bytes were loaded and exposed through [imageUrl]. */
    @Serializable
    @SerialName("success")
    public data class Success(
        public val output: ViewImageToolOutput,
    ) : StableImageViewResult

    /** The image could not be loaded. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableImageViewResult
}
