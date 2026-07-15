package io.github.stream29.codex.lite.openai

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.encodeToString
import kotlin.test.assertEquals

private val json = OpenAiJsonCodec

val collaborationModelsSerializationTest by testSuite {
    test("visible collaboration modes use Rust wire names") {
        assertEquals("\"default\"", json.encodeToString(ModeKind.Default))
        assertEquals("\"plan\"", json.encodeToString(ModeKind.Plan))
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
