package io.github.stream29.kodex.tool.multiagent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val suggestSubagentTaskToolsTest by testSuite {
    test("spec exposes only task content") {
        val parameters = OpenAiJsonCodec.parseToJsonElement(
            OpenAiJsonCodec.encodeToString(SuggestSubagentTaskTools.spec.parameters),
        ).jsonObject
        val taskProperties = parameters.getValue("properties")
            .jsonObject.getValue("tasks")
            .jsonObject.getValue("items").jsonObject
            .getValue("properties").jsonObject

        assertEquals("suggest_subagent_task", SuggestSubagentTaskTools.spec.name)
        assertFalse(SuggestSubagentTaskTools.spec.strict)
        assertTrue("name" in taskProperties)
        assertTrue("prompt" in taskProperties)
        assertFalse("model" in taskProperties)
        assertFalse("cwd" in taskProperties)
    }

    test("accepted and rejected responses have explicit decisions and null feedback") {
        val stableJson = Json
        val accepted = stableJson.encodeToString(
            SuggestSubagentTaskResponse.Accepted.serializer(),
            SuggestSubagentTaskResponse.Accepted(
                feedback = null,
                sessions = listOf(SuggestedSessionMeta("memory:abc", "Inspect")),
            ),
        )
        val rejected = stableJson.encodeToString(
            SuggestSubagentTaskResponse.Rejected.serializer(),
            SuggestSubagentTaskResponse.Rejected(feedback = null),
        )

        assertEquals(
            """{"feedback":null,"sessions":[{"uri":"memory:abc","name":"Inspect"}],"decision":"accepted"}""",
            accepted,
        )
        assertEquals("""{"feedback":null,"decision":"rejected"}""", rejected)
    }
}
