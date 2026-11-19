package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponseItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

val subagentHistoryEventsTest by testSuite {
    test("creation reads legacy empty metadata and round-trips the name and prompt without changing model input") {
        val legacy = """{"type":"event_subagent_created","child_agent_id":91}"""
        val old = assertIs<StableSubagentCreatedEvent>(AgentCommitType.EventSubagentCreated.decodePayloadJson(legacy))
        assertEquals("", old.childName)
        assertEquals("", old.prompt)
        assertEquals("Subagent #91 created", old.notice)
        val created = StableSubagentCreatedEvent(91, "Reviewer", "first line\nsecond line")
        assertEquals(created, created.commitType.decodePayloadJson(created.encodePayloadJson()))
        assertEquals("Subagent Reviewer created", created.notice)
        assertEquals(old.toResponseHistoryItems(), created.toResponseHistoryItems())
    }

    test("both child notices are Index; completion keeps its old payload, turn boundary and steer identity") {
        val created = StableSubagentCreatedEvent(91)
        val completed = StableSubagentCompletedEvent(92)
        for ((event, id) in listOf(created to 91, completed to 92)) {
            val json = event.encodePayloadJson()
            val payload = Json.parseToJsonElement(json).jsonObject
            val expectedKeys = setOf("type", "child_agent_id") +
                if (event == created) setOf("child_name", "prompt") else emptySet()
            assertEquals(expectedKeys, payload.keys)
            assertEquals(JsonPrimitive(id), payload["child_agent_id"])
            assertEquals(event, event.commitType.decodePayloadJson(json))
            val modelMessage = assertIs<ResponseItem.Message>(event.toResponseHistoryItems().single())
            assertTrue(assertIs<ContentItem.InputText>(modelMessage.content.single()).text.contains("#$id"))
        }
        assertTrue(created.commitType in AgentCommitType.IndexTypes)
        assertFalse(created.commitType in AgentCommitType.WorkTypes)
        assertFalse(completed.commitType in AgentCommitType.WorkTypes)
        assertTrue(completed.commitType in AgentCommitType.IndexTypes)
        assertTrue(completed.commitType in AgentCommitType.TurnStartBoundaryTypes)
        assertFalse(completed.commitType in AgentCommitType.CompactionRetainedTypes)
        val oldPayload = """{"type":"event_subagent_completed","child_agent_id":92}"""
        assertEquals(oldPayload, completed.encodePayloadJson())
        assertEquals(completed, AgentCommitType.EventSubagentCompleted.decodePayloadJson(oldPayload))
        val steer = SteerSnapshot(listOf(completed))
        assertEquals("[$oldPayload]", steer.encodePayloadJson())
        assertEquals(steer, steer.commitType.decodePayloadJson("[$oldPayload]"))
    }
}
