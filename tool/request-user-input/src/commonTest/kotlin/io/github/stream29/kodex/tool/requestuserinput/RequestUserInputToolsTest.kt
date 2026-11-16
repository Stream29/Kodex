package io.github.stream29.kodex.tool.requestuserinput

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val requestUserInputToolsTest by testSuite {
    test("schema declares the Rust-aligned question schema") {
        val parameters = OpenAiJsonCodec.parseToJsonElement(
            OpenAiJsonCodec.encodeToString(RequestUserInputParametersSchema),
        ).jsonObject
        val properties = parameters.getValue("properties").jsonObject
        val questions = properties.getValue("questions").jsonObject
        val questionProperties = questions.getValue("items").jsonObject
            .getValue("properties")
            .jsonObject
        val options = questionProperties.getValue("options").jsonObject

        assertEquals(JsonPrimitive(false), parameters["additionalProperties"])
        assertEquals(
            JsonPrimitive("Questions to show the user. Prefer 1 and do not exceed 3"),
            questions["description"],
        )
        assertEquals(JsonPrimitive("number"), properties.getValue("autoResolutionMs").jsonObject["type"])
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

    test("response preserves the keyed answer shape") {
        assertEquals(
            """{"answers":{"scope":{"answers":["Narrow"]}}}""",
            OpenAiJsonCodec.encodeToString(
                RequestUserInputResponse.serializer(),
                RequestUserInputResponse(
                    answers = mapOf("scope" to RequestUserInputAnswer(listOf("Narrow"))),
                ),
            ),
        )
    }
}
