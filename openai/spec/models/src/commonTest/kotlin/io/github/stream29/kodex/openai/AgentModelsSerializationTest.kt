package io.github.stream29.kodex.openai

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlin.test.assertEquals

private val json = OpenAiJsonCodec

val agentModelsSerializationTest by testSuite {
    test("request user input modes use stable wire names") {
        assertEquals("\"ask_user\"", json.encodeToString(RequestUserInputMode.AskUser))
        assertEquals("\"no_question\"", json.encodeToString(RequestUserInputMode.NoQuestion))
    }

    test("agent settings without request user input mode default to ask user") {
        val settings = json.decodeFromString<KodexAgentSettings>(
            """{"model":"test-model"}""",
        )

        assertEquals(RequestUserInputMode.AskUser, settings.requestUserInputMode)
    }

    test("thread goal uses Rust camel case status and fields") {
        val encoded = json.encodeToString(
            ThreadGoal(
                objective = "Finish the implementation.",
                status = ThreadGoalStatus.BudgetLimited,
                tokenBudget = 100,
                tokensUsed = 80,
                timeUsedSeconds = 12,
            ),
        )

        assertEquals(
            """{"objective":"Finish the implementation.","status":"budgetLimited","tokenBudget":100,"tokensUsed":80,"timeUsedSeconds":12}""",
            encoded,
        )
    }
}
