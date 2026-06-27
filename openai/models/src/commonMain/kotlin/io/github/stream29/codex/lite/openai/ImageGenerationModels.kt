package io.github.stream29.codex.lite.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI image generation request.
 *
 * @property background Nullable because the API may choose the default;
 * `null` means omit this field.
 * @property n Nullable because image count is optional; `null` means omit this field.
 * @property quality Nullable because quality is optional; `null` means omit this field.
 * @property size Nullable because size is optional; `null` means omit this field.
 */
@Serializable
public data class ImageGenerationRequest(
    public val prompt: String,
    public val model: OpenAiModelId,
    public val background: ImageBackground? = null,
    public val n: Long? = null,
    public val quality: ImageQuality? = null,
    public val size: String? = null,
)

/**
 * OpenAI image edit request.
 *
 * @property background Nullable because the API may choose the default;
 * `null` means omit this field.
 * @property n Nullable because image count is optional; `null` means omit this field.
 * @property quality Nullable because quality is optional; `null` means omit this field.
 * @property size Nullable because size is optional; `null` means omit this field.
 */
@Serializable
public data class ImageEditRequest(
    public val images: List<ImageUrl>,
    public val prompt: String,
    public val model: OpenAiModelId,
    public val background: ImageBackground? = null,
    public val n: Long? = null,
    public val quality: ImageQuality? = null,
    public val size: String? = null,
)

@Serializable
public data class ImageUrl(
    @SerialName("image_url")
    public val imageUrl: String,
)

@Serializable
public enum class ImageBackground {
    @SerialName("transparent")
    Transparent,

    @SerialName("opaque")
    Opaque,

    @SerialName("auto")
    Auto,
}

@Serializable
public enum class ImageQuality {
    @SerialName("low")
    Low,

    @SerialName("medium")
    Medium,

    @SerialName("high")
    High,

    @SerialName("auto")
    Auto,
}

/**
 * OpenAI image response.
 *
 * @property background Nullable because some API responses omit resolved
 * background; `null` means no value was returned.
 * @property quality Nullable because some API responses omit resolved quality;
 * `null` means no value was returned.
 * @property size Nullable because some API responses omit resolved size;
 * `null` means no value was returned.
 */
@Serializable
public data class ImageResponse(
    public val created: Long,
    public val data: List<ImageData>,
    public val background: ImageBackground? = null,
    public val quality: ImageQuality? = null,
    public val size: String? = null,
)

@Serializable
public data class ImageData(
    @SerialName("b64_json")
    public val b64Json: String,
)
