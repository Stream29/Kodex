package io.github.stream29.kodex.openai

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse



private val json = OpenAiJsonCodec

val responseItemSerializationTest by testSuite {
    test("additional tools preserve opaque tool definitions") {
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
        assertFalse((item as ResponseItem) is ResponseItem.HistoryItem)
    }

    test("response item ids and custom tool namespace round trip") {
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
            ResponseItem.ClientToolSearchCall(
                id = id,
                callId = "call_search",
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
            ResponseItem.ClientToolSearchOutput(
                id = id,
                callId = "call_search",
                status = "completed",
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

    test("history item serializer preserves steerable items") {
        val items = listOf<ResponseItem.HistoryItem>(
            ResponseItem.Message(
                id = ResponseItemId("message_1"),
                role = MessageRole.User,
                content = listOf(ContentItem.InputText("continue")),
            ),
            ResponseItem.AgentMessage(
                id = ResponseItemId("agent_message_1"),
                author = "/root/worker",
                recipient = "/root",
                content = listOf(AgentMessageInputContent.InputText("done")),
            ),
        )

        items.forEach { item ->
            assertEquals(
                item,
                json.decodeFromString(
                    ResponseItem.HistoryItem.serializer(),
                    json.encodeToString(ResponseItem.HistoryItem.serializer(), item),
                ),
            )
        }
    }

    test("tool search execution selects a concrete response item type") {
        val clientCall = ResponseItem.ClientToolSearchCall(
            callId = "call_client",
            arguments = json.parseToJsonElement("""{"paths":["crm"]}"""),
        )
        val serverCall = ResponseItem.ServerToolSearchCall(
            arguments = json.parseToJsonElement("""{"paths":["crm"]}"""),
        )
        val clientOutput = ResponseItem.ClientToolSearchOutput(
            callId = "call_client",
            status = "completed",
            tools = emptyList(),
        )
        val serverOutput = ResponseItem.ServerToolSearchOutput(
            status = "completed",
            tools = emptyList(),
        )

        val clientCallWire = json.parseToJsonElement(json.encodeToString<ResponseItem>(clientCall)).jsonObject
        val serverCallWire = json.parseToJsonElement(json.encodeToString<ResponseItem>(serverCall)).jsonObject
        val clientOutputWire = json.parseToJsonElement(json.encodeToString<ResponseItem>(clientOutput)).jsonObject
        val serverOutputWire = json.parseToJsonElement(json.encodeToString<ResponseItem>(serverOutput)).jsonObject

        assertEquals(JsonPrimitive("tool_search_call"), clientCallWire["type"])
        assertEquals(JsonPrimitive("client"), clientCallWire["execution"])
        assertEquals(JsonPrimitive("call_client"), clientCallWire["call_id"])
        assertEquals(JsonPrimitive("tool_search_call"), serverCallWire["type"])
        assertEquals(JsonPrimitive("server"), serverCallWire["execution"])
        assertEquals(JsonNull, serverCallWire["call_id"])
        assertEquals(JsonPrimitive("tool_search_output"), clientOutputWire["type"])
        assertEquals(JsonPrimitive("client"), clientOutputWire["execution"])
        assertEquals(JsonPrimitive("call_client"), clientOutputWire["call_id"])
        assertEquals(JsonPrimitive("tool_search_output"), serverOutputWire["type"])
        assertEquals(JsonPrimitive("server"), serverOutputWire["execution"])
        assertEquals(JsonNull, serverOutputWire["call_id"])

        assertEquals(clientCall, json.decodeFromString<ResponseItem>(json.encodeToString<ResponseItem>(clientCall)))
        assertEquals(serverCall, json.decodeFromString<ResponseItem>(json.encodeToString<ResponseItem>(serverCall)))
        assertEquals(clientOutput, json.decodeFromString<ResponseItem>(json.encodeToString<ResponseItem>(clientOutput)))
        assertEquals(serverOutput, json.decodeFromString<ResponseItem>(json.encodeToString<ResponseItem>(serverOutput)))
    }

    test("hosted tool search accepts null call ids") {
        val call = json.decodeFromString<ResponseItem>(
            """{"type":"tool_search_call","execution":"server","call_id":null,"status":"completed","arguments":{"paths":["crm"]}}""",
        )
        val output = json.decodeFromString<ResponseItem>(
            """{"type":"tool_search_output","execution":"server","call_id":null,"status":"completed","tools":[]}""",
        )

        assertEquals(
            ResponseItem.ServerToolSearchCall(
                status = "completed",
                arguments = json.parseToJsonElement("""{"paths":["crm"]}"""),
            ),
            call,
        )
        assertEquals(
            ResponseItem.ServerToolSearchOutput(
                status = "completed",
                tools = emptyList(),
            ),
            output,
        )
    }

    test("tool search with an unknown execution is an unknown response item") {
        assertEquals(
            ResponseItem.Other,
            json.decodeFromString<ResponseItem>(
                """{"type":"tool_search_call","execution":"remote","arguments":{}}""",
            ),
        )
    }

    test("tool search rejects execution and call id contradictions") {
        assertEquals(
            ResponseItem.Other,
            json.decodeFromString<ResponseItem>(
                """{"type":"tool_search_call","execution":"client","call_id":null,"arguments":{}}""",
            ),
        )
        assertEquals(
            ResponseItem.Other,
            json.decodeFromString<ResponseItem>(
                """{"type":"tool_search_output","execution":"server","call_id":"call_unexpected","status":"completed","tools":[]}""",
            ),
        )
    }
}
