package io.github.stream29.codex.lite.agentstorage.cleanmodels

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a completed `view_image` interaction.
 *
 * @property path Image path supplied to the tool.
 * @property detail Nullable because callers may omit the requested detail.
 * @property environmentId Nullable because the current environment is implicit.
 * @property result Image inspection outcome.
 */
@Serializable
@SerialName("image_view_tool_event")
public data class StableImageViewToolEvent(
    public val path: String,
    public val detail: StableImageDetail? = null,
    @SerialName("environment_id")
    public val environmentId: String? = null,
    public val result: StableImageViewResult,
) : StableToolEvent

/** Detail level used when presenting an image to the model. */
@Serializable
public enum class StableImageDetail {
    @SerialName("high")
    High,

    @SerialName("original")
    Original,
}

/** Completed outcome of an image inspection. */
@Serializable
public sealed interface StableImageViewResult {
    /** Image bytes were loaded and exposed through [imageUrl]. */
    @Serializable
    @SerialName("success")
    public data class Success(
        @SerialName("image_url")
        public val imageUrl: String,
        public val detail: StableImageDetail,
    ) : StableImageViewResult

    /** The image could not be loaded. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableImageViewResult
}
