package io.github.stream29.codex.lite.openai.client.test

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.ImageData
import io.github.stream29.codex.lite.openai.ImageGenerationRequest
import io.github.stream29.codex.lite.openai.ImageResponse
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
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

        assertEquals(listOf(completed), client.createResponse(request).toList())
    }

    test("remote compaction v2 handler receives wire request and transport values") {
        val completed = ResponsesStreamEvent.Completed(Response(id = "done"))
        val request = ResponsesApiRequest(model = OpenAiModelId("model"), input = emptyList())
        var observedRequest: ResponsesApiRequest? = null
        var observedInstallationId: String? = null
        var observedTurnMetadata: String? = null
        var observedWindowId: String? = null
        val client = mockOpenAiClient {
            createRemoteCompactionV2Response { rawRequest, installationId, turnMetadata, windowId ->
                observedRequest = rawRequest
                observedInstallationId = installationId
                observedTurnMetadata = turnMetadata
                observedWindowId = windowId
                RemoteCompactionV2Response(
                    compactionOutput = ResponseItem.Compaction(encryptedContent = "compact"),
                    completedResponse = completed.response,
                )
            }
        }

        assertEquals(
            completed.response,
            client.createRemoteCompactionV2Response(
                request = request,
                installationId = "install",
                turnMetadata = "metadata",
                windowId = "thread:0",
            ).completedResponse,
        )
        assertEquals(request, observedRequest)
        assertEquals("install", observedInstallationId)
        assertEquals("metadata", observedTurnMetadata)
        assertEquals("thread:0", observedWindowId)
    }

    test("unconfigured handlers fail clearly") {
        val client = mockOpenAiClient()

        assertFailsWith<IllegalStateException> {
            client.listModels()
        }
    }
}
