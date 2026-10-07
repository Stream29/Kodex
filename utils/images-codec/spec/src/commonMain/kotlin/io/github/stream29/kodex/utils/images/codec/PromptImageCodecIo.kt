package io.github.stream29.kodex.utils.images.codec

import io.github.stream29.kodex.utils.images.EncodedImage
import io.github.stream29.kodex.utils.images.PromptImageMode
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.images.PromptImages
import io.github.stream29.kodex.utils.images.toPromptImage
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

/**
 * Reads image bytes and prepares prompt image input without codec transformation.
 * Borrows this filesystem, closing only the per-call read handle. Header inspection
 * is not full codec validation; source bytes are preserved when the plan permits.
 * Other filesystem failures propagate without normalization.
 *
 * @throws kotlinx.io.IOException if input exceeds [PromptImages.MaxInputBytes] or filesystem IO fails.
 * @throws io.github.stream29.kodex.utils.images.UnsupportedImageFormatException if no supported signature matches.
 * @throws io.github.stream29.kodex.utils.images.InvalidImageException if recognized dimension headers are invalid.
 * @throws io.github.stream29.kodex.utils.images.ImageTransformRequiredException if resizing or transcoding is required.
 * @throws kotlinx.coroutines.CancellationException if filesystem IO observes cancellation.
 */
public suspend fun CoroutineFileSystem.readPromptImage(
    path: Path,
    mode: PromptImageMode,
): EncodedImage =
    readBytes(path, maxByteCount = PromptImages.MaxInputBytes).toPromptImage(mode)

/**
 * Reads image bytes and prepares prompt image input with a platform transformer when needed.
 * Borrows filesystem and transformer; closes only the per-call read handle. Checks
 * returned metadata against the plan, not the returned bytes' full decodability.
 * Other filesystem/transformer failures propagate unchanged; synchronous codec work
 * need not be promptly interruptible.
 *
 * @throws kotlinx.io.IOException if input exceeds [PromptImages.MaxInputBytes] or filesystem IO fails.
 * @throws io.github.stream29.kodex.utils.images.UnsupportedImageFormatException if no supported signature matches.
 * @throws io.github.stream29.kodex.utils.images.InvalidImageException if recognized dimension headers are invalid.
 * @throws IllegalArgumentException if transformer result metadata differs from the plan.
 * @throws UnsupportedOperationException if the transformer lacks the required codec capability.
 * @throws IllegalStateException if a codec operation fails.
 * @throws kotlinx.coroutines.CancellationException if filesystem IO or transformation observes cancellation.
 */
public suspend fun CoroutineFileSystem.readPromptImage(
    path: Path,
    mode: PromptImageMode,
    transformer: PromptImageTransformer,
): EncodedImage =
    readBytes(path, maxByteCount = PromptImages.MaxInputBytes).toPromptImage(mode, transformer)

/**
 * Writes encoded image bytes to a filesystem path.
 * Borrows this filesystem and closes the per-call write handle. [append] appends raw
 * bytes instead of replacing content; it does not combine images into a valid container.
 * Does not create parent directories, validate encoded content, or provide atomic rollback.
 * Other filesystem failures propagate unchanged; a failed write may leave partial content.
 *
 * @throws kotlinx.io.IOException if filesystem IO fails.
 * @throws kotlinx.coroutines.CancellationException if filesystem IO observes cancellation.
 */
public suspend fun CoroutineFileSystem.writeEncodedImage(
    path: Path,
    image: EncodedImage,
    append: Boolean = false,
) {
    writeBytes(path, image.bytes, append)
}
