package io.github.stream29.codex.lite.utils.images.codec

import io.github.stream29.codex.lite.utils.images.EncodedImage
import io.github.stream29.codex.lite.utils.images.PromptImageMode
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import io.github.stream29.codex.lite.utils.images.PromptImages
import io.github.stream29.codex.lite.utils.images.toPromptImage
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

/**
 * Reads image bytes and prepares prompt image input without codec transformation.
 */
public suspend fun CoroutineFileSystem.readPromptImage(
    path: Path,
    mode: PromptImageMode,
): EncodedImage =
    readBytes(path, maxByteCount = PromptImages.MaxInputBytes).toPromptImage(mode)

/**
 * Reads image bytes and prepares prompt image input with a platform transformer when needed.
 */
public suspend fun CoroutineFileSystem.readPromptImage(
    path: Path,
    mode: PromptImageMode,
    transformer: PromptImageTransformer,
): EncodedImage =
    readBytes(path, maxByteCount = PromptImages.MaxInputBytes).toPromptImage(mode, transformer)

/**
 * Writes encoded image bytes to a filesystem path.
 */
public suspend fun CoroutineFileSystem.writeEncodedImage(
    path: Path,
    image: EncodedImage,
    append: Boolean = false,
) {
    writeBytes(path, image.bytes, append)
}
