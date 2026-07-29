package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenToolArguments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Image-generation input awaiting a result. */
@Serializable
public sealed interface PendingImageGenerationRequest {
    /** Input supplied to the local `image_gen.imagegen` function tool. */
    @Serializable
    @SerialName("tool")
    public data class Tool(
        public val arguments: ImageGenToolArguments,
    ) : PendingImageGenerationRequest

    /** Hosted image generation without exposed prompt arguments. */
    @Serializable
    @SerialName("hosted")
    public data object Hosted : PendingImageGenerationRequest
}
