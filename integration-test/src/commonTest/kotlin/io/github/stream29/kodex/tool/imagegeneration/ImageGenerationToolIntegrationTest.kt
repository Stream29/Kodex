package io.github.stream29.kodex.tool.imagegeneration

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.client.OpenAiClient as RealOpenAiClient
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.rpc.models.ImageGenToolArguments
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private suspend fun realOpenAiClient(): OpenAiClient =
    RealOpenAiClient(
        authStore = kodexAuthStore(),
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

val imageGenerationToolIntegrationTest by testSuite {
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
            "generateToFile generates image against real endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 300.seconds),
        ) {
            val toolClient = ImageGenerationToolClient(client = client)
            val artifact = withContext(Dispatchers.Default) {
                toolClient.generateToFile(
                    ImageGenToolArguments(prompt = "A tiny black square on a plain white background."),
                    Path(root, "generated"),
                )
            }

            assertTrue(
                SystemCoroutineFileSystem.readBytes(Path(artifact.path)).isNotEmpty(),
                "Expected generated image bytes.",
            )
        }

        test(
            "generateToFile edits referenced image paths against real endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 300.seconds),
        ) {
            val imagePath = Path(root, "image.png")
            SystemCoroutineFileSystem.writeBytes(imagePath, png64x32)
            val toolClient = ImageGenerationToolClient(
                client = client,
                workingDirectoryProvider = { root },
            )
            val artifact = withContext(Dispatchers.Default) {
                toolClient.generateToFile(
                    ImageGenToolArguments(
                        prompt = "Keep the image simple and make the rectangle slightly brighter.",
                        referencedImagePaths = listOf("image.png"),
                    ),
                    Path(root, "edited"),
                )
            }

            assertTrue(
                SystemCoroutineFileSystem.readBytes(Path(artifact.path)).isNotEmpty(),
                "Expected edited image bytes.",
            )
        }
    }
}
