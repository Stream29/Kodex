package io.github.stream29.kodex.utils.images

public sealed interface PromptImageMode {
    public data object ResizeToFit : PromptImageMode
    public data object Original : PromptImageMode
}

public data class PromptImagePlan(
    public val source: ImageInfo,
    public val outputDimensions: ImageDimensions,
    public val outputMimeType: ImageMimeType,
    public val preservesSourceBytes: Boolean,
)

public val PromptImagePlan.requiresTransformation: Boolean
    get() = !preservesSourceBytes

/**
 * Encoded image bytes plus the metadata needed to send them as model input.
 * Copies constructor input and every [bytes] result; metadata is supplied, not decoded/validated.
 */
public class EncodedImage(
    bytes: ByteArray,
    public val mimeType: ImageMimeType,
    public val dimensions: ImageDimensions,
) {
    private val content: ByteArray = bytes.copyOf()

    public val bytes: ByteArray
        get() = content.copyOf()

    public fun toDataUrl(): String =
        content.toDataUrl(mimeType)
}

/**
 * Image transformation request for platform codec implementations.
 * Copies constructor input and every [sourceBytes] result.
 */
public class PromptImageTransformRequest(
    sourceBytes: ByteArray,
    public val plan: PromptImagePlan,
) {
    private val content: ByteArray = sourceBytes.copyOf()

    public val sourceBytes: ByteArray
        get() = content.copyOf()
}

/**
 * Transforms encoded image bytes according to the captured prompt-image plan.
 */
public fun interface PromptImageTransformer {
    public suspend fun transform(request: PromptImageTransformRequest): EncodedImage
}
