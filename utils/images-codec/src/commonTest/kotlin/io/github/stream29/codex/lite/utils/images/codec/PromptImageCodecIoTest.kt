package io.github.stream29.codex.lite.utils.images.codec

import io.github.stream29.codex.lite.utils.images.EncodedImage
import io.github.stream29.codex.lite.utils.images.ImageDimensions
import io.github.stream29.codex.lite.utils.images.ImageMimeType
import io.github.stream29.codex.lite.utils.images.PromptImageMode
import io.github.stream29.codex.lite.utils.images.PromptImageResizeLimits
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import io.github.stream29.codex.lite.utils.images.detectImageInfo
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PromptImageCodecIoTest {
    @Test
    fun readPromptImagePreservesBytesWhenNoTransformationIsNeeded() = runTest {
        val root = Path(SystemTemporaryDirectory, "codex-lite-images-codec-${Random.nextLong()}")
        val path = Path(root, "image.png")
        val bytes = pngBytes(64, 32)
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeBytes(path, bytes)

            val image = SystemCoroutineFileSystem.readPromptImage(path, PromptImageMode.Original)

            assertEquals(ImageMimeType.Png, image.mimeType)
            assertEquals(ImageDimensions(64, 32), image.dimensions)
            assertContentEquals(bytes, image.bytes)
        } finally {
            SystemCoroutineFileSystem.delete(path, mustExist = false)
            SystemCoroutineFileSystem.delete(root, mustExist = false)
        }
    }

    @Test
    fun readPromptImageUsesTransformerWhenTransformationIsNeeded() = runTest {
        val root = Path(SystemTemporaryDirectory, "codex-lite-images-codec-transform-${Random.nextLong()}")
        val path = Path(root, "image.png")
        val bytes = pngBytes(4096, 2048)
        val transformedBytes = pngBytes(32, 16)
        val transformer = PromptImageTransformer { request ->
            EncodedImage(
                bytes = transformedBytes,
                mimeType = request.plan.outputMimeType,
                dimensions = request.plan.outputDimensions,
            )
        }
        try {
            SystemCoroutineFileSystem.createDirectories(root)
            SystemCoroutineFileSystem.writeBytes(path, bytes)

            val image = SystemCoroutineFileSystem.readPromptImage(
                path = path,
                mode = PromptImageMode.ResizeWithLimits(
                    PromptImageResizeLimits(maxDimension = 32, maxPatches = 10_000),
                ),
                transformer = transformer,
            )

            assertEquals(ImageMimeType.Png, image.mimeType)
            assertEquals(ImageDimensions(32, 16), image.dimensions)
            assertEquals(ImageDimensions(32, 16), image.bytes.detectImageInfo()?.dimensions)
        } finally {
            SystemCoroutineFileSystem.delete(path, mustExist = false)
            SystemCoroutineFileSystem.delete(root, mustExist = false)
        }
    }
}
