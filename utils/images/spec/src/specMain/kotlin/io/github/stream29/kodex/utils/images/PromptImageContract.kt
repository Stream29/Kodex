package io.github.stream29.kodex.utils.images

public sealed interface PromptImageMode {
    public data object ResizeToFit : PromptImageMode
    public data object Original : PromptImageMode
    public data class ResizeWithLimits(public val limits: PromptImageResizeLimits) : PromptImageMode
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
 *
 * The returned image must have the plan's output dimensions and MIME type. It
 * owns its encoded bytes; neither caller input nor returned bytes may alias a
 * codec's mutable working buffer. Platform codecs may support different input
 * formats, but do not choose a different resize policy or silently change the
 * requested output format.
 */
public fun interface PromptImageTransformer {
    /**
     * Decodes, resizes and encodes the given request. The transformer does not
     * own the calling scope or a persistent filesystem/application resource.
     * Per-call codec resources belong to the implementation. Cancellation is propagated
     * where observed; synchronous native/codec work need not be promptly interruptible.
     * Platform codec failures are not universally normalized.
     *
     * @throws UnsupportedOperationException if the codec cannot decode the
     * source or encode the requested output format.
     * @throws IllegalStateException if a supported codec operation fails.
     * @throws kotlinx.coroutines.CancellationException if the operation is
     * cancelled at a suspending codec boundary.
     */
    public suspend fun transform(request: PromptImageTransformRequest): EncodedImage
}

/**
 * Image bytes need decoding, resizing, or re-encoding before they can be used as prompt input.
 */
public class ImageTransformRequiredException(
    public val plan: PromptImagePlan,
) : IllegalStateException(
    "Image transformation is required for ${plan.source.mimeType.mime} " +
        "${plan.source.dimensions.width}x${plan.source.dimensions.height}",
)
