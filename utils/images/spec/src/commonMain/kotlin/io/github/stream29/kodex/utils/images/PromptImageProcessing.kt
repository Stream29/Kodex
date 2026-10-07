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

public fun ImageInfo.planPromptImage(mode: PromptImageMode): PromptImagePlan {
    val outputDimensions = when (mode) {
        PromptImageMode.Original -> dimensions
        PromptImageMode.ResizeToFit -> dimensions.fitWithinMaxDimension(PromptImages.MaxDimension)
        is PromptImageMode.ResizeWithLimits -> dimensions.fitPromptImageLimits(mode.limits)
    }
    val outputMimeType = if (mimeType.canPreserveSourceBytes) mimeType else ImageMimeType.Png
    val preservesSourceBytes = outputDimensions == dimensions && mimeType.canPreserveSourceBytes
    return PromptImagePlan(
        source = this,
        outputDimensions = outputDimensions,
        outputMimeType = outputMimeType,
        preservesSourceBytes = preservesSourceBytes,
    )
}

/**
 * Inspects headers and preserves bytes only when the plan needs no transformation.
 * Does not fully decode the image or enforce a byte-count limit.
 *
 * @throws UnsupportedImageFormatException if no supported container signature matches.
 * @throws InvalidImageException if recognized dimension headers are invalid.
 * @throws ImageTransformRequiredException if resizing or GIF-to-PNG conversion is required.
 */
public fun ByteArray.toPromptImage(mode: PromptImageMode): EncodedImage {
    val info = requireImageInfo()
    val plan = info.planPromptImage(mode)
    if (plan.requiresTransformation) {
        throw ImageTransformRequiredException(plan)
    }
    return EncodedImage(this, info.mimeType, info.dimensions)
}

/**
 * Uses [transformer] only if the header-derived plan requires it; otherwise copies source bytes.
 * The transformer is borrowed, never closed. Result metadata is checked against the plan,
 * not independently decoded from the returned bytes. No input byte-count limit is enforced.
 * Other transformer failures propagate unchanged.
 *
 * @throws UnsupportedImageFormatException if no supported container signature matches.
 * @throws InvalidImageException if recognized dimension headers are invalid.
 * @throws IllegalArgumentException if returned MIME or dimensions differ from the plan.
 * @throws UnsupportedOperationException if the transformer lacks the required codec capability.
 * @throws IllegalStateException if a codec operation fails.
 * @throws kotlinx.coroutines.CancellationException if transformation observes cancellation.
 */
public suspend fun ByteArray.toPromptImage(
    mode: PromptImageMode,
    transformer: PromptImageTransformer,
): EncodedImage {
    val info = requireImageInfo()
    val plan = info.planPromptImage(mode)
    if (!plan.requiresTransformation) {
        return EncodedImage(this, info.mimeType, info.dimensions)
    }

    val transformed = transformer.transform(PromptImageTransformRequest(this, plan))
    require(transformed.mimeType == plan.outputMimeType) {
        "transformed image MIME type must be ${plan.outputMimeType.mime}"
    }
    require(transformed.dimensions == plan.outputDimensions) {
        "transformed image dimensions must be ${plan.outputDimensions}"
    }
    return transformed
}

/**
 * Decodes a guarded data URL, then prepares it without a codec.
 *
 * @throws InvalidImageDataUrlException if the data URL or base64 payload is invalid.
 * @throws ImageInputTooLargeException if the encoded or decoded input exceeds [PromptImages.MaxInputBytes].
 * @throws UnsupportedImageFormatException if no supported container signature matches.
 * @throws InvalidImageException if recognized dimension headers are invalid.
 * @throws ImageTransformRequiredException if the plan requires resizing or transcoding.
 */
public fun String.toPromptImage(mode: PromptImageMode): EncodedImage =
    decodePromptImageDataUrlBytes().toPromptImage(mode)

/**
 * Decodes a guarded data URL, then borrows [transformer] when the plan needs it.
 * Other transformer failures propagate unchanged; no full-image validation is added.
 *
 * @throws InvalidImageDataUrlException if the data URL or base64 payload is invalid.
 * @throws ImageInputTooLargeException if the encoded or decoded input exceeds [PromptImages.MaxInputBytes].
 * @throws UnsupportedImageFormatException if no supported container signature matches.
 * @throws InvalidImageException if recognized dimension headers are invalid.
 * @throws IllegalArgumentException if transformer result metadata differs from the plan.
 * @throws UnsupportedOperationException if the transformer lacks the required codec capability.
 * @throws IllegalStateException if a codec operation fails.
 * @throws kotlinx.coroutines.CancellationException if transformation observes cancellation.
 */
public suspend fun String.toPromptImage(
    mode: PromptImageMode,
    transformer: PromptImageTransformer,
): EncodedImage =
    decodePromptImageDataUrlBytes().toPromptImage(mode, transformer)
