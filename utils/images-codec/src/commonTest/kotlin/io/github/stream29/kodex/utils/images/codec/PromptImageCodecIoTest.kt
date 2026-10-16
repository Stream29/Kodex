package io.github.stream29.kodex.utils.images.codec

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.utils.images.EncodedImage
import io.github.stream29.kodex.utils.images.ImageDimensions
import io.github.stream29.kodex.utils.images.ImageMimeType
import io.github.stream29.kodex.utils.images.PromptImageMode
import io.github.stream29.kodex.utils.images.PromptImageResizeLimits
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.images.ImageTransformRequiredException
import io.github.stream29.kodex.utils.images.InvalidImageException
import io.github.stream29.kodex.utils.images.UnsupportedImageFormatException
import io.github.stream29.kodex.utils.images.PromptImages
import io.github.stream29.kodex.utils.images.detectImageInfo
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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
        test("read overloads propagate header failures and no-codec transform requirements") { root ->
            val path = Path(root, "input.png")
            SystemCoroutineFileSystem.writeBytes(path, pngBytes(4096, 2048))
            assertFailsWith<ImageTransformRequiredException> {
                SystemCoroutineFileSystem.readPromptImage(path, PromptImageMode.ResizeToFit)
            }
            val unused = PromptImageTransformer { error("Header failure must precede transformation") }
            SystemCoroutineFileSystem.writeBytes(path, pngBytes(64, 32).copyOf(16))
            assertFailsWith<InvalidImageException> {
                SystemCoroutineFileSystem.readPromptImage(path, PromptImageMode.Original)
            }
            assertFailsWith<InvalidImageException> {
                SystemCoroutineFileSystem.readPromptImage(path, PromptImageMode.Original, unused)
            }
            SystemCoroutineFileSystem.writeBytes(path, byteArrayOf(1, 2, 3))
            assertFailsWith<UnsupportedImageFormatException> {
                SystemCoroutineFileSystem.readPromptImage(path, PromptImageMode.Original)
            }
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
                SystemCoroutineFileSystem.readPromptImage(path, PromptImageMode.ResizeToFit,
                    PromptImageTransformer { throw failure })
            })
            assertSame(cancelled, assertFailsWith<CancellationException> {
                SystemCoroutineFileSystem.readPromptImage(path, PromptImageMode.ResizeToFit,
                    PromptImageTransformer { throw cancelled })
            })
            // The borrowed filesystem remains usable; no new resource owner was installed.
            assertContentEquals(bytes, SystemCoroutineFileSystem.readBytes(path))
        }
        test("both read overloads pass the input bound and propagate filesystem failures") { root ->
            val path = Path(root, "bounded.png")
            val failure = IOException("controlled bounded-read failure")
            val bounded = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                override suspend fun readBytes(path: Path, maxByteCount: Long): ByteArray {
                    assertEquals(PromptImages.MaxInputBytes, maxByteCount)
                    throw failure
                }
            }
            assertSame(failure, assertFailsWith<IOException> {
                bounded.readPromptImage(path, PromptImageMode.Original)
            })
            assertSame(failure, assertFailsWith<IOException> {
                bounded.readPromptImage(path, PromptImageMode.Original, PromptImageTransformer { error("No bytes") })
            })
        }
        test("write encoded image replaces or appends raw bytes without validating a container") { root ->
            val path = Path(root, "output.png")
            val first = EncodedImage(pngBytes(64, 32), ImageMimeType.Png, ImageDimensions(64, 32))
            val next = EncodedImage(byteArrayOf(1, 2, 3), ImageMimeType.Png, ImageDimensions(1, 1))
            SystemCoroutineFileSystem.writeEncodedImage(path, first)
            assertContentEquals(first.bytes, SystemCoroutineFileSystem.readBytes(path))
            SystemCoroutineFileSystem.writeEncodedImage(path, next, append = true)
            assertContentEquals(first.bytes + next.bytes, SystemCoroutineFileSystem.readBytes(path))
            SystemCoroutineFileSystem.writeEncodedImage(path, next)
            assertContentEquals(next.bytes, SystemCoroutineFileSystem.readBytes(path))
        }
        test("write propagates filesystem failure and cancellation unchanged") { root ->
            val image = EncodedImage(byteArrayOf(1), ImageMimeType.Png, ImageDimensions(1, 1))
            val path = Path(root, "output.png")
            for (failure in listOf(IOException("write"), CancellationException("write cancelled"))) {
                val failing = object : CoroutineFileSystem by SystemCoroutineFileSystem {
                    override suspend fun writeBytes(
                        path: Path, content: ByteArray, append: Boolean, mustCreate: Boolean,
                    ) {
                        assertContentEquals(image.bytes, content)
                        assertEquals(false, append)
                        assertEquals(false, mustCreate)
                        throw failure
                    }
                }
                assertSame(failure, assertFailsWith<Exception> { failing.writeEncodedImage(path, image) })
            }
        }
    }
}
