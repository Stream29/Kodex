package io.github.stream29.codex.lite.openai.client.test

import io.github.stream29.codex.lite.openai.ImageData
import io.github.stream29.codex.lite.openai.ImageGenerationRequest
import io.github.stream29.codex.lite.openai.ImageResponse
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MockOpenAiClientTest {
    @Test
    fun configuredHandlersReturnDslValues() = runTest {
        val client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse()) }
            generateImage { request ->
                OpenAiResult.Success(
                    ImageResponse(
                        created = 1,
                        data = listOf(ImageData("image:${request.prompt}")),
                    ),
                )
            }
        }

        assertEquals(OpenAiResult.Success(ModelsResponse()), client.listModels())
        assertEquals(
            OpenAiResult.Success(ImageResponse(created = 1, data = listOf(ImageData("image:draw")))),
            client.generateImage(ImageGenerationRequest(prompt = "draw", model = OpenAiModelId("gpt-image-2"))),
        )
    }

    @Test
    fun streamHandlerReturnsFlow() = runTest {
        val completed = ResponsesStreamEvent.Completed(Response(id = "done"))
        val request = ResponsesApiRequest(model = OpenAiModelId("model"), input = emptyList())
        val client = mockOpenAiClient {
            createResponse { flowOf(completed) }
        }

        assertEquals(listOf(completed), client.createResponse(request).toList())
    }

    @Test
    fun unconfiguredHandlersFailClearly() = runTest {
        val client = mockOpenAiClient()

        assertFailsWith<IllegalStateException> {
            client.listModels()
        }
    }
}
