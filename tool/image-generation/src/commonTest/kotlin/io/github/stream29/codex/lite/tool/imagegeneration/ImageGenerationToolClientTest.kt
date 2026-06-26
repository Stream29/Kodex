@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.github.stream29.codex.lite.tool.imagegeneration

import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ImageGenerationToolClientTest {
    @Test
    fun runGeneratesImageAgainstRealEndpoint() = runTest(timeout = 300.seconds) {
        val toolClient = ImageGenerationToolClient(
            client = realOpenAiClient(),
        )

        val output = withContext(Dispatchers.Default) {
            toolClient.run(ImageGenToolArguments(prompt = "A tiny black square on a plain white background."))
        }

        assertTrue(output.result.isNotBlank(), "Expected generated image bytes.")
    }

    @Test
    fun runEditsReferencedImagePathsAgainstRealEndpoint() = runTest(timeout = 300.seconds) {
        val root = temporaryRoot()
        val imagePath = Path(root, "image.png")
        SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)
        val toolClient = ImageGenerationToolClient(
            client = realOpenAiClient(),
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
        val toolClient = ImageGenerationToolClient(client = realOpenAiClient())

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
        val toolClient = ImageGenerationToolClient(client = realOpenAiClient())

        assertFailsWith<ImageGenerationToolException> {
            toolClient.run(
                ImageGenToolArguments(
                    prompt = "edit",
                    numLastImagesToInclude = 1,
                ),
            )
        }
    }

    private fun realOpenAiClient(): OpenAiImageGenerationClient =
        OpenAiImageGenerationClient(
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
