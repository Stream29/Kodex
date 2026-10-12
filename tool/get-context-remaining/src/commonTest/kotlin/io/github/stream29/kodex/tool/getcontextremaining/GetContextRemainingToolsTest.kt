package io.github.stream29.kodex.tool.getcontextremaining

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val getContextRemainingToolsTest by testSuite {
    test("spec declares the Rust-aligned nullable output union") {
        val schema = OpenAiJsonCodec.parseToJsonElement(
            OpenAiJsonCodec.encodeToString(GetContextRemainingTools.spec.outputSchema),
        ).jsonObject
        val tokensLeft = schema.getValue("properties").jsonObject
            .getValue("tokens_left")
            .jsonObject

        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertTrue(JsonPrimitive("tokens_left") in schema.getValue("required").jsonArray)
        assertEquals(
            listOf(JsonPrimitive("integer"), JsonPrimitive("null")),
            tokensLeft.getValue("anyOf").jsonArray.map { option ->
                option.jsonObject.getValue("type")
            },
        )
    }
}
