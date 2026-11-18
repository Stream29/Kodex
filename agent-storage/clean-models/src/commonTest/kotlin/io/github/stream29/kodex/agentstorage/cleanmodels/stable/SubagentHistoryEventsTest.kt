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
    test("the child ID is the only event payload; creation is Index and completion is steerable Work") {
        val created = StableSubagentCreatedEvent(91)
        val completed = StableSubagentCompletedEvent(92)
        for ((event, id) in listOf(created to 91, completed to 92)) {
            val json = event.encodePayloadJson()
            val payload = Json.parseToJsonElement(json).jsonObject
            assertEquals(setOf("type", "child_agent_id"), payload.keys)
            assertEquals(JsonPrimitive(id), payload["child_agent_id"])
            assertEquals(event, event.commitType.decodePayloadJson(json))
            val modelMessage = assertIs<ResponseItem.Message>(event.toResponseHistoryItems().single())
            assertTrue(assertIs<ContentItem.InputText>(modelMessage.content.single()).text.contains("#$id"))
        }
        assertTrue(created.commitType in AgentCommitType.IndexTypes)
        assertFalse(created.commitType in AgentCommitType.WorkTypes)
        assertTrue(completed.commitType in AgentCommitType.WorkTypes)
        assertFalse(completed.commitType in AgentCommitType.IndexTypes)
        assertFalse(completed.commitType in AgentCommitType.CompactionRetainedTypes)
        val steer = SteerSnapshot(listOf(completed))
        assertEquals(steer, steer.commitType.decodePayloadJson(steer.encodePayloadJson()))
    }
}
