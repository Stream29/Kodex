package io.github.stream29.codex.lite.utils.images

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
 */
public class PromptImageTransformRequest(
    sourceBytes: ByteArray,
    public val plan: PromptImagePlan,
) {
    private val content: ByteArray = sourceBytes.copyOf()

    public val sourceBytes: ByteArray
        get() = content.copyOf()
}

public fun interface PromptImageTransformer {
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

public fun ByteArray.toPromptImage(mode: PromptImageMode): EncodedImage {
    val info = requireImageInfo()
    val plan = info.planPromptImage(mode)
    if (plan.requiresTransformation) {
        throw ImageTransformRequiredException(plan)
    }
    return EncodedImage(this, info.mimeType, info.dimensions)
}

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

public fun String.toPromptImage(mode: PromptImageMode): EncodedImage =
    decodePromptImageDataUrlBytes().toPromptImage(mode)

public suspend fun String.toPromptImage(
    mode: PromptImageMode,
    transformer: PromptImageTransformer,
): EncodedImage =
    decodePromptImageDataUrlBytes().toPromptImage(mode, transformer)
