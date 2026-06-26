@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.github.stream29.codex.lite.tool.viewimage

import io.github.stream29.codex.lite.utils.images.ImageMimeType
import io.github.stream29.codex.lite.utils.images.detectImageInfo
import io.github.stream29.codex.lite.utils.images.decodePromptImageDataUrlBytes
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ViewImageToolClientTest {
    @Test
    fun viewReturnsImageDataUrl() = runTest {
        val root = temporaryRoot()
        val imagePath = Path(root, "image.png")
        SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)

        val output = ViewImageToolClient(root = root).view(
            ViewImageToolArguments(path = "image.png"),
        )

        assertEquals(ViewImageDetail.High, output.detail)
        assertTrue(output.imageUrl.startsWith("data:image/png;base64,"))
        assertEquals(ImageMimeType.Png, output.imageUrl.decodePromptImageDataUrlBytes().detectImageInfo()?.mimeType)
        SystemCoroutineFileSystem.delete(imagePath, mustExist = true)
        SystemCoroutineFileSystem.delete(root, mustExist = true)
    }

    @Test
    fun originalDetailRequiresExplicitCapability() = runTest {
        val root = temporaryRoot()
        val imagePath = Path(root, "image.png")
        SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)

        val output = ViewImageToolClient(root = root, canRequestOriginalImageDetail = false).view(
            ViewImageToolArguments(path = "image.png", detail = ViewImageDetail.Original),
        )

        assertEquals(ViewImageDetail.High, output.detail)
        SystemCoroutineFileSystem.delete(imagePath, mustExist = true)
        SystemCoroutineFileSystem.delete(root, mustExist = true)
    }

    @Test
    fun missingFileReturnsToolException() = runTest {
        val root = temporaryRoot()

        assertFailsWith<ViewImageToolException> {
            ViewImageToolClient(root = root).view(ViewImageToolArguments(path = "missing.png"))
        }

        SystemCoroutineFileSystem.delete(root, mustExist = true)
    }

    private suspend fun temporaryRoot(): Path {
        val root = Path("build/tmp/view-image-test-${Random.nextLong().toString().replace('-', '0')}")
        SystemCoroutineFileSystem.createDirectories(root)
        return root
    }

    private val png64x32: ByteArray
        get() = Base64.Default.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAgklEQVR4Xu3QoRHDABADQeNgY+MUkf6rcC82XxKskcASgZ/5Oz7n9TQ7HNosgEObBXBoswAObf4GuH/faP6jBXCQB9P4jxbAQR5M4z9aAAd5MI3/aAEc5ME0/qMFcJAH0/iPFsBBHkzjP1oAB3kwjf9oARzaLIBDmwVwaLMADm3qA7y8LuS12WzThwAAAABJRU5ErkJggg==",
        )
}
