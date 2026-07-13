package io.github.stream29.codex.lite.openai.client.test

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.openai.ImageData
import io.github.stream29.codex.lite.openai.ImageGenerationRequest
import io.github.stream29.codex.lite.openai.ImageResponse
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Request
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
import io.github.stream29.codex.lite.openai.ResponseItem
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
    fun streamHandlerCanObserveExtraHeaders() = runTest {
        val completed = ResponsesStreamEvent.Completed(Response(id = "done"))
        val request = ResponsesApiRequest(model = OpenAiModelId("model"), input = emptyList())
        val observedHeaders = mutableListOf<Map<String, String>>()
        val client = mockOpenAiClient {
            createResponseWithHeaders { _, extraHeaders ->
                observedHeaders += extraHeaders
                flowOf(completed)
            }
        }

        assertEquals(
            listOf(completed),
            client.createResponse(request, mapOf("x-test" to "value")).toList(),
        )
        assertEquals(listOf(mapOf("x-test" to "value")), observedHeaders)
    }

    @Test
    fun remoteCompactionV2HandlerReturnsFlow() = runTest {
        val completed = ResponsesStreamEvent.Completed(Response(id = "done"))
        val request = RemoteCompactionV2Request(
            history = emptyList(),
            checkpoint = CompactionCheckpoint(
                prefix = emptyList(),
                historyBaseIndex = 0,
                windowNumber = 0,
                firstWindowId = "window-0",
                windowId = "window-0",
            ),
            settings = CodexAgentSettings(OpenAiModelId("model")),
            turnId = "turn_0",
            trigger = RemoteCompactionV2Trigger.Manual,
            reason = RemoteCompactionV2Reason.UserRequested,
            phase = RemoteCompactionV2Phase.StandaloneTurn,
        )
        val observedRequests = mutableListOf<RemoteCompactionV2Request>()
        val client = mockOpenAiClient {
            createRemoteCompactionV2Response {
                observedRequests += it
                RemoteCompactionV2Response(
                    compactionOutput = ResponseItem.Compaction(encryptedContent = "compact"),
                    completedResponse = completed.response,
                )
            }
        }

        assertEquals(completed.response, client.createRemoteCompactionV2Response(request).completedResponse)
        assertEquals(listOf(request), observedRequests)
    }

    @Test
    fun unconfiguredHandlersFailClearly() = runTest {
        val client = mockOpenAiClient()

        assertFailsWith<IllegalStateException> {
            client.listModels()
        }
    }
}
