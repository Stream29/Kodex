package io.github.stream29.kodex.utils.images.codec

import io.github.stream29.kodex.utils.images.EncodedImage
import io.github.stream29.kodex.utils.images.PromptImageMode
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

/**
 * Reads image bytes and prepares prompt image input with a platform transformer when needed.
 * Borrows filesystem and transformer; closes only the per-call read handle.
 */
public expect suspend fun CoroutineFileSystem.readPromptImage(
    path: Path,
    mode: PromptImageMode,
    transformer: PromptImageTransformer,
): EncodedImage
