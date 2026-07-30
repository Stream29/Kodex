package io.github.stream29.codex.lite.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentstorage.cleanmodels.CleanCompactionCheckpoint
import io.github.stream29.codex.lite.openai.AgentMessageInputContent
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessagePhase
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponseItemId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

private val stableEventJson = Json

val stableCleanEventSerializationTest by testSuite {
    test("round trips exact message metadata through the stable union") {
        val item = ResponseItem.Message(
            id = ResponseItemId("assistant_1"),
            role = MessageRole.Assistant,
            content = listOf(ContentItem.OutputText("done")),
            phase = MessagePhase.FinalAnswer,
        )
        val event: StableCleanEvent = StableCleanEvent.AssistantMessage(
            content = item.content,
            id = item.id,
            phase = item.phase,
        )

        val encoded = stableEventJson.encodeToString(event)
        val element = stableEventJson.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("assistant_message"), element["type"])
        assertEquals(setOf("type", "content", "id", "phase"), element.keys)
        assertEquals(event, stableEventJson.decodeFromString<StableCleanEvent>(encoded))
        assertEquals(listOf(item), event.toResponseHistoryItems())
    }

    test("round trips content-only user and developer messages") {
        val events = listOf(
            StableCleanEvent.UserMessage(
                listOf(ContentItem.InputText("user content")),
            ),
            StableCleanEvent.DeveloperMessage(
                listOf(ContentItem.InputText("host context")),
            ),
        )

        events.forEach { event ->
            val encoded = stableEventJson.encodeToString(event)

            assertEquals(
                setOf("type", "content"),
                stableEventJson.parseToJsonElement(encoded).jsonObject.keys,
            )
            assertEquals(event, stableEventJson.decodeFromString<StableCleanEvent>(encoded))
        }
    }

    test("round trips flattened inter-agent fields") {
        val event: StableCleanEvent = StableCleanEvent.AgentMessage(
            author = "/root/worker",
            recipient = "/root",
            content = listOf(
                AgentMessageInputContent.InputText("Message metadata"),
                AgentMessageInputContent.EncryptedContent("encrypted"),
            ),
        )

        val encoded = stableEventJson.encodeToString(event)
        val element = stableEventJson.parseToJsonElement(encoded).jsonObject

        assertEquals(setOf("type", "author", "recipient", "content"), element.keys)
        assertEquals(event, stableEventJson.decodeFromString<StableCleanEvent>(encoded))
        assertEquals(
            listOf(
                ResponseItem.AgentMessage(
                    author = "/root/worker",
                    recipient = "/root",
                    content = listOf(
                        AgentMessageInputContent.InputText("Message metadata"),
                        AgentMessageInputContent.EncryptedContent("encrypted"),
                    ),
                ),
            ),
            event.toResponseHistoryItems(),
        )
    }

    test("round trips hosted completed tools through the stable union") {
        val serverSearchCall = ResponseItem.ServerToolSearchCall(
            status = "completed",
            arguments = kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        val serverSearchOutput = ResponseItem.ServerToolSearchOutput(
            status = "completed",
            tools = emptyList(),
        )
        val webSearch = ResponseItem.WebSearchCall(status = "completed")
        val imageGeneration = ResponseItem.ImageGenerationCall(
            status = "completed",
            result = "base64",
        )
        val events: List<StableCleanEvent.CompletedTool> = listOf(
            StableCleanEvent.ServerToolSearch(serverSearchCall, serverSearchOutput),
            StableCleanEvent.WebSearchCall(webSearch),
            StableCleanEvent.ImageGenerationCall(imageGeneration),
        )

        events.forEach { event ->
            val encoded = stableEventJson.encodeToString<StableCleanEvent>(event)

            assertEquals(
                event,
                stableEventJson.decodeFromString<StableCleanEvent>(encoded),
            )
        }
        assertEquals(
            listOf(serverSearchCall, serverSearchOutput),
            events.first().toResponseHistoryItems(),
        )
        assertEquals(listOf(webSearch), events[1].toResponseHistoryItems())
        assertEquals(listOf(imageGeneration), events[2].toResponseHistoryItems())
    }

    test("round trips clean compaction checkpoint with stable prefix") {
        val compaction = ResponseItem.Compaction(encryptedContent = "encrypted")
        val checkpoint = CleanCompactionCheckpoint(
            prefix = listOf(
                StableCleanEvent.UserMessage(
                    listOf(ContentItem.InputText("retained")),
                ),
            ),
            compaction = compaction,
            historyBaseIndex = 12,
            windowNumber = 3,
            firstWindowId = "window-1",
            previousWindowId = "window-2",
            windowId = "window-3",
        )

        val encoded = stableEventJson.encodeToString(checkpoint)

        assertEquals(
            checkpoint,
            stableEventJson.decodeFromString<CleanCompactionCheckpoint>(encoded),
        )
        assertEquals(
            listOf(
                ResponseItem.Message(
                    role = MessageRole.User,
                    content = listOf(ContentItem.InputText("retained")),
                ),
                compaction,
            ),
            checkpoint.toResponseHistoryItems(),
        )
    }
}
