package io.github.stream29.codex.lite.tool.imagegeneration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OpenAiImageGenerationClientTest {
    @Test
    fun generateCallsRealImageGenerationEndpointWithCodexAuth() = runTest(timeout = 300.seconds) {
        val client = OpenAiImageGenerationClient(
            authProvider = codexAuthProvider(),
        )

        val response = withContext(Dispatchers.Default) {
            client.generate(
                ImageGenerationRequest(
                    prompt = "A tiny black square on a plain white background.",
                    model = ImageGenDefaultModel,
                    background = ImageBackground.Auto,
                    quality = ImageQuality.Auto,
                    size = "auto",
                ),
            )
        }

        assertTrue(response.created > 0L, "Expected server-created timestamp.")
        assertEquals(1, response.data.size)
        assertTrue(response.data.single().b64Json.isNotBlank(), "Expected generated image bytes.")
    }

    @Test
    fun editCallsRealImageEditEndpointWithCodexAuth() = runTest(timeout = 300.seconds) {
        val client = OpenAiImageGenerationClient(
            authProvider = codexAuthProvider(),
        )

        val response = withContext(Dispatchers.Default) {
            client.edit(
                ImageEditRequest(
                    images = listOf(ImageUrl(png64x32DataUrl)),
                    prompt = "Keep the image simple and make the rectangle slightly brighter.",
                    model = ImageGenDefaultModel,
                    background = ImageBackground.Auto,
                    quality = ImageQuality.Auto,
                    size = "auto",
                ),
            )
        }

        assertTrue(response.created > 0L, "Expected server-created timestamp.")
        assertEquals(1, response.data.size)
        assertTrue(response.data.single().b64Json.isNotBlank(), "Expected edited image bytes.")
    }
}
