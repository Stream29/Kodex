package io.github.stream29.kodex.tool.readmedia

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingReadMediaToolEvent
import io.github.stream29.kodex.utils.images.ImageMimeType
import io.github.stream29.kodex.utils.images.detectImageInfo
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private suspend fun temporaryRoot(): Path {
    val root = Path(SystemTemporaryDirectory, "read-media-test-${Random.nextLong().toString().replace('-', '0')}")
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

val readMediaToolClientTest by testSuite {
    testFixture { temporaryRoot() } closeWith { deleteRecursively(this) } asParameterForEach {
        test("read resolves relative path and returns image data url") { root ->
            val imagePath = Path(root, "image.png")
            SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)

            val output = ReadMediaToolClient(workingDirectoryProvider = { root }).read(
                ReadMediaToolArguments(path = "image.png"),
            )

            assertEquals(MediaDetail.High, output.detail)
            assertTrue(output.imageUrl.startsWith("data:image/png;base64,"))
            val decoded = Base64.decode(output.imageUrl.substringAfter(";base64,"))
            assertEquals(ImageMimeType.Png, decoded.detectImageInfo()?.mimeType)
        }

        test("tool returns protocol-native image content and supports original detail") { root ->
            val imagePath = Path(root, "image.png")
            SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)

            val completed = assertIs<StableReadMediaToolEvent>(
                ReadMediaTools.createTool(ReadMediaToolClient(workingDirectoryProvider = { root })).handle(
                    PendingReadMediaToolEvent(
                        callId = "media_1",
                        arguments = ReadMediaToolArguments(path = "image.png", detail = MediaDetail.Original),
                    ),
                ),
            )
            val output = assertIs<StableReadMediaResult.Success>(completed.result).output

            assertTrue(output.imageUrl.startsWith("data:image/png;base64,"))
            assertEquals(MediaDetail.Original, output.detail)
        }

        test("tool returns failure output for missing or blank media path") { root ->
            val tool = ReadMediaTools.createTool(ReadMediaToolClient(workingDirectoryProvider = { root }))
            val missing = assertIs<StableReadMediaToolEvent>(
                tool.handle(
                    PendingReadMediaToolEvent(
                        callId = "media_missing",
                        arguments = ReadMediaToolArguments(path = "missing.png"),
                    ),
                ),
            )
            assertEquals("media file does not exist: missing.png", assertIs<StableReadMediaResult.Failure>(missing.result).message)

            val blank = assertIs<StableReadMediaToolEvent>(
                tool.handle(
                    PendingReadMediaToolEvent(
                        callId = "media_blank",
                        arguments = ReadMediaToolArguments(path = "   "),
                    ),
                ),
            )
            assertEquals("`path` must not be blank", assertIs<StableReadMediaResult.Failure>(blank.result).message)
        }

        test("original detail falls back to high when capability is disabled") { root ->
            val imagePath = Path(root, "image.png")
            SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)

            val output = ReadMediaToolClient(
                workingDirectoryProvider = { root },
                canRequestOriginalImageDetail = false,
            ).read(
                ReadMediaToolArguments(path = "image.png", detail = MediaDetail.Original),
            )

            assertEquals(MediaDetail.High, output.detail)
        }

        test("missing file throws ReadMediaToolException") { root ->
            assertFailsWith<ReadMediaToolException> {
                ReadMediaToolClient(workingDirectoryProvider = { root })
                    .read(ReadMediaToolArguments(path = "missing.png"))
            }
        }
    }
}
