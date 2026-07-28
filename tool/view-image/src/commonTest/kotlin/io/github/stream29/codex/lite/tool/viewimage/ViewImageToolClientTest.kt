package io.github.stream29.codex.lite.tool.viewimage

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputContentItem
import io.github.stream29.codex.lite.openai.ImageDetail
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.utils.images.ImageMimeType
import io.github.stream29.codex.lite.utils.images.detectImageInfo
import io.github.stream29.codex.lite.utils.images.decodePromptImageDataUrlBytes
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
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

            val result = assertIs<ResponseItem.FunctionCallOutput>(
                ViewImageTools.createTool(ViewImageToolClient(workingDirectoryProvider = { root })).handle(
                    ResponseItem.FunctionCall(
                        callId = "view_1",
                        name = ViewImageTools.Name,
                        arguments = """{"path":"image.png"}""",
                    ),
                ),
            )
            val body = assertIs<FunctionCallOutputBody.ContentItems>(result.output.body)
            val image = assertIs<FunctionCallOutputContentItem.InputImage>(body.items.single())

            assertTrue(image.imageUrl.startsWith("data:image/png;base64,"))
            assertEquals(ImageDetail.High, image.detail)
            assertEquals(true, result.output.success)
        }

        test("tool returns a failure output for a missing image") { root ->
            val result = assertIs<ResponseItem.FunctionCallOutput>(
                ViewImageTools.createTool(ViewImageToolClient(workingDirectoryProvider = { root })).handle(
                    ResponseItem.FunctionCall(
                        callId = "view_1",
                        name = ViewImageTools.Name,
                        arguments = """{"path":"missing.png"}""",
                    ),
                ),
            )

            assertEquals(false, result.output.success)
            assertEquals(
                FunctionCallOutputBody.Text("image file does not exist: missing.png"),
                result.output.body,
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
