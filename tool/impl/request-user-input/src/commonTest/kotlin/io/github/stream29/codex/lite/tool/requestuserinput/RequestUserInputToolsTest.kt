package io.github.stream29.codex.lite.tool.requestuserinput

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val requestUserInputToolsTest by testSuite {
    test("spec declares the Rust-aligned question schema") {
        val parameters = OpenAiJsonCodec.parseToJsonElement(
            OpenAiJsonCodec.encodeToString(RequestUserInputTools.spec.parameters),
        ).jsonObject
        val properties = parameters.getValue("properties").jsonObject
        val questions = properties.getValue("questions").jsonObject
        val questionProperties = questions.getValue("items").jsonObject
            .getValue("properties")
            .jsonObject
        val options = questionProperties.getValue("options").jsonObject

        assertEquals(RequestUserInputTools.Name, RequestUserInputTools.spec.name)
        assertFalse(RequestUserInputTools.spec.strict)
        assertEquals(JsonPrimitive(false), parameters["additionalProperties"])
        assertEquals(JsonPrimitive("integer"), properties.getValue("autoResolutionMs").jsonObject["type"])
        assertTrue(
            JsonPrimitive("options") in questions.getValue("items").jsonObject.getValue("required").jsonArray,
        )
        assertEquals(JsonPrimitive(false), options.getValue("items").jsonObject["additionalProperties"])
        assertTrue(JsonPrimitive("questions") in parameters.getValue("required").jsonArray)
    }

    test("arguments use the tool wire names and omit an absent timeout") {
        val arguments = RequestUserInputArgs(
            questions = listOf(
                RequestUserInputQuestion(
                    id = "build_scope",
                    header = "Scope",
                    question = "Which implementation scope should we use?",
                    options = listOf(
                        RequestUserInputQuestionOption(
                            label = "Narrow (Recommended)",
                            description = "Implement the smallest complete change.",
                        ),
                    ),
                ),
            ),
        )

        val encoded = OpenAiJsonCodec.parseToJsonElement(
            OpenAiJsonCodec.encodeToString(RequestUserInputArgs.serializer(), arguments),
        ).jsonObject

        assertTrue("questions" in encoded)
        assertFalse("autoResolutionMs" in encoded)
        assertEquals(
            JsonPrimitive("build_scope"),
            encoded.getValue("questions").jsonArray.single().jsonObject["id"],
        )
    }
}
