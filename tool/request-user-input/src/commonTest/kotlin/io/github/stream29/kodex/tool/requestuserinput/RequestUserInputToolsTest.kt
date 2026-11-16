package io.github.stream29.kodex.tool.requestuserinput

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val json = Json { explicitNulls = false }

val requestUserInputToolsTest by testSuite {
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

        val encoded = json.parseToJsonElement(
            json.encodeToString(RequestUserInputArgs.serializer(), arguments),
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
            json.encodeToString(
                RequestUserInputResponse.serializer(),
                RequestUserInputResponse(
                    answers = mapOf("scope" to RequestUserInputAnswer(listOf("Narrow"))),
                ),
            ),
        )
    }
}
