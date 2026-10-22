package io.github.stream29.kodex.utils.images.codec

import io.github.stream29.kodex.utils.images.EncodedImage
import io.github.stream29.kodex.utils.images.PromptImageMode
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.images.PromptImages
import io.github.stream29.kodex.utils.images.toPromptImage
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

public actual suspend fun CoroutineFileSystem.readPromptImage(
    path: Path,
    mode: PromptImageMode,
): EncodedImage =
    readBytes(path, maxByteCount = PromptImages.MaxInputBytes).toPromptImage(mode)

public actual suspend fun CoroutineFileSystem.readPromptImage(
    path: Path,
    mode: PromptImageMode,
    transformer: PromptImageTransformer,
): EncodedImage =
    readBytes(path, maxByteCount = PromptImages.MaxInputBytes).toPromptImage(mode, transformer)

public actual suspend fun CoroutineFileSystem.writeEncodedImage(
    path: Path,
    image: EncodedImage,
    append: Boolean,
) {
    writeBytes(path, image.bytes, append)
}
