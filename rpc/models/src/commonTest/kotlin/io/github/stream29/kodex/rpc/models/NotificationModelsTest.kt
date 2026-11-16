@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

val notificationModelsTest by testSuite {
    val message = StableAssistantMessage(
        content = listOf(ContentItem.OutputText("完成。\nSecond line")),
    )
    val askEntry = AskUserEntry(
        askId = 1,
        questions = RequestUserInputArgs(
            questions = listOf(
                RequestUserInputQuestion("scope", "Scope", "Which scope?"),
                RequestUserInputQuestion("target", "Target", "Which target?", isOther = true),
            ),
            autoResolutionMs = 60_000,
        ),
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
    )
    val stops = listOf(
        Notification.Stop.AssistantMessage(7, message),
        Notification.Stop.UnhandledError(8, "Final fixture failure"),
    )
    val notifications: List<Notification> = stops + Notification.AskUserCreated(7, askEntry)

    test("all notification and stop branches round trip through sealed levels") {
        for (notification in notifications) {
            val encoded = Json.encodeToString<Notification>(notification)
            assertEquals(notification, Json.decodeFromString<Notification>(encoded))
        }
        for (stop in stops) {
            val encoded = Json.encodeToString<Notification>(stop)
            assertEquals(encoded, Json.encodeToString<Notification.Stop>(stop))
            assertEquals(stop, Json.decodeFromString<Notification.Stop>(encoded))
        }
        assertEquals(
            3,
            notifications.map { Json.encodeToJsonElement<Notification>(it).jsonObject.getValue("type") }.toSet().size,
        )
    }

    test("notification payloads retain the original domain value encoding") {
        val assistant = Json.encodeToJsonElement<Notification>(stops[0]).jsonObject
        assertEquals(setOf("type", "sessionIndex", "message"), assistant.keys)
        assertEquals(Json.encodeToJsonElement(message), assistant.getValue("message"))
        val askCreated = Json.encodeToJsonElement<Notification>(Notification.AskUserCreated(7, askEntry)).jsonObject
        assertEquals(setOf("type", "sessionIndex", "entry"), askCreated.keys)
        assertEquals(Json.encodeToJsonElement(askEntry), askCreated.getValue("entry"))
        val decoded = assertIs<Notification.AskUserCreated>(
            Json.decodeFromString<Notification>(askCreated.toString()),
        )
        assertEquals(2, decoded.entry.questions.questions.size)
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

    test("unknown kinds retired branches and missing or null required fields are rejected") {
        for (encoded in listOf(
            """{"type":"unknown"}""",
            """{"type":"stop_request_user_input","sessionIndex":7,"requests":[]}""",
            """{"type":"stop_suggest_subagent","sessionIndex":7,"requests":[]}""",
            """{"type":"stop_unhandled_error","message":"failure"}""",
            """{"type":"stop_unhandled_error","sessionIndex":7}""",
            """{"type":"stop_unhandled_error","sessionIndex":null,"message":"failure"}""",
            """{"type":"stop_assistant_message","sessionIndex":7,"message":null}""",
            """{"type":"ask_user_created","sessionIndex":7,"entry":null}""",
            """{"type":"ask_user_created","sessionIndex":7}""",
        )) {
            assertFailsWith<SerializationException> {
                Json.decodeFromString<Notification>(encoded)
            }
        }
    }
}
