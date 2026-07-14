package io.github.stream29.codex.lite.openai.client.test

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CodexResponsesRequest
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
import io.github.stream29.codex.lite.openai.client.contract.createResponse
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue



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

    test("codex request extension supplies required transport values") {
        val completed = ResponsesStreamEvent.Completed(Response(id = "done"))
        val request = CodexResponsesRequest(
            input = emptyList(),
            checkpoint = CompactionCheckpoint(
                prefix = emptyList(),
                historyBaseIndex = 0,
                windowNumber = 3,
                firstWindowId = "window-0",
                windowId = "window-3",
            ),
            settings = CodexAgentSettings(
                model = OpenAiModelId("model"),
                installationId = "install",
                turnId = "turn_3",
            ),
            threadId = "thread_3",
        )
        var observedRequest: ResponsesApiRequest? = null
        var observedInstallationId: String? = null
        var observedTurnMetadata: String? = null
        var observedWindowId: String? = null
        val client = mockOpenAiClient {
            createResponse { rawRequest, installationId, turnMetadata, windowId ->
                observedRequest = rawRequest
                observedInstallationId = installationId
                observedTurnMetadata = turnMetadata
                observedWindowId = windowId
                flowOf(completed)
            }
        }

        assertEquals(listOf(completed), client.createResponse(request).toList())
        assertEquals("install", observedInstallationId)
        assertEquals("thread_3:3", observedWindowId)
        assertEquals("turn_3", observedRequest?.clientMetadata?.get("turn_id"))
        assertTrue(observedTurnMetadata.orEmpty().contains("\"request_kind\":\"turn\""))
    }

    test("remote compaction v2 handler returns flow") {
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
            settings = CodexAgentSettings(
                model = OpenAiModelId("model"),
                turnId = "turn_0",
            ),
            threadId = "thread_0",
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

    test("unconfigured handlers fail clearly") {
        val client = mockOpenAiClient()

        assertFailsWith<IllegalStateException> {
            client.listModels()
        }
    }
}
