package io.github.stream29.kodex.tool.imagegeneration

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ImageData
import io.github.stream29.kodex.openai.ImageResponse
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val imageGenerationToolClientTest by testSuite {
    test("run generates image with mock client") {
        val toolClient = ImageGenerationToolClient(
            client = mockOpenAiClient {
                generateImage { request ->
                    assertEquals("draw", request.prompt)
                    OpenAiResult.Success(
                        ImageResponse(
                            created = 1,
                            data = listOf(ImageData("generated-image")),
                        ),
                    )
                }
            },
        )

        val output = toolClient.run(ImageGenToolArguments(prompt = "draw"))

        assertEquals(GeneratedImageOutput(result = "generated-image"), output)
    }

    test("too many referenced images fail before calling api") {
        val toolClient = ImageGenerationToolClient(client = mockOpenAiClient())

        assertFailsWith<ImageGenerationToolException> {
            toolClient.run(
                ImageGenToolArguments(
                    prompt = "edit",
                    referencedImagePaths = List(ImageGenMaxEditImages + 1) { "/tmp/$it.png" },
                ),
            )
        }
    }

    test("conversation image selection requires agent loop history") {
        val toolClient = ImageGenerationToolClient(client = mockOpenAiClient())

        assertFailsWith<ImageGenerationToolException> {
            toolClient.run(
                ImageGenToolArguments(
                    prompt = "edit",
                    numLastImagesToInclude = 1,
                ),
            )
        }
    }

    test("generateToFile writes decoded PNG under outputDirectory") {
        val root = Path(SystemTemporaryDirectory, "kodex-image-gen-${Random.nextLong()}")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        try {
            val toolClient = ImageGenerationToolClient(
                client = mockOpenAiClient {
                    generateImage {
                        OpenAiResult.Success(
                            ImageResponse(
                                created = 1,
                                data = listOf(ImageData(Base64.encode(bytes))),
                            ),
                        )
                    }
                },
            )
            val outDir = imageArtifactDirectory(root, "42")
            val artifact = toolClient.generateToFile(ImageGenToolArguments(prompt = "draw"), outDir)
            assertEquals("image/png", artifact.mimeType)
            assertTrue(artifact.path.endsWith(".png"))
            assertContentEquals(bytes, SystemCoroutineFileSystem.readBytes(Path(artifact.path)))
        } finally {
            if (SystemCoroutineFileSystem.exists(root)) {
                SystemCoroutineFileSystem.list(outDirOrRoot(root)).forEach { _ -> }
                deleteDir(root)
            }
        }
    }
}

private fun outDirOrRoot(root: Path): Path = root

private suspend fun deleteDir(path: Path) {
    val meta = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (meta.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { deleteDir(it) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
