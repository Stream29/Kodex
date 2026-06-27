@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.github.stream29.codex.lite.tool.imagegeneration

import io.github.stream29.codex.lite.openai.ImageData
import io.github.stream29.codex.lite.openai.ImageResponse
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.openai.client.OpenAiClient as RealOpenAiClient
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ImageGenerationToolClientTest {
    @Test
    fun runGeneratesImageWithMockClient() = runTest {
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

    @Test
    fun runGeneratesImageAgainstRealEndpoint() = runTest(timeout = 300.seconds) {
        val client = realOpenAiClient()
        val toolClient = ImageGenerationToolClient(
            client = client,
        )

        val output = try {
            withContext(Dispatchers.Default) {
                toolClient.run(ImageGenToolArguments(prompt = "A tiny black square on a plain white background."))
            }
        } finally {
            client.close()
        }

        assertTrue(output.result.isNotBlank(), "Expected generated image bytes.")
    }

    @Test
    fun runEditsReferencedImagePathsAgainstRealEndpoint() = runTest(timeout = 300.seconds) {
        val root = temporaryRoot()
        val imagePath = Path(root, "image.png")
        SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)
        val client = realOpenAiClient()
        val toolClient = ImageGenerationToolClient(
            client = client,
            root = root,
        )

        try {
            val output = withContext(Dispatchers.Default) {
                toolClient.run(
                    ImageGenToolArguments(
                        prompt = "Keep the image simple and make the rectangle slightly brighter.",
                        referencedImagePaths = listOf("image.png"),
                    ),
                )
            }

            assertTrue(output.result.isNotBlank(), "Expected edited image bytes.")
        } finally {
            client.close()
            if (SystemCoroutineFileSystem.exists(imagePath)) {
                SystemCoroutineFileSystem.delete(imagePath, mustExist = true)
            }
            if (SystemCoroutineFileSystem.exists(root)) {
                SystemCoroutineFileSystem.delete(root, mustExist = true)
            }
        }
    }

    @Test
    fun tooManyReferencedImagesFailBeforeCallingApi() = runTest {
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

    @Test
    fun conversationImageSelectionRequiresAgentLoopHistory() = runTest {
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

    private suspend fun realOpenAiClient(): OpenAiClient =
        RealOpenAiClient(
            authProvider = codexAuthProvider(),
        )

    private suspend fun temporaryRoot(): Path {
        val root = Path("build/tmp/image-generation-test-${Random.nextLong().toString().replace('-', '0')}")
        SystemCoroutineFileSystem.createDirectories(root)
        return root
    }

    private val png64x32: ByteArray
        get() = decodePng64x32()
}
