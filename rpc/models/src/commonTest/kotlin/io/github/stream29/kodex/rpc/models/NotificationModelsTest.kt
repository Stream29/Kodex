package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

val notificationModelsTest by testSuite {
    val message = StableAssistantMessage(
        content = listOf(ContentItem.OutputText("完成。\nSecond line")),
    )
    val question = PendingRequestUserInputToolEvent(
        callId = "input-1",
        arguments = RequestUserInputArgs(
            questions = listOf(
                RequestUserInputQuestion("scope", "Scope", "Which scope?"),
                RequestUserInputQuestion("target", "Target", "Which target?", isOther = true),
            ),
            autoResolutionMs = 60_000,
        ),
    )
    val suggestion = PendingSuggestSubagentTaskToolEvent(
        callId = "suggestion-1",
        arguments = SuggestSubagentTaskArgs(
            tasks = listOf(
                SuggestedSubagentTask("Inspect", "Inspect the fixture."),
                SuggestedSubagentTask("Verify", "Verify the fixture."),
            ),
        ),
    )
    val stops = listOf(
        Notification.Stop.AssistantMessage(7, message),
        Notification.Stop.RequestUserInput(7, listOf(question)),
        Notification.Stop.SuggestSubagent(8, listOf(suggestion)),
        Notification.Stop.UnhandledError(8, "Final fixture failure"),
    )

    test("all stop branches round trip through both sealed levels") {
        for (stop in stops) {
            val encoded = Json.encodeToString<Notification>(stop)
            assertEquals(stop, Json.decodeFromString<Notification>(encoded))
            assertEquals(encoded, Json.encodeToString<Notification.Stop>(stop))
            assertEquals(stop, Json.decodeFromString<Notification.Stop>(encoded))
        }
        assertEquals(
            4,
            stops.map { Json.encodeToJsonElement<Notification>(it).jsonObject.getValue("type") }.toSet().size,
        )
    }

    test("notification payloads retain the original domain value encoding") {
        val assistant = Json.encodeToJsonElement<Notification>(stops[0]).jsonObject
        assertEquals(setOf("type", "sessionIndex", "message"), assistant.keys)
        assertEquals(Json.encodeToJsonElement(message), assistant.getValue("message"))
        val input = Json.encodeToJsonElement<Notification>(stops[1]).jsonObject
        assertEquals(setOf("type", "sessionIndex", "requests"), input.keys)
        assertEquals(Json.encodeToJsonElement(question), input.getValue("requests").jsonArray.single())
        val tasks = Json.encodeToJsonElement<Notification>(stops[2]).jsonObject
        assertEquals(setOf("type", "sessionIndex", "requests"), tasks.keys)
        assertEquals(Json.encodeToJsonElement(suggestion), tasks.getValue("requests").jsonArray.single())
    }

    test("request batches preserve call order duplicates and nested questions and tasks") {
        val input = Notification.Stop.RequestUserInput(
            7, listOf(question.copy(callId = "input-2"), question, question),
        )
        val tasks = Notification.Stop.SuggestSubagent(
            7, listOf(suggestion, suggestion.copy(callId = "suggestion-2"), suggestion),
        )
        val events: List<Notification> = listOf(input, tasks)
        val decoded = Json.decodeFromString<List<Notification>>(Json.encodeToString(events))
        assertEquals(events, decoded)
        val decodedInput = assertIs<Notification.Stop.RequestUserInput>(decoded[0])
        assertEquals(listOf("input-2", "input-1", "input-1"), decodedInput.requests.map { it.callId })
        assertEquals(2, decodedInput.requests[0].arguments.questions.size)
        val decodedTasks = assertIs<Notification.Stop.SuggestSubagent>(decoded[1])
        assertEquals(2, decodedTasks.requests[0].arguments.tasks.size)
    }

    test("error diagnostics preserve absence and JSON looking text without Throwable fields") {
        for (diagnostic in listOf(null, "", "failure\n诊断", """{"type":"not.an.exception.protocol"}""")) {
            val event = Notification.Stop.UnhandledError(7, diagnostic)
            val payload = Json.encodeToJsonElement<Notification>(event).jsonObject
            assertEquals(setOf("type", "sessionIndex", "message"), payload.keys)
            if (diagnostic == null) assertEquals(JsonNull, payload.getValue("message"))
            assertEquals(event, Json.decodeFromString<Notification>(payload.toString()))
        }
    }

    test("empty request groups are rejected in constructors and decoding") {
        assertFailsWith<IllegalArgumentException> {
            Notification.Stop.RequestUserInput(7, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            Notification.Stop.SuggestSubagent(7, emptyList())
        }
        for (kind in listOf("stop_request_user_input", "stop_suggest_subagent")) {
            assertFailsWith<IllegalArgumentException> {
                Json.decodeFromString<Notification>("""{"type":"$kind","sessionIndex":7,"requests":[]}""")
            }
        }
    }

    test("unknown kinds and missing or null required fields are rejected") {
        for (encoded in listOf(
            """{"type":"unknown"}""",
            """{"type":"stop_unhandled_error","message":"failure"}""",
            """{"type":"stop_unhandled_error","sessionIndex":7}""",
            """{"type":"stop_unhandled_error","sessionIndex":null,"message":"failure"}""",
            """{"type":"stop_assistant_message","sessionIndex":7,"message":null}""",
            """{"type":"stop_request_user_input","sessionIndex":7,"requests":null}""",
            """{"type":"stop_suggest_subagent","sessionIndex":7}""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                Json.decodeFromString<Notification>(encoded)
            }
        }
    }
}
