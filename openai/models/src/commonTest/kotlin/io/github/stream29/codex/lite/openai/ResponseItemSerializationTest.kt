package io.github.stream29.codex.lite.openai

import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ResponseItemSerializationTest {
    private val json = OpenAiJsonCodec

    @Test
    fun additionalToolsPreserveOpaqueToolDefinitions() {
        val item = ResponseItem.AdditionalTools(
            id = ResponseItemId("at_server"),
            role = "developer",
            tools = listOf(
                json.parseToJsonElement(
                    """{"type":"function","name":"lookup","x-provider-extension":true}""",
                ),
            ),
        )

        val encoded = json.parseToJsonElement(json.encodeToString<ResponseItem>(item)).jsonObject

        assertEquals(JsonPrimitive("additional_tools"), encoded["type"])
        assertEquals(JsonPrimitive("at_server"), encoded["id"])
        assertEquals(JsonPrimitive("developer"), encoded["role"])
        assertEquals(item, json.decodeFromString<ResponseItem>(json.encodeToString<ResponseItem>(item)))
    }

    @Test
    fun responseItemIdsAndCustomToolNamespaceRoundTrip() {
        val id = ResponseItemId("legacy-id")
        val output = FunctionCallOutputPayload.fromText("ok")
        val items = listOf<ResponseItem>(
            ResponseItem.Message(
                id = id,
                role = MessageRole.User,
                content = listOf(ContentItem.InputText("hello")),
            ),
            ResponseItem.Message(
                id = id,
                role = MessageRole.Developer,
                content = listOf(ContentItem.InputText("follow the workspace instructions")),
            ),
            ResponseItem.AgentMessage(
                id = id,
                author = "worker",
                recipient = "root",
                content = listOf(AgentMessageInputContent.InputText("done")),
            ),
            ResponseItem.Reasoning(id = id),
            ResponseItem.LocalShellCall(
                id = id,
                status = LocalShellStatus.Completed,
                action = LocalShellAction.Exec(command = listOf("pwd")),
            ),
            ResponseItem.FunctionCall(
                id = id,
                name = "lookup",
                arguments = "{}",
                callId = "call_1",
            ),
            ResponseItem.ToolSearchCall(
                id = id,
                execution = "client",
                arguments = json.parseToJsonElement("{}"),
            ),
            ResponseItem.FunctionCallOutput(id = id, callId = "call_1", output = output),
            ResponseItem.CustomToolCall(
                id = id,
                callId = "call_2",
                name = "apply_patch",
                namespace = "tools",
                input = "*** Begin Patch",
            ),
            ResponseItem.CustomToolCallOutput(id = id, callId = "call_2", output = output),
            ResponseItem.ToolSearchOutput(
                id = id,
                status = "completed",
                execution = "client",
                tools = emptyList(),
            ),
            ResponseItem.WebSearchCall(id = id),
            ResponseItem.ImageGenerationCall(id = id, status = "completed", result = "image"),
            ResponseItem.Compaction(id = id, encryptedContent = "encrypted"),
            ResponseItem.CompactionSummary(id = id, encryptedContent = "encrypted"),
            ResponseItem.ContextCompaction(id = id),
        )

        items.forEach { item ->
            assertEquals(item, json.decodeFromString<ResponseItem>(json.encodeToString<ResponseItem>(item)))
        }
    }
}
