@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
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

    test("token count snapshots round-trip across all four subtypes and preserve type discriminator") {
        val response = TokenCountSnapshot.Response(
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
        val snapshots = listOf<TokenCountSnapshot>(
            TokenCountSnapshot.Legacy(0),
            TokenCountSnapshot.Initialization(0),
            TokenCountSnapshot.Compaction(10),
            response,
        )
        for (snapshot in snapshots) {
            val payload = snapshot.encodePayloadJson()
            assertEquals(snapshot, snapshot.commitType.decodePayloadJson(payload))
            assertEquals(snapshot, OpenAiJsonCodec.decodeFromString(TokenCountSnapshot.serializer(), payload))
            assertEquals(snapshot, OpenAiJsonCodec.decodeFromString(AgentCommit.serializer(), payload))
        }

        val missing = Json.decodeFromString<TokenCountSnapshot>("""{"type":"token_count_response","total_tokens":0}""")
        val reported = (missing as TokenCountSnapshot.Response).copy(
            usage = TokenUsage(0, 0, 0, TokenUsageInputDetails(0), TokenUsageOutputDetails(0)),
            diagnostics = TokenCountDiagnostics(turnStateSent = false),
        )
        assertEquals(TokenCountSnapshot.Response(0), missing)
        assertNotEquals<TokenCountSnapshot>(missing, reported)
    }

    test("all agent commit families round-trip through native AgentCommit polymorphism and payload helpers") {
        val commits: List<AgentCommit> = listOf(
            StableUserMessage(listOf(ContentItem.InputText("hi"))),
            StableAssistantMessage(listOf(ContentItem.OutputText("plan")), phase = MessagePhase.Commentary),
            StableAssistantMessage(listOf(ContentItem.OutputText("done")), phase = MessagePhase.FinalAnswer),
            StableAssistantMessage(listOf(ContentItem.OutputText("legacy")), phase = null),
            StableContextCompaction(encryptedContent = "compacted"),
            StableReasoning(ResponseItem.Reasoning(ResponseItemId("r1"), emptyList())),
            KodexAgentSettings(model = OpenAiModelId("gpt-5")),
            CommitTimestamp(Instant.parse("2026-11-05T12:00:00Z")),
            TokenCountSnapshot.Initialization(0),
            TokenCountSnapshot.Response(42),
            UnstableSnapshot(listOf(PendingPlanUpdate("c1", null, UpdatePlanArgs("plan", emptyList())))),
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
