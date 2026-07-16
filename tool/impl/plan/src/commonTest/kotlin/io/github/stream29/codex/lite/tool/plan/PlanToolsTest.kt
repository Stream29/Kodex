package io.github.stream29.codex.lite.tool.plan

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.tool.builder.ToolBuilderJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val planToolsTest by testSuite {
    test("spec matches the update plan function schema") {
        val parameters = OpenAiJsonCodec.parseToJsonElement(
            OpenAiJsonCodec.encodeToString(PlanTools.spec.parameters),
        ).jsonObject

        assertEquals(PlanTools.Name, PlanTools.spec.name)
        assertEquals(false, PlanTools.spec.strict)
        assertEquals(JsonPrimitive(false), parameters["additionalProperties"])
        assertTrue("plan" in parameters.getValue("properties").jsonObject)
    }

    test("parser rejects unknown plan fields") {
        assertFailsWith<SerializationException> {
            ToolBuilderJson.decodeFromString<UpdatePlanArgs>("{\"plan\":[],\"unexpected\":true}")
        }
    }
}
