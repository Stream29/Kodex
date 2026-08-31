package io.github.stream29.kodex.tool.viewimage

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageViewResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingImageViewToolEvent
import io.github.stream29.kodex.utils.images.ImageMimeType
import io.github.stream29.kodex.utils.images.detectImageInfo
import io.github.stream29.kodex.utils.images.decodePromptImageDataUrlBytes
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private suspend fun temporaryRoot(): Path {
    val root = Path("build/tmp/view-image-test-${Random.nextLong().toString().replace('-', '0')}")
    SystemCoroutineFileSystem.createDirectories(root)
    return root
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

private val png64x32: ByteArray
    get() = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAgklEQVR4Xu3QoRHDABADQeNgY+MUkf6rcC82XxKskcASgZ/5Oz7n9TQ7HNosgEObBXBoswAObf4GuH/faP6jBXCQB9P4jxbAQR5M4z9aAAd5MI3/aAEc5ME0/qMFcJAH0/iPFsBBHkzjP1oAB3kwjf9oARzaLIBDmwVwaLMADm3qA7y8LuS12WzThwAAAABJRU5ErkJggg==",
    )

val viewImageToolClientTest by testSuite {
    testFixture { temporaryRoot() } closeWith { deleteRecursively(this) } asParameterForEach {
        test("view returns image data url") { root ->
            val imagePath = Path(root, "image.png")
            SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)

            val output = ViewImageToolClient(workingDirectoryProvider = { root }).view(
                ViewImageToolArguments(path = "image.png"),
            )

            assertEquals(ViewImageDetail.High, output.detail)
            assertTrue(output.imageUrl.startsWith("data:image/png;base64,"))
            assertEquals(ImageMimeType.Png, output.imageUrl.decodePromptImageDataUrlBytes().detectImageInfo()?.mimeType)
        }

        test("tool returns the image as protocol-native content") { root ->
            val imagePath = Path(root, "image.png")
            SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)

            val completed = assertIs<StableImageViewToolEvent>(
                ViewImageTools.createTool(ViewImageToolClient(workingDirectoryProvider = { root })).handle(
                    PendingImageViewToolEvent(
                        callId = "view_1",
                        arguments = ViewImageToolArguments(path = "image.png"),
                    ),
                ),
            )
            val output = assertIs<StableImageViewResult.Success>(completed.result).output

            assertTrue(output.imageUrl.startsWith("data:image/png;base64,"))
            assertEquals(ViewImageDetail.High, output.detail)
        }

        test("tool returns a failure output for a missing image") { root ->
            val completed = assertIs<StableImageViewToolEvent>(
                ViewImageTools.createTool(ViewImageToolClient(workingDirectoryProvider = { root })).handle(
                    PendingImageViewToolEvent(
                        callId = "view_1",
                        arguments = ViewImageToolArguments(path = "missing.png"),
                    ),
                ),
            )
            val failure = assertIs<StableImageViewResult.Failure>(completed.result)

            assertEquals(
                "image file does not exist: missing.png",
                failure.message,
            )
        }

        test("original detail requires explicit capability") { root ->
            val imagePath = Path(root, "image.png")
            SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)

            val output = ViewImageToolClient(
                workingDirectoryProvider = { root },
                canRequestOriginalImageDetail = false,
            ).view(
                ViewImageToolArguments(path = "image.png", detail = ViewImageDetail.Original),
            )

            assertEquals(ViewImageDetail.High, output.detail)
        }

        test("missing file returns tool exception") { root ->
            assertFailsWith<ViewImageToolException> {
                ViewImageToolClient(workingDirectoryProvider = { root })
                    .view(ViewImageToolArguments(path = "missing.png"))
            }
        }
    }
}
