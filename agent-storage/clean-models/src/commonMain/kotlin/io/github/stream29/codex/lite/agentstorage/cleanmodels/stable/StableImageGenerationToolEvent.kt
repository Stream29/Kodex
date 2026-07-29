package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean projection of a completed image-generation interaction.
 *
 * Local function tools and hosted image generation share the same result model,
 * while [request] preserves which input shape was available.
 */
@Serializable
@SerialName("image_generation_tool_event")
public data class StableImageGenerationToolEvent(
    public val request: StableImageGenerationRequest,
    public val result: StableImageGenerationResult,
) : StableToolEvent

/** Input shape available for an image-generation interaction. */
@Serializable
public sealed interface StableImageGenerationRequest {
    /** Input supplied to the local `image_gen.imagegen` function tool. */
    @Serializable
    @SerialName("tool")
    public data class Tool(
        public val prompt: String,
        @SerialName("referenced_image_paths")
        public val referencedImagePaths: List<String>? = null,
        @SerialName("num_last_images_to_include")
        public val numLastImagesToInclude: Long? = null,
    ) : StableImageGenerationRequest

    /** Hosted image generation did not expose the original prompt as arguments. */
    @Serializable
    @SerialName("hosted")
    public data object Hosted : StableImageGenerationRequest
}

/** Completed outcome of image generation. */
@Serializable
public sealed interface StableImageGenerationResult {
    /**
     * An image was generated.
     *
     * @property imageUrl URL or data URL containing the generated image.
     * @property outputHint Nullable model-visible persistence hint.
     * @property savedPath Nullable local artifact path.
     * @property revisedPrompt Nullable provider-revised prompt.
     */
    @Serializable
    @SerialName("success")
    public data class Success(
        @SerialName("image_url")
        public val imageUrl: String,
        @SerialName("output_hint")
        public val outputHint: String? = null,
        @SerialName("saved_path")
        public val savedPath: String? = null,
        @SerialName("revised_prompt")
        public val revisedPrompt: String? = null,
    ) : StableImageGenerationResult

    /** Image generation failed. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        public val message: String,
    ) : StableImageGenerationResult
}
