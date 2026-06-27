package io.github.stream29.codex.lite.openai

import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanToolModelsSerializationTest {
    private val json = OpenAiJsonCodec

    @Test
    fun updatePlanArgsSerializesRustWireShape() {
        val encoded = json.encodeToString(
            UpdatePlanArgs(
                explanation = "next steps",
                plan = listOf(
                    PlanItemArg("Inspect state model", StepStatus.Completed),
                    PlanItemArg("Add plan timeline", StepStatus.InProgress),
                    PlanItemArg("Wire fork logic", StepStatus.Pending),
                ),
            ),
        )

        assertEquals(
            """{"explanation":"next steps","plan":[{"step":"Inspect state model","status":"completed"},{"step":"Add plan timeline","status":"in_progress"},{"step":"Wire fork logic","status":"pending"}]}""",
            encoded,
        )
    }

    @Test
    fun updatePlanArgsDecodesOmittedExplanationAsNull() {
        val decoded = json.decodeFromString<UpdatePlanArgs>(
            """{"plan":[{"step":"Review model","status":"pending"}]}""",
        )

        assertNull(decoded.explanation)
        assertEquals(listOf(PlanItemArg("Review model", StepStatus.Pending)), decoded.plan)
    }
}
