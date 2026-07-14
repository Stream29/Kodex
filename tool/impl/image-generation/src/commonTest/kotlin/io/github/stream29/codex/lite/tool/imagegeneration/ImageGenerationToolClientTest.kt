@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.github.stream29.codex.lite.tool.imagegeneration

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.ImageData
import io.github.stream29.codex.lite.openai.ImageResponse
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.openai.client.OpenAiClient as RealOpenAiClient
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds



private suspend fun realOpenAiClient(): OpenAiClient =
    RealOpenAiClient(
        authProvider = codexAuthProvider(),
    )

private suspend fun temporaryRoot(): Path {
    val root = Path("build/tmp/image-generation-test-${Random.nextLong().toString().replace('-', '0')}")
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
    get() = decodePng64x32()

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

    testFixture { realOpenAiClient() } asParameterForEach {
        test(
            "run generates image against real endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 300.seconds),
        ) { client ->
            val toolClient = ImageGenerationToolClient(client = client)
            val output = withContext(Dispatchers.Default) {
                toolClient.run(ImageGenToolArguments(prompt = "A tiny black square on a plain white background."))
            }

            assertTrue(output.result.isNotBlank(), "Expected generated image bytes.")
        }
    }

    testFixture {
        val testRoot = temporaryRoot()
        val openAiClient = realOpenAiClient()
        object {
            val root = testRoot
            val client = openAiClient
        }
    } closeWith {
        client.close()
        deleteRecursively(root)
    } asContextForEach {
        test(
            "run edits referenced image paths against real endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 300.seconds),
        ) {
            val imagePath = Path(root, "image.png")
            SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)
            val toolClient = ImageGenerationToolClient(
                client = client,
                root = root,
            )
            val output = withContext(Dispatchers.Default) {
                toolClient.run(
                    ImageGenToolArguments(
                        prompt = "Keep the image simple and make the rectangle slightly brighter.",
                        referencedImagePaths = listOf("image.png"),
                    ),
                )
            }

            assertTrue(output.result.isNotBlank(), "Expected edited image bytes.")
        }
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
}
