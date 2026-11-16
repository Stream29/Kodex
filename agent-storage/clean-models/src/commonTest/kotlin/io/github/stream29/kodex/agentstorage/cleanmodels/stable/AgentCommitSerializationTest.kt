@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwaitEventToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventOutput
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.TokenUsage
import io.github.stream29.kodex.openai.TokenUsageInputDetails
import io.github.stream29.kodex.openai.TokenUsageOutputDetails
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Instant

val agentCommitSerializationTest by testSuite {
    test("agent settings serialize cwd as a string and use compatibility fallback") {
        val settings = KodexAgentSettings(
            model = OpenAiModelId("test-model"),
            cwd = Path("/workspace/project"),
        )
        val encoded = OpenAiJsonCodec
            .encodeToJsonElement(KodexAgentSettings.serializer(), settings)
            .jsonObject
        assertEquals(JsonPrimitive("/workspace/project"), encoded["cwd"])
        assertEquals(
            settings,
            OpenAiJsonCodec.decodeFromJsonElement(KodexAgentSettings.serializer(), encoded),
        )

        val decoded = OpenAiJsonCodec.decodeFromJsonElement(
            KodexAgentSettings.serializer(),
            JsonObject(mapOf("model" to JsonPrimitive("test-model"))),
        )
        assertEquals(Path("."), decoded.cwd)
        assertEquals(RequestUserInputMode.AskUser, decoded.requestUserInputMode)
    }

    test("legacy settings without reasoning or effort retain medium semantics and service tiers") {
        for (source in listOf(
            """{"model":"test-model"}""",
            """{"model":"test-model","reasoning":{}}""",
            """{"model":"test-model","reasoning":{"effort":"low"}}""",
        )) {
            val settings = OpenAiJsonCodec.decodeFromString(KodexAgentSettings.serializer(), source)
            val encoded = OpenAiJsonCodec.encodeToJsonElement(KodexAgentSettings.serializer(), settings)
            assertEquals(
                JsonPrimitive(if ("low" in source) "low" else "medium"),
                encoded.jsonObject["reasoning"]!!.jsonObject["effort"],
            )
            assertEquals(settings, OpenAiJsonCodec.decodeFromJsonElement(KodexAgentSettings.serializer(), encoded))
        }

        val codecs = listOf(OpenAiJsonCodec, Json(OpenAiJsonCodec) { encodeDefaults = false })
        for (codec in codecs) {
            for ((tier, wireValue) in listOf(
                ServiceTier.Default to "default",
                ServiceTier.Fast to "priority",
                ServiceTier.Flex to "flex",
                ServiceTier.Ultrafast to "ultrafast",
            )) {
                val settings = KodexAgentSettings(OpenAiModelId("original"), serviceTier = tier)
                val encoded = codec.encodeToJsonElement(KodexAgentSettings.serializer(), settings)
                if (tier != ServiceTier.Default) {
                    assertEquals(JsonPrimitive(wireValue), encoded.jsonObject["serviceTier"])
                }
                assertEquals(settings, codec.decodeFromJsonElement(KodexAgentSettings.serializer(), encoded))
            }
            assertEquals(
                ServiceTier.Default,
                codec.decodeFromString<KodexAgentSettings>("""{"model":"legacy"}""").serviceTier,
            )
        }
    }

    test("llm request meta snapshots round-trip across all four subtypes and preserve type discriminator") {
        val response = LlmRequestMeta.Response(
            totalTokens = 9_007_199_254_740_993L,
            usage = TokenUsage(
                inputTokens = 100,
                outputTokens = 20,
                totalTokens = 120,
                inputTokensDetails = TokenUsageInputDetails(cachedTokens = 0, cacheWriteTokens = 12),
                outputTokensDetails = TokenUsageOutputDetails(reasoningTokens = 5),
            ),
            diagnostics = TokenCountDiagnostics(
                turnId = "turn",
                windowId = "window",
                requestedModel = "requested",
                requestedReasoningEffort = "high",
                requestedServiceTier = "priority",
                responseId = "response",
                requestId = "request",
                model = "actual",
                serviceTier = "default",
                turnStateSent = false,
                turnStateReceived = true,
                turnStateAdopted = true,
            ),
        )
        val snapshots = listOf<LlmRequestMeta>(
            LlmRequestMeta.Legacy(0),
            LlmRequestMeta.Initialization(0),
            LlmRequestMeta.Compaction(10),
            response,
        )
        for (snapshot in snapshots) {
            val payload = snapshot.encodePayloadJson()
            assertEquals(snapshot, snapshot.commitType.decodePayloadJson(payload))
            assertEquals(snapshot, OpenAiJsonCodec.decodeFromString(LlmRequestMeta.serializer(), payload))
            assertEquals(snapshot, OpenAiJsonCodec.decodeFromString(AgentCommit.serializer(), payload))
        }

        val missing = Json.decodeFromString<LlmRequestMeta>("""{"type":"llm_request_meta_response","total_tokens":0}""")
        val reported = (missing as LlmRequestMeta.Response).copy(
            usage = TokenUsage(0, 0, 0, TokenUsageInputDetails(0), TokenUsageOutputDetails(0)),
            diagnostics = TokenCountDiagnostics(turnStateSent = false),
        )
        assertEquals(LlmRequestMeta.Response(0), missing)
        assertNotEquals<LlmRequestMeta>(missing, reported)
    }

    test("all agent commit families round-trip through native AgentCommit polymorphism and payload helpers") {
        val sampleArgs = RequestUserInputArgs(
            questions = listOf(
                RequestUserInputQuestion(id = "q1", header = "H", question = "Continue?"),
            ),
        )
        val sampleResponse = RequestUserInputResponse(
            answers = mapOf("q1" to RequestUserInputAnswer(listOf("yes"))),
        )
        val commits: List<AgentCommit> = listOf(
            StableUserMessage(listOf(ContentItem.InputText("hi"))),
            StableAskUserEvent(askId = 1, arguments = sampleArgs, response = sampleResponse),
            StableScheduleEvent(scheduleId = 9L, description = "nightly check"),
            StableTerminalExitedEvent(sessionId = 4, name = "server", command = "./run.sh", exitCode = 0),
            StableAssistantMessage(listOf(ContentItem.OutputText("plan")), phase = MessagePhase.Commentary),
            StableAssistantMessage(listOf(ContentItem.OutputText("done")), phase = MessagePhase.FinalAnswer),
            StableAssistantMessage(listOf(ContentItem.OutputText("legacy")), phase = null),
            StableContextCompaction(encryptedContent = "compacted"),
            StableReasoning(ResponseItem.Reasoning(ResponseItemId("r1"), emptyList())),
            StableAwaitEventToolEvent(
                callId = "call_await",
                arguments = AwaitEventArguments(terminals = listOf(4)),
                result = StableAwaitEventResult.Output(AwaitEventOutput(Instant.parse("2026-11-15T10:00:00Z"))),
            ),
            KodexAgentSettings(model = OpenAiModelId("gpt-5")),
            LlmRequestMeta.Initialization(0),
            LlmRequestMeta.Response(42),
            UnstableSnapshot(
                listOf(
                    PendingPlanUpdate("c1", null, UpdatePlanArgs("plan", emptyList())),
                    PendingAwaitEventToolEvent("c2", null, AwaitEventArguments(listOf(4))),
                ),
            ),
            AskUserSnapshot(
                listOf(
                    AskUserEntry(
                        askId = 1,
                        questions = sampleArgs,
                        answer = AskUserAnswer(sampleResponse, Instant.parse("2026-11-15T10:01:00Z")),
                        createdAt = Instant.parse("2026-11-15T10:00:00Z"),
                    ),
                ),
            ),
        )
        for ((idx, commit) in commits.withIndex()) {
            val payload = commit.encodePayloadJson()
            assertEquals(commit, commit.commitType.decodePayloadJson(payload))
            val polymorphic = OpenAiJsonCodec.encodeToString(AgentCommit.serializer(), commit)
            assertEquals(commit, OpenAiJsonCodec.decodeFromString(AgentCommit.serializer(), polymorphic))
            val indexed = IndexedAgentCommit(idx, commit)
            val wire = OpenAiJsonCodec.encodeToString(IndexedAgentCommit.serializer(), indexed)
            assertEquals(indexed, OpenAiJsonCodec.decodeFromString(IndexedAgentCommit.serializer(), wire))
        }
    }
}
