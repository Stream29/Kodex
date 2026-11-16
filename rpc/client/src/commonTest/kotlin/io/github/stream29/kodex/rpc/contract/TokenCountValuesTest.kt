package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.TokenCountDiagnostics
import io.github.stream29.kodex.openai.TokenUsage
import io.github.stream29.kodex.openai.TokenUsageInputDetails
import io.github.stream29.kodex.openai.TokenUsageOutputDetails
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

val tokenCountValuesTest by testSuite {
    test("timeline values retain snapshot kinds usage diagnostics and sparse indexes") {
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
        val entries = listOf(
            0 to LlmRequestMeta.Legacy(0),
            2 to LlmRequestMeta.Initialization(0),
            7 to LlmRequestMeta.Compaction(10),
            11 to response,
        )
        val serializer = ListSerializer(PairSerializer(Int.serializer(), LlmRequestMeta.serializer()))
        assertEquals(entries, Json.decodeFromString(serializer, Json.encodeToString(serializer, entries)))
    }

    test("missing details remain distinct from reported zero and false") {
        val missing = assertIs<LlmRequestMeta.Response>(
            Json.decodeFromString<LlmRequestMeta>(
                """{"type":"llm_request_meta_response","total_tokens":0}""",
            ),
        )
        val reported = missing.copy(
            usage = TokenUsage(0, 0, 0, TokenUsageInputDetails(0), TokenUsageOutputDetails(0)),
            diagnostics = TokenCountDiagnostics(turnStateSent = false),
        )
        assertEquals(LlmRequestMeta.Response(0), missing)
        assertNotEquals<LlmRequestMeta>(missing, reported)
        assertEquals<LlmRequestMeta>(
            reported,
            Json.decodeFromString<LlmRequestMeta>(Json.encodeToString<LlmRequestMeta>(reported)),
        )
    }
}
