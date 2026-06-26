package io.github.stream29.codex.lite.openai.client

import io.github.stream29.codex.lite.openai.ImageBackground
import io.github.stream29.codex.lite.openai.ImageEditRequest
import io.github.stream29.codex.lite.openai.ImageGenerationRequest
import io.github.stream29.codex.lite.openai.ImageQuality
import io.github.stream29.codex.lite.openai.ImageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OpenAiClientImageGenerationTest {
    @Test
    fun generateCallsRealImageGenerationEndpointWithCodexAuth() = runTest(timeout = 300.seconds) {
        val client = OpenAiClient(
            authProvider = codexAuthProvider(),
        )

        val response = try {
            withContext(Dispatchers.Default) {
                client.generateImage(
                    ImageGenerationRequest(
                        prompt = "A tiny black square on a plain white background.",
                        model = ImageGenerationTestModel,
                        background = ImageBackground.Auto,
                        quality = ImageQuality.Auto,
                        size = "auto",
                    ),
                )
            }
        } finally {
            client.close()
        }.successOrFail()

        assertTrue(response.created > 0L, "Expected server-created timestamp.")
        assertEquals(1, response.data.size)
        assertTrue(response.data.single().b64Json.isNotBlank(), "Expected generated image bytes.")
    }

    @Test
    fun editCallsRealImageEditEndpointWithCodexAuth() = runTest(timeout = 300.seconds) {
        val client = OpenAiClient(
            authProvider = codexAuthProvider(),
        )

        val response = try {
            withContext(Dispatchers.Default) {
                client.editImage(
                    ImageEditRequest(
                        images = listOf(ImageUrl(png64x32DataUrl)),
                        prompt = "Keep the image simple and make the rectangle slightly brighter.",
                        model = ImageGenerationTestModel,
                        background = ImageBackground.Auto,
                        quality = ImageQuality.Auto,
                        size = "auto",
                    ),
                )
            }
        } finally {
            client.close()
        }.successOrFail()

        assertTrue(response.created > 0L, "Expected server-created timestamp.")
        assertEquals(1, response.data.size)
        assertTrue(response.data.single().b64Json.isNotBlank(), "Expected edited image bytes.")
    }
}
