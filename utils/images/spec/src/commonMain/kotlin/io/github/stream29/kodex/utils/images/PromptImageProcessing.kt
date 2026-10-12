package io.github.stream29.kodex.utils.images

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
