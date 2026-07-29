package io.github.stream29.codex.lite.tool.imagegeneration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @property referencedImagePaths Nullable because brand-new image generation
 * has no referenced local images; `null` means use no path-based edit images.
 * @property numLastImagesToInclude Nullable because history-based edit images
 * are optional; `null` means do not select recent conversation images.
 */
@Serializable
public data class ImageGenToolArguments(
    public val prompt: String,
    @SerialName("referenced_image_paths")
    public val referencedImagePaths: List<String>? = null,
    @SerialName("num_last_images_to_include")
    public val numLastImagesToInclude: Long? = null,
)

/**
 * @property outputHint Nullable because not every caller persists generated
 * bytes to a local artifact; `null` means no persistence hint is available.
 */
@Serializable
public data class GeneratedImageOutput(
    public val result: String,
    @SerialName("output_hint")
    public val outputHint: String? = null,
)

public class ImageGenerationToolException(
    message: String,
) : IllegalArgumentException(message)
