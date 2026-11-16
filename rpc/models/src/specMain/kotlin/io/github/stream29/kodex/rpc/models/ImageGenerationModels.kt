package io.github.stream29.kodex.rpc.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @property referencedImagePaths Nullable because brand-new image generation
 * has no referenced local images; `null` means use no path-based edit images.
 * @property numLastImagesToInclude Retained for wire compatibility. This host
 * does not bind conversation image history; a non-null value is unsupported
 * and produces an explicit failure. Omit it and use local reference paths.
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
 * Saved PNG file artifact returned by `AgentRuntimeRpc.generateImage` and `kodex image`.
 *
 * Callers inspect the generated image in model context via `read_media(path)`.
 */
@Serializable
public data class GeneratedImageArtifact(
    public val path: String,
    @SerialName("mime_type")
    public val mimeType: String = "image/png",
)
