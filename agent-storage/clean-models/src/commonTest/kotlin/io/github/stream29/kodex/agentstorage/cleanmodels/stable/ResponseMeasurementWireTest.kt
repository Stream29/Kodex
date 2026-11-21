package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.TokenUsage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalSerializationApi::class)
val responseMeasurementWireTest by testSuite {
    test("positive elapsed nanoseconds and real zero output serialize separately from usage and latency") {
        val response = LlmRequestMeta.Response(
            totalTokens = 30,
            elapsedNanoseconds = 450,
            outputTokens = 0,
            usage = TokenUsage(inputTokens = 30, outputTokens = 0, totalTokens = 30),
            latencyMs = 19,
        )
        val serializer = LlmRequestMeta.Response.serializer()
        val payload = Json.encodeToJsonElement(serializer, response).jsonObject
        assertEquals(JsonPrimitive(450), payload["elapsed_ns"])
        assertEquals(JsonPrimitive(0), payload["output_tokens"])
        assertEquals(JsonPrimitive(19), payload["latency_ms"])
        assertEquals(JsonPrimitive(30), payload["total_tokens"])
        assertEquals(response, Json.decodeFromString(serializer, payload.toString()))
    }

    test("an unbackfilled response cannot silently decode into a measured sample") {
        val serializer = LlmRequestMeta.Response.serializer()
        assertFailsWith<MissingFieldException> {
            Json.decodeFromString(serializer, """{"total_tokens":41,"usage":{"input_tokens":40,"output_tokens":1,"total_tokens":41}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            LlmRequestMeta.Response(totalTokens = 41, elapsedNanoseconds = 0, outputTokens = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            LlmRequestMeta.Response(totalTokens = 41, elapsedNanoseconds = 5, outputTokens = -1)
        }
    }
}
