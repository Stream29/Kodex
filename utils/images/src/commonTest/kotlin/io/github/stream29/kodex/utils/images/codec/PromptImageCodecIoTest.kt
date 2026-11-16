package io.github.stream29.kodex.utils.images.codec

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.images.EncodedImage
import io.github.stream29.kodex.utils.images.ImageDimensions
import io.github.stream29.kodex.utils.images.ImageMimeType
import io.github.stream29.kodex.utils.images.InvalidImageException
import io.github.stream29.kodex.utils.images.PromptImageMode
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.images.PromptImages
import io.github.stream29.kodex.utils.images.UnsupportedImageFormatException
import io.github.stream29.kodex.utils.images.detectImageInfo
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory

private suspend fun temporaryRoot(): Path =
    Path(SystemTemporaryDirectory, "kodex-images-codec-${Random.nextLong()}").also {
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

            val image = SystemCoroutineFileSystem.readPromptImage(
                path,
                PromptImageMode.Original,
                PromptImageTransformer { error("No transformation needed") },
            )

            assertEquals(ImageMimeType.Png, image.mimeType)
            assertEquals(ImageDimensions(64, 32), image.dimensions)
            assertContentEquals(bytes, image.bytes)
        }

        test("read prompt image uses transformer when transformation is needed") { root ->
            val path = Path(root, "image.png")
            val bytes = pngBytes(4096, 2048)
            val transformedBytes = pngBytes(2048, 1024)
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
                mode = PromptImageMode.ResizeToFit,
                transformer = transformer,
            )

            assertEquals(ImageMimeType.Png, image.mimeType)
            assertEquals(ImageDimensions(2048, 1024), image.dimensions)
            assertEquals(ImageDimensions(2048, 1024), image.bytes.detectImageInfo()?.dimensions)
        }

        test("read propagates header failures before transformation") { root ->
            val path = Path(root, "input.png")
            val unused = PromptImageTransformer { error("Header failure must precede transformation") }
            SystemCoroutineFileSystem.writeBytes(path, pngBytes(64, 32).copyOf(16))
            assertFailsWith<InvalidImageException> {
                SystemCoroutineFileSystem.readPromptImage(path, PromptImageMode.Original, unused)
            }
            SystemCoroutineFileSystem.writeBytes(path, byteArrayOf(1, 2, 3))
            assertFailsWith<UnsupportedImageFormatException> {
                SystemCoroutineFileSystem.readPromptImage(path, PromptImageMode.Original, unused)
            }
        }

        test("read transformer failures and cancellation propagate without owning the filesystem") { root ->
            val path = Path(root, "input.png")
            val bytes = pngBytes(4096, 2048)
            SystemCoroutineFileSystem.writeBytes(path, bytes)
            val failure = ImageCodecException("controlled codec failure")
            val cancelled = CancellationException("controlled codec cancellation")
            assertSame(failure, assertFailsWith<ImageCodecException> {
                SystemCoroutineFileSystem.readPromptImage(
                    path,
                    PromptImageMode.ResizeToFit,
                    PromptImageTransformer { throw failure },
                )
            })
            assertSame(cancelled, assertFailsWith<CancellationException> {
                SystemCoroutineFileSystem.readPromptImage(
                    path,
                    PromptImageMode.ResizeToFit,
                    PromptImageTransformer { throw cancelled },
                )
            })
            assertContentEquals(bytes, SystemCoroutineFileSystem.readBytes(path))
        }

        test("read passes the input bound and propagates filesystem failures") { root ->
            val path = Path(root, "bounded.png")
            val failure = IOException("controlled bounded-read failure")
            val bounded = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun readBytes(path: Path, maxByteCount: Long): ByteArray {
                    assertEquals(PromptImages.MaxInputBytes, maxByteCount)
                    throw failure
                }
            }
            assertSame(failure, assertFailsWith<IOException> {
                bounded.readPromptImage(path, PromptImageMode.Original, PromptImageTransformer { error("No bytes") })
            })
        }
    }
}
