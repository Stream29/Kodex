package io.github.stream29.kodex.utils.images

public expect fun ImageInfo.planPromptImage(mode: PromptImageMode): PromptImagePlan

/**
 * Uses [transformer] only if the header-derived plan requires it; otherwise copies source bytes.
 * The transformer is borrowed, never closed. Result metadata is checked against the plan,
 * not independently decoded from the returned bytes. No input byte-count limit is enforced.
 * Other transformer failures propagate unchanged.
 */
public expect suspend fun ByteArray.toPromptImage(
    mode: PromptImageMode,
    transformer: PromptImageTransformer,
): EncodedImage
