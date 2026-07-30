package io.github.stream29.kodex.tool.plan

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val planToolsTest by testSuite {
    test("spec matches the update plan function schema") {
        val parameters = OpenAiJsonCodec.parseToJsonElement(
            OpenAiJsonCodec.encodeToString(PlanTools.spec.parameters),
        ).jsonObject

        assertEquals(PlanTools.Name, PlanTools.spec.name)
        assertEquals(
            "Updates the task plan.\n" +
                "Provide an optional explanation and a list of plan items, each with a step and status.\n" +
                "At most one step can be in_progress at a time.\n",
            PlanTools.spec.description,
        )
        assertEquals(false, PlanTools.spec.strict)
        assertEquals(JsonPrimitive(false), parameters["additionalProperties"])
        assertTrue("plan" in parameters.getValue("properties").jsonObject)
    }
}
