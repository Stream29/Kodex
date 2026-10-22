package io.github.stream29.kodex.utils.images

public actual fun ImageInfo.planPromptImage(mode: PromptImageMode): PromptImagePlan {
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

public actual fun ByteArray.toPromptImage(mode: PromptImageMode): EncodedImage {
    val info = requireImageInfo()
    val plan = info.planPromptImage(mode)
    if (plan.requiresTransformation) {
        throw ImageTransformRequiredException(plan)
    }
    return EncodedImage(this, info.mimeType, info.dimensions)
}

public actual suspend fun ByteArray.toPromptImage(
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

public actual fun String.toPromptImage(mode: PromptImageMode): EncodedImage =
    decodePromptImageDataUrlBytes().toPromptImage(mode)

public actual suspend fun String.toPromptImage(
    mode: PromptImageMode,
    transformer: PromptImageTransformer,
): EncodedImage =
    decodePromptImageDataUrlBytes().toPromptImage(mode, transformer)
