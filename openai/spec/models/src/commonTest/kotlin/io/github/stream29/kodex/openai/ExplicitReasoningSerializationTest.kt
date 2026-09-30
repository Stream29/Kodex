package io.github.stream29.kodex.openai

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse

val explicitReasoningSerializationTest by testSuite {
    val model = OpenAiModelId("test-model")
    val codecs = listOf(OpenAiJsonCodec, Json(OpenAiJsonCodec) { encodeDefaults = false })

    test("default and explicit efforts are sent in full response and search requests") {
        for (codec in codecs) {
            val defaults = listOf(
                codec.encodeToJsonElement(ResponsesApiRequest.serializer(), ResponsesApiRequest(model, emptyList())),
                codec.encodeToJsonElement(SearchRequest.serializer(), SearchRequest("test", model)),
            )
            for (encoded in defaults) {
                assertEquals(JsonPrimitive("medium"), encoded.jsonObject["reasoning"]!!.jsonObject["effort"])
            }
            for (effort in listOf(
                ReasoningEffort.None, ReasoningEffort.Minimal, ReasoningEffort.Low,
                ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.XHigh,
                ReasoningEffort.Max, ReasoningEffort.Custom("future-effort"),
            )) {
                val reasoning = Reasoning(effort = effort)
                val requests = listOf(
                    codec.encodeToJsonElement(ResponsesApiRequest.serializer(), ResponsesApiRequest(model, emptyList(), reasoning = reasoning)),
                    codec.encodeToJsonElement(SearchRequest.serializer(), SearchRequest("test", model, reasoning)),
                )
                for (request in requests) {
                    val encoded = request.jsonObject["reasoning"]!!.jsonObject
                    assertEquals(codec.encodeToJsonElement(ReasoningEffort.serializer(), effort), encoded["effort"])
                    assertFalse("summary" in encoded)
                    assertFalse("context" in encoded)
                }
            }
        }
    }

    test("non-default summary and context survive with medium") {
        for (summary in listOf(ReasoningSummary.Auto, ReasoningSummary.Concise, ReasoningSummary.Detailed)) {
            for (context in listOf(ReasoningContext.Auto, ReasoningContext.CurrentTurn, ReasoningContext.AllTurns)) {
                val value = Reasoning(summary = summary, context = context)
                val encoded = OpenAiJsonCodec.encodeToJsonElement(Reasoning.serializer(), value).jsonObject
                assertEquals(JsonPrimitive("medium"), encoded["effort"])
                assertEquals(summary != ReasoningSummary.Auto, "summary" in encoded)
                assertEquals(context != ReasoningContext.Auto, "context" in encoded)
                assertEquals(value, OpenAiJsonCodec.decodeFromJsonElement(Reasoning.serializer(), encoded))
            }
        }
    }

    test("legacy settings without reasoning or effort retain medium semantics") {
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
    }
}
