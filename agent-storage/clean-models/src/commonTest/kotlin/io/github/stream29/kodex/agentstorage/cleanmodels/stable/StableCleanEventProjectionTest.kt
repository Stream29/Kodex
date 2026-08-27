package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.CleanCompactionCheckpoint
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ReasoningItemContent
import io.github.stream29.kodex.openai.ReasoningItemReasoningSummary
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import kotlin.test.assertEquals

val stableCleanEventProjectionTest by testSuite {
    test("message and reasoning events project their OpenAI items") {
        val user = ResponseItem.Message(
            role = MessageRole.User,
            content = listOf(ContentItem.InputImage("data:image/png;base64,aW1hZ2U=")),
        )
        val assistant = ResponseItem.Message(
            id = ResponseItemId("assistant_1"),
            role = MessageRole.Assistant,
            content = listOf(ContentItem.OutputText("Done.")),
            phase = MessagePhase.FinalAnswer,
        )
        val developer = ResponseItem.Message(
            role = MessageRole.Developer,
            content = listOf(ContentItem.InputText("Host context.")),
        )
        val agent = ResponseItem.AgentMessage(
            author = "/root/worker",
            recipient = "/root",
            content = listOf(AgentMessageInputContent.EncryptedContent("encrypted")),
        )
        val reasoning = ResponseItem.Reasoning(
            id = ResponseItemId("reasoning_1"),
            summary = listOf(ReasoningItemReasoningSummary.SummaryText("Summary")),
            content = listOf(ReasoningItemContent.ReasoningText("Raw reasoning")),
            encryptedContent = "encrypted-reasoning",
        )

        val events = listOf(
            StableCleanEvent.UserMessage(user.content),
            StableCleanEvent.AssistantMessage(
                content = assistant.content,
                id = assistant.id,
                phase = assistant.phase,
            ),
            StableCleanEvent.DeveloperMessage(developer.content),
            StableCleanEvent.AgentMessage(
                author = agent.author,
                recipient = agent.recipient,
                content = agent.content,
            ),
            StableCleanEvent.Reasoning(reasoning),
        )

        assertEquals(
            listOf(user, assistant, developer, agent, reasoning),
            events.flatMap(StableCleanEvent::toResponseHistoryItems),
        )
        assertEquals("Done.", (events[1] as StableCleanEvent.AssistantMessage).text)
        assertEquals("Summary", (events[4] as StableCleanEvent.Reasoning).display)
    }

    test("hosted completed tools retain their concrete item types") {
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

        assertEquals(
            listOf(
                serverSearchCall,
                serverSearchOutput,
                webSearch,
                imageGeneration,
            ),
            events.flatMap(StableCleanEvent::toResponseHistoryItems),
        )
    }

    test("checkpoint prefix and stable compaction event project once in order") {
        val message = ResponseItem.Message(
            role = MessageRole.User,
            content = listOf(ContentItem.InputText("Retained.")),
        )
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")
        val contextCompaction = StableCleanEvent.ContextCompaction(
            id = compaction.id,
            encryptedContent = compaction.encryptedContent,
        )
        val checkpoint = CleanCompactionCheckpoint(
            prefix = listOf(StableCleanEvent.UserMessage(message.content)),
            historyBaseIndex = 9,
            windowNumber = 2,
            firstWindowId = "window-1",
            previousWindowId = "window-1",
            windowId = "window-2",
        )

        assertEquals(listOf(compaction), contextCompaction.toResponseHistoryItems())
        assertEquals(
            listOf(message),
            checkpoint.toResponseHistoryItems(),
        )
    }
}
