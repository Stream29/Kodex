package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageGenerationCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWorkEvent
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

private val stableEventJson = Json

val stableCleanEventSerializationTest by testSuite {
    test("round trips exact message metadata through the index union") {
        val item = ResponseItem.Message(
            id = ResponseItemId("assistant_1"),
            role = MessageRole.Assistant,
            content = listOf(ContentItem.OutputText("done")),
            phase = MessagePhase.FinalAnswer,
        )
        val event: StableIndexEvent = StableAssistantMessage(
            content = item.content,
            id = item.id,
            phase = item.phase,
        )

        val encoded = stableEventJson.encodeToString(event)
        val element = stableEventJson.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("assistant_message"), element["type"])
        assertEquals(setOf("type", "content", "id", "phase"), element.keys)
        assertEquals(event, stableEventJson.decodeFromString<StableIndexEvent>(encoded))
        assertEquals(listOf(item), event.toResponseHistoryItems())
    }

    test("round trips content-only user and developer messages") {
        val events: List<StableIndexEvent> = listOf(
            StableUserMessage(
                listOf(ContentItem.InputText("user content")),
            ),
            StableDeveloperMessage(
                listOf(ContentItem.InputText("host context")),
            ),
        )

        events.forEach { event ->
            val encoded = stableEventJson.encodeToString(event)

            assertEquals(
                setOf("type", "content"),
                stableEventJson.parseToJsonElement(encoded).jsonObject.keys,
            )
            assertEquals(event, stableEventJson.decodeFromString<StableIndexEvent>(encoded))
        }
    }

    test("round trips flattened inter-agent fields") {
        val event: StableIndexEvent = StableAgentMessage(
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
        assertEquals(event, stableEventJson.decodeFromString<StableIndexEvent>(encoded))
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

    test("round trips hosted completed tools through the work union") {
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
        val events: List<StableWorkEvent.CompletedTool> = listOf(
            StableServerToolSearch(serverSearchCall, serverSearchOutput),
            StableWebSearchCall(webSearch),
            StableImageGenerationCall(imageGeneration),
        )

        events.forEach { event ->
            val encoded = stableEventJson.encodeToString<StableWorkEvent>(event)

            assertEquals(
                event,
                stableEventJson.decodeFromString<StableWorkEvent>(encoded),
            )
        }
        assertEquals(
            listOf(serverSearchCall, serverSearchOutput),
            events.first().toResponseHistoryItems(),
        )
        assertEquals(listOf(webSearch), events[1].toResponseHistoryItems())
        assertEquals(listOf(imageGeneration), events[2].toResponseHistoryItems())
    }

    test("round trips index events and compaction points through the index union") {
        val entries: List<CleanIndexEntry> = listOf(
            StableUserMessage(
                listOf(ContentItem.InputText("retained")),
            ),
            StableAssistantMessage(
                listOf(ContentItem.OutputText("answer")),
            ),
            CleanCompactionPoint,
        )

        entries.forEach { entry ->
            val encoded = stableEventJson.encodeToString<CleanIndexEntry>(entry)

            assertEquals(
                entry,
                stableEventJson.decodeFromString<CleanIndexEntry>(encoded),
            )
        }
        assertIs<StableIndexEvent>(entries[0])
        assertIs<StableIndexEvent>(entries[1])
        assertFalse(entries[2] is StableCleanEvent)
        assertEquals(
            JsonPrimitive("compaction_point"),
            stableEventJson
                .parseToJsonElement(
                    stableEventJson.encodeToString<CleanIndexEntry>(entries[2]),
                )
                .jsonObject["type"],
        )
    }

    test("round trips context compaction through the work union") {
        val compaction = ResponseItem.Compaction(encryptedContent = "encrypted")
        val contextCompaction: StableWorkEvent = StableContextCompaction(
            id = compaction.id,
            encryptedContent = compaction.encryptedContent,
        )

        val encoded = stableEventJson.encodeToString<StableWorkEvent>(contextCompaction)

        assertEquals(
            contextCompaction,
            stableEventJson.decodeFromString<StableWorkEvent>(encoded),
        )
        assertEquals(
            listOf(compaction),
            contextCompaction.toResponseHistoryItems(),
        )
    }
}
