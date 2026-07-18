package io.github.stream29.codex.lite.openai

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

private val json = OpenAiJsonCodec

val modelCatalogModelsSerializationTest by testSuite {
    test("decodes server-advertised reasoning metadata") {
        val response = json.decodeFromString<ModelsResponse>(
            """
            {
              "models": [{
                "slug": "gpt-test",
                "display_name": "GPT Test",
                "default_reasoning_level": "ultra",
                "supported_reasoning_levels": [
                  {"effort": "max", "description": "Maximum reasoning"},
                  {"effort": "ultra", "description": "Delegated reasoning"},
                  {"effort": "future", "description": "Future reasoning"}
                ]
              }]
            }
            """.trimIndent(),
        )

        val model = response.models.single()
        assertEquals(ReasoningEffort.Ultra, model.defaultReasoningLevel)
        assertEquals(
            listOf(
                ReasoningEffort.Max,
                ReasoningEffort.Ultra,
                ReasoningEffort.Custom("future"),
            ),
            model.supportedReasoningLevels.map(ReasoningEffortPreset::effort),
        )
    }

    test("round trips custom reasoning metadata as primitive wire values") {
        val model = ModelInfo(
            slug = OpenAiModelId("gpt-test"),
            displayName = "GPT Test",
            defaultReasoningLevel = ReasoningEffort.Custom("adaptive"),
            supportedReasoningLevels = listOf(
                ReasoningEffortPreset(ReasoningEffort.Medium, "Balanced"),
                ReasoningEffortPreset(ReasoningEffort.Custom("adaptive"), "Adaptive"),
            ),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(model)).jsonObject

        assertEquals(JsonPrimitive("adaptive"), encoded["default_reasoning_level"])
        assertEquals(
            JsonPrimitive("adaptive"),
            encoded.getValue("supported_reasoning_levels")
                .jsonArray[1]
                .jsonObject["effort"],
        )
        assertEquals(model, json.decodeFromString<ModelInfo>(json.encodeToString(model)))
    }
}
