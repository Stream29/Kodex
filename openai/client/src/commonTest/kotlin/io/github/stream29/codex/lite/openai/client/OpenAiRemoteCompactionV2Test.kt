package io.github.stream29.codex.lite.openai.client

import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Request
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.codexRequestWindowId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OpenAiRemoteCompactionV2Test {
    @Test
    fun remoteCompactionV2BuildsResponsesRequestAndTransportValues() {
        val user = ResponseItem.Message(
            role = MessageRole.User,
            content = listOf(ContentItem.InputText("Compact this.")),
        )
        val request = RemoteCompactionV2Request(
            history = listOf(user),
            checkpoint = CompactionCheckpoint(
                prefix = emptyList(),
                historyBaseIndex = 0,
                windowNumber = 7,
                firstWindowId = "window-0",
                previousWindowId = "window-6",
                windowId = "window-7",
            ),
            settings = CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                clientMetadata = mapOf("existing" to "value"),
                installationId = "install",
                sessionId = "session",
                turnId = "turn_1",
            ),
            threadId = "thread",
            trigger = RemoteCompactionV2Trigger.Manual,
            reason = RemoteCompactionV2Reason.UserRequested,
            phase = RemoteCompactionV2Phase.StandaloneTurn,
        )

        val windowId = request.checkpoint.codexRequestWindowId(request.threadId)
        val metadata = request.toCodexTurnMetadata(windowId)
        val responsesRequest = request.toResponsesApiRequest(
            turnMetadata = metadata,
            windowId = windowId,
        )

        assertEquals(listOf(user, ResponseItem.CompactionTrigger), responsesRequest.input)
        assertEquals("value", responsesRequest.clientMetadata["existing"])
        assertEquals("install", responsesRequest.clientMetadata["x-codex-installation-id"])
        assertEquals("session", responsesRequest.clientMetadata["session_id"])
        assertEquals("thread", responsesRequest.clientMetadata["thread_id"])
        assertEquals("turn_1", responsesRequest.clientMetadata["turn_id"])
        assertEquals(windowId, responsesRequest.clientMetadata["x-codex-window-id"])
        assertEquals(metadata, responsesRequest.clientMetadata["x-codex-turn-metadata"])
        assertEquals("thread:7", windowId)

        val metadataJson = Json.parseToJsonElement(metadata).jsonObject
        assertEquals("install", metadataJson.getValue("installation_id").jsonPrimitive.content)
        assertEquals("session", metadataJson.getValue("session_id").jsonPrimitive.content)
        assertEquals("thread", metadataJson.getValue("thread_id").jsonPrimitive.content)
        assertEquals("turn_1", metadataJson.getValue("turn_id").jsonPrimitive.content)
        assertEquals("thread:7", metadataJson.getValue("window_id").jsonPrimitive.content)
        assertEquals("compaction", metadataJson.getValue("request_kind").jsonPrimitive.content)
        val compaction = metadataJson.getValue("compaction").jsonObject
        assertEquals("manual", compaction.getValue("trigger").jsonPrimitive.content)
        assertEquals("user_requested", compaction.getValue("reason").jsonPrimitive.content)
        assertEquals("responses_compaction_v2", compaction.getValue("implementation").jsonPrimitive.content)
        assertEquals("standalone_turn", compaction.getValue("phase").jsonPrimitive.content)
        assertEquals("memento", compaction.getValue("strategy").jsonPrimitive.content)
    }

    @Test
    fun remoteCompactionV2OmitsOptionalIdentityMetadata() {
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
                model = OpenAiModelId("test-model"),
                turnId = "turn_2",
            ),
            threadId = "thread",
            trigger = RemoteCompactionV2Trigger.Auto,
            reason = RemoteCompactionV2Reason.ContextLimit,
            phase = RemoteCompactionV2Phase.PreTurn,
        )

        val windowId = request.checkpoint.codexRequestWindowId(request.threadId)
        val metadataJson = Json.parseToJsonElement(request.toCodexTurnMetadata(windowId)).jsonObject
        val responsesRequest = request.toResponsesApiRequest(
            turnMetadata = request.toCodexTurnMetadata(windowId),
            windowId = windowId,
        )

        assertFalse(responsesRequest.clientMetadata.containsKey("x-codex-installation-id"))
        assertFalse(responsesRequest.clientMetadata.containsKey("session_id"))
        assertEquals("thread", responsesRequest.clientMetadata["thread_id"])
        assertFalse(metadataJson.containsKey("installation_id"))
        assertFalse(metadataJson.containsKey("session_id"))
        assertEquals("thread", metadataJson.getValue("thread_id").jsonPrimitive.content)
    }

    @Test
    fun remoteCompactionV2SucceedsWhenCompactionOutputArrivesWithoutCompleted() = runTest {
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")

        val response = flowOf<ResponsesStreamEvent>(
            ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = compaction),
        ).collectRemoteCompactionV2Response()

        assertEquals(compaction, response.compactionOutput)
        assertNull(response.completedResponse)
    }

    @Test
    fun remoteCompactionV2RetriesWhenStreamClosesBeforeCompactionOutput() = runTest {
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")
        var attempts = 0

        val response = retryOpenAiStreamingTransport(
            OpenAiClientRetryConfig(
                maxRetries = 1,
                baseDelayMillis = 1,
                maxDelayMillis = 1,
                randomizationMillis = 0,
            ),
        ) {
            attempts += 1
            if (attempts == 1) {
                flowOf<ResponsesStreamEvent>().collectRemoteCompactionV2Response()
            } else {
                flowOf<ResponsesStreamEvent>(
                    ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = compaction),
                ).collectRemoteCompactionV2Response()
            }
        }

        assertEquals(2, attempts)
        assertEquals(compaction, response.compactionOutput)
    }
}
