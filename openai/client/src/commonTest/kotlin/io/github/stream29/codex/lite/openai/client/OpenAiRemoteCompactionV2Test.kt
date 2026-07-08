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
    fun remoteCompactionV2BuildsResponsesRequestAndHeaders() {
        val user = ResponseItem.Message(
            role = MessageRole.User,
            content = listOf(ContentItem.InputText("Compact this.")),
        )
        val request = RemoteCompactionV2Request(
            history = listOf(user),
            checkpoint = CompactionCheckpoint(
                prefix = emptyList(),
                historyBaseIndex = 0,
                windowId = 7,
            ),
            settings = CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                clientMetadata = mapOf("existing" to "value"),
                installationId = "install",
                sessionId = "session",
                threadId = "thread",
            ),
            turnId = "turn_1",
            trigger = RemoteCompactionV2Trigger.Manual,
            reason = RemoteCompactionV2Reason.UserRequested,
            phase = RemoteCompactionV2Phase.StandaloneTurn,
        )

        val responsesRequest = request.toResponsesApiRequest()
        val headers = request.remoteCompactionV2ExtraHeaders()
        val metadata = headers.getValue("x-codex-turn-metadata")

        assertEquals(listOf(user, ResponseItem.CompactionTrigger), responsesRequest.input)
        assertEquals("value", responsesRequest.clientMetadata["existing"])
        assertEquals("install", responsesRequest.clientMetadata["x-codex-installation-id"])
        assertEquals("session", responsesRequest.clientMetadata["session_id"])
        assertEquals("thread", responsesRequest.clientMetadata["thread_id"])
        assertEquals("turn_1", responsesRequest.clientMetadata["turn_id"])
        assertEquals("7", responsesRequest.clientMetadata["x-codex-window-id"])
        assertEquals(metadata, responsesRequest.clientMetadata["x-codex-turn-metadata"])
        assertEquals("remote_compaction_v2", headers["x-codex-beta-features"])
        assertEquals("install", headers["x-codex-installation-id"])
        assertEquals("7", headers["x-codex-window-id"])

        val metadataJson = Json.parseToJsonElement(metadata).jsonObject
        assertEquals("install", metadataJson.getValue("installation_id").jsonPrimitive.content)
        assertEquals("session", metadataJson.getValue("session_id").jsonPrimitive.content)
        assertEquals("thread", metadataJson.getValue("thread_id").jsonPrimitive.content)
        assertEquals("turn_1", metadataJson.getValue("turn_id").jsonPrimitive.content)
        assertEquals("7", metadataJson.getValue("window_id").jsonPrimitive.content)
        assertEquals("compaction", metadataJson.getValue("request_kind").jsonPrimitive.content)
        val compaction = metadataJson.getValue("compaction").jsonObject
        assertEquals("manual", compaction.getValue("trigger").jsonPrimitive.content)
        assertEquals("user_requested", compaction.getValue("reason").jsonPrimitive.content)
        assertEquals("responses_compaction_v2", compaction.getValue("implementation").jsonPrimitive.content)
        assertEquals("standalone_turn", compaction.getValue("phase").jsonPrimitive.content)
        assertEquals("memento", compaction.getValue("strategy").jsonPrimitive.content)
    }

    @Test
    fun remoteCompactionV2OmitsAbsentIdentityMetadata() {
        val request = RemoteCompactionV2Request(
            history = emptyList(),
            checkpoint = CompactionCheckpoint(
                prefix = emptyList(),
                historyBaseIndex = 0,
                windowId = 0,
            ),
            settings = CodexAgentSettings(OpenAiModelId("test-model")),
            turnId = "turn_2",
            trigger = RemoteCompactionV2Trigger.Auto,
            reason = RemoteCompactionV2Reason.ContextLimit,
            phase = RemoteCompactionV2Phase.PreTurn,
        )

        val responsesRequest = request.toResponsesApiRequest()
        val headers = request.remoteCompactionV2ExtraHeaders()
        val metadataJson = Json.parseToJsonElement(headers.getValue("x-codex-turn-metadata")).jsonObject

        assertFalse(headers.containsKey("x-codex-installation-id"))
        assertFalse(responsesRequest.clientMetadata.containsKey("x-codex-installation-id"))
        assertFalse(responsesRequest.clientMetadata.containsKey("session_id"))
        assertFalse(responsesRequest.clientMetadata.containsKey("thread_id"))
        assertFalse(metadataJson.containsKey("installation_id"))
        assertFalse(metadataJson.containsKey("session_id"))
        assertFalse(metadataJson.containsKey("thread_id"))
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
