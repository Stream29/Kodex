package io.github.stream29.kodex.utils.images

public expect fun ImageInfo.planPromptImage(mode: PromptImageMode): PromptImagePlan

/**
 * Inspects headers and preserves bytes only when the plan needs no transformation.
 * Does not fully decode the image or enforce a byte-count limit.
 *
 * @throws UnsupportedImageFormatException if no supported container signature matches.
 * @throws InvalidImageException if recognized dimension headers are invalid.
 * @throws ImageTransformRequiredException if resizing or GIF-to-PNG conversion is required.
 */
public expect fun ByteArray.toPromptImage(mode: PromptImageMode): EncodedImage

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
public expect suspend fun ByteArray.toPromptImage(
    mode: PromptImageMode,
    transformer: PromptImageTransformer,
): EncodedImage

/**
 * Decodes a guarded data URL, then prepares it without a codec.
 *
 * @throws InvalidImageDataUrlException if the data URL or base64 payload is invalid.
 * @throws ImageInputTooLargeException if the encoded or decoded input exceeds [PromptImages.MaxInputBytes].
 * @throws UnsupportedImageFormatException if no supported container signature matches.
 * @throws InvalidImageException if recognized dimension headers are invalid.
 * @throws ImageTransformRequiredException if the plan requires resizing or transcoding.
 */
public expect fun String.toPromptImage(mode: PromptImageMode): EncodedImage

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
public expect suspend fun String.toPromptImage(
    mode: PromptImageMode,
    transformer: PromptImageTransformer,
): EncodedImage
