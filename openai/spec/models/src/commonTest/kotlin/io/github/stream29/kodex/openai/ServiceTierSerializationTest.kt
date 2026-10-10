package io.github.stream29.kodex.openai

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

val serviceTierSerializationTest by testSuite {
    val wireValues = listOf(
        ServiceTier.Default to "default",
        ServiceTier.Fast to "priority",
        ServiceTier.Flex to "flex",
        ServiceTier.Ultrafast to "ultrafast",
    )
    val codecs = listOf(OpenAiJsonCodec, Json(OpenAiJsonCodec) { encodeDefaults = false })

    test("tier names retain old wire values and append ultrafast") {
        assertEquals(wireValues.map { it.first }, ServiceTier.entries.toList())
        for ((tier, wireValue) in wireValues) {
            assertEquals(wireValue, tier.requestValue)
            assertEquals("\"$wireValue\"", OpenAiJsonCodec.encodeToString(tier))
            assertEquals(tier, OpenAiJsonCodec.decodeFromString<ServiceTier>("\"$wireValue\""))
        }
    }

    test("responses omit default routing and preserve every explicit tier") {
        for (codec in codecs) {
            for ((tier, wireValue) in wireValues) {
                val request = ResponsesApiRequest(
                    model = OpenAiModelId("test-model"),
                    input = emptyList(),
                    serviceTier = tier,
                )
                val body = codec.encodeToJsonElement(ResponsesApiRequest.serializer(), request).jsonObject
                if (tier == ServiceTier.Default) {
                    assertFalse("service_tier" in body)
                } else {
                    assertEquals(JsonPrimitive(wireValue), body["service_tier"])
                }
                assertFalse("x-codex-routing-hint" in body)
                assertFalse("OpenAI-Service-Tier" in body)
            }
        }
    }

    test("agent settings preserve requested tiers across serialization and model copies") {
        for (codec in codecs) {
            for ((tier, wireValue) in wireValues) {
                val settings = KodexAgentSettings(OpenAiModelId("original"), serviceTier = tier)
                val encoded = codec.encodeToJsonElement(KodexAgentSettings.serializer(), settings)
                if (tier != ServiceTier.Default) {
                    assertEquals(JsonPrimitive(wireValue), encoded.jsonObject["serviceTier"])
                }
                assertEquals(settings, codec.decodeFromJsonElement(KodexAgentSettings.serializer(), encoded))
                assertEquals(tier, settings.copy(model = OpenAiModelId("different")).serviceTier)
            }
            assertEquals(
                ServiceTier.Default,
                codec.decodeFromString<KodexAgentSettings>("""{"model":"legacy"}""").serviceTier,
            )
        }
    }

    test("provider reported tiers remain nullable open strings without usage") {
        for (actual in listOf("ultrafast", "default", "priority", "flex", "future-tier")) {
            val response = OpenAiJsonCodec.decodeFromString<Response>(
                """{"id":"response","service_tier":"$actual"}""",
            )
            assertEquals(actual, response.serviceTier)
            assertNull(response.usage)
            val encoded = OpenAiJsonCodec.encodeToJsonElement(Response.serializer(), response)
            assertEquals(JsonPrimitive(actual), encoded.jsonObject["service_tier"])
        }
        assertNull(OpenAiJsonCodec.decodeFromString<Response>("""{"id":"response"}""").serviceTier)
    }

    test("unknown request tiers are not silently converted to defaults") {
        for (unknown in listOf("future-tier", "ultra-fast", "ultra")) {
            assertFailsWith<SerializationException> {
                OpenAiJsonCodec.decodeFromString<ServiceTier>("\"$unknown\"")
            }
        }
    }
}
