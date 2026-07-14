package io.github.stream29.codex.lite.utils.images.codec

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.utils.images.EncodedImage
import io.github.stream29.codex.lite.utils.images.ImageDimensions
import io.github.stream29.codex.lite.utils.images.ImageMimeType
import io.github.stream29.codex.lite.utils.images.PromptImageMode
import io.github.stream29.codex.lite.utils.images.PromptImageResizeLimits
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import io.github.stream29.codex.lite.utils.images.detectImageInfo
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

private suspend fun temporaryRoot(): Path =
    Path(SystemTemporaryDirectory, "codex-lite-images-codec-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        for (child in SystemCoroutineFileSystem.list(path)) {
            deleteRecursively(child)
        }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

val promptImageCodecIoTest by testSuite {
    testFixture { temporaryRoot() } closeWith { deleteRecursively(this) } asParameterForEach {
        test("read prompt image preserves bytes when no transformation is needed") { root ->
            val path = Path(root, "image.png")
            val bytes = pngBytes(64, 32)
            SystemCoroutineFileSystem.writeBytes(path, bytes)

            val image = SystemCoroutineFileSystem.readPromptImage(path, PromptImageMode.Original)

            assertEquals(ImageMimeType.Png, image.mimeType)
            assertEquals(ImageDimensions(64, 32), image.dimensions)
            assertContentEquals(bytes, image.bytes)
        }

        test("read prompt image uses transformer when transformation is needed") { root ->
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
        }
    }
}
