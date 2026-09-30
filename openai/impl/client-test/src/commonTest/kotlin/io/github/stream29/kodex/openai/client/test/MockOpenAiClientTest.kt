package io.github.stream29.kodex.openai.client.test

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.openai.ImageData
import io.github.stream29.kodex.openai.ImageGenerationRequest
import io.github.stream29.kodex.openai.ImageResponse
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.CodexResponsesClientMetadata
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith



val mockOpenAiClientTest by testSuite {
    test("configured handlers return dsl values") {
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

    test("stream handler returns flow") {
        val completed = ResponsesStreamEvent.Completed(Response(id = "done"))
        val request = ResponsesApiRequest(model = OpenAiModelId("model"), input = emptyList())
        val client = mockOpenAiClient {
            createResponse { flowOf(completed) }
        }

        assertEquals(
            listOf(completed),
            client.createResponse(
                model = request.model,
                input = request.input,
            ).toList(),
        )
    }

    test("remote compaction v2 handler receives request-owned transport values") {
        val completed = ResponsesStreamEvent.Completed(Response(id = "done"))
        val request = ResponsesApiRequest(
            model = OpenAiModelId("model"),
            input = emptyList(),
            clientMetadata = CodexResponsesClientMetadata(
                installationId = "install",
                threadId = "thread",
                windowId = "thread:0",
                turnMetadata = "metadata",
            ),
        )
        var observedRequest: ResponsesApiRequest? = null
        val client = mockOpenAiClient {
            createRemoteCompactionV2Response { rawRequest ->
                observedRequest = rawRequest
                RemoteCompactionV2Response(
                    compactionOutput = ResponseItem.Compaction(encryptedContent = "compact"),
                    completedResponse = completed.response,
                )
            }
        }

        assertEquals(
            completed.response,
            client.createRemoteCompactionV2Response(request).completedResponse,
        )
        assertEquals(request, observedRequest)
        assertEquals("install", observedRequest?.clientMetadata?.installationId)
        assertEquals("metadata", observedRequest?.clientMetadata?.turnMetadata)
        assertEquals("thread:0", observedRequest?.clientMetadata?.windowId)
    }

    test("unconfigured handlers fail clearly") {
        val client = mockOpenAiClient()

        assertFailsWith<IllegalStateException> {
            client.listModels()
        }
    }
}
