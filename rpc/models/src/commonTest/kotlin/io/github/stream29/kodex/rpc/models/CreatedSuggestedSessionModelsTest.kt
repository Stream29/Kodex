package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.tool.multiagent.SuggestedSessionMeta
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val createdSuggestedSessionModelsTest by testSuite {
    test("created children retain input order and original metadata") {
        val children = listOf(
            CreatedSuggestedSession(42, SuggestedSessionMeta("fixture://sessions/42", "同名任务")),
            CreatedSuggestedSession(0, SuggestedSessionMeta("fixture://sessions/0", "同名任务")),
            CreatedSuggestedSession(Int.MAX_VALUE, SuggestedSessionMeta("fixture://last", "quoted \"name\"\n")),
        )
        val encoded = Json.encodeToString(children)
        assertEquals(children, Json.decodeFromString<List<CreatedSuggestedSession>>(encoded))
        assertEquals(
            listOf(42, 0, Int.MAX_VALUE),
            Json.decodeFromString<List<CreatedSuggestedSession>>(encoded).map { it.sessionIndex },
        )
        assertEquals(emptyList(), Json.decodeFromString<List<CreatedSuggestedSession>>("[]"))
    }

    test("result adds only the index and reuses the original tool metadata encoding") {
        val meta = SuggestedSessionMeta("fixture://session", "Session 7")
        val child = CreatedSuggestedSession(7, meta)
        val encoded = Json.encodeToJsonElement(CreatedSuggestedSession.serializer(), child).jsonObject
        assertEquals(setOf("sessionIndex", "meta"), encoded.keys)
        assertEquals(Json.encodeToJsonElement(SuggestedSessionMeta.serializer(), meta), encoded["meta"])

        val response: SuggestSubagentTaskResponse = SuggestSubagentTaskResponse.Accepted(
            feedback = null,
            sessions = listOf(child).map { it.meta },
        )
        assertEquals(
            response,
            Json.decodeFromString<SuggestSubagentTaskResponse>(Json.encodeToString(response)),
        )
    }

    test("index and original metadata remain required values") {
        for (invalid in listOf(
            """{"meta":{"uri":"fixture://session","name":"Task"}}""",
            """{"sessionIndex":1}""",
            """{"sessionIndex":null,"meta":{"uri":"fixture://session","name":"Task"}}""",
            """{"sessionIndex":1,"meta":null}""",
            """{"sessionIndex":1,"meta":{"name":"Task"}}""",
            """{"sessionIndex":1,"meta":{"uri":"fixture://session"}}""",
        )) {
            assertFailsWith<SerializationException> {
                Json.decodeFromString<CreatedSuggestedSession>(invalid)
            }
        }
    }
}
