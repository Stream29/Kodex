package io.github.stream29.codex.lite.openai.client

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.*
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.schema.json.AdditionalPropertiesConstraint
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull



private val json = OpenAiJsonCodec

private fun encodeTool(tool: ToolSpec): JsonElement =
    json.parseToJsonElement(json.encodeToString<ToolSpec>(tool))

val openAiSubscriptionSerializationTest by testSuite {
    test("response item uses tagged polymorphic shape") {
        val request = ResponsesApiRequest(
            model = OpenAiModelId("test-model"),
            input = listOf(
                ResponseItem.Message(
                    role = MessageRole.User,
                    content = listOf(ContentItem.InputText("hello")),
                ),
            ),
        )

        val encoded = json
            .parseToJsonElement(json.encodeToString(request))
            .jsonObject
        val item = encoded["input"]?.jsonArray?.single()?.jsonObject

        assertEquals(JsonPrimitive("message"), item?.get("type"))
        assertEquals(JsonPrimitive("user"), item?.get("role"))
        assertEquals(JsonPrimitive("input_text"), item?.get("content")?.jsonArray?.single()?.jsonObject?.get("type"))
    }

    test("request uses explicit wire names") {
        val request = ResponsesApiRequest(
            model = OpenAiModelId("test-model"),
            input = listOf(
                ResponseItem.Message(
                    role = MessageRole.User,
                    content = listOf(ContentItem.InputText("hello")),
                ),
            ),
            previousResponseId = "resp_1",
            toolChoice = ToolChoice.Required,
            parallelToolCalls = true,
            serviceTier = ServiceTier.Flex,
            promptCacheKey = "cache-key",
            clientMetadata = mapOf("client" to "test"),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals(JsonPrimitive("resp_1"), encoded["previous_response_id"])
        assertEquals(JsonPrimitive("required"), encoded["tool_choice"])
        assertEquals(JsonPrimitive(true), encoded["parallel_tool_calls"])
        assertEquals(JsonPrimitive("flex"), encoded["service_tier"])
        assertEquals(JsonPrimitive("cache-key"), encoded["prompt_cache_key"])
        assertEquals(JsonPrimitive("test"), encoded["client_metadata"]?.jsonObject?.get("client"))
    }

    test("responses api request forces stream wire shape") {
        val request = ResponsesApiRequest(
            model = OpenAiModelId("test-model"),
            input = listOf(
                ResponseItem.Message(
                    role = MessageRole.User,
                    content = listOf(ContentItem.InputText("hello")),
                ),
            ),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals(JsonPrimitive(true), encoded["stream"])
    }

    test("responses api request omits default optional controls") {
        val request = ResponsesApiRequest(
            model = OpenAiModelId("test-model"),
            input = listOf(
                ResponseItem.Message(
                    role = MessageRole.User,
                    content = listOf(ContentItem.InputText("hello")),
                ),
            ),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertFalse("instructions" in encoded)
        assertFalse("reasoning" in encoded)
        assertFalse("service_tier" in encoded)
        assertFalse("text" in encoded)
    }

    test("function call output payload can be text") {
        val item = ResponseItem.FunctionCallOutput(
            callId = "call_1",
            output = FunctionCallOutputPayload.fromText("ok"),
        )

        val encoded = json
            .parseToJsonElement(json.encodeToString<ResponseItem>(item))
            .jsonObject

        assertEquals(JsonPrimitive("function_call_output"), encoded["type"])
        assertEquals(JsonPrimitive("ok"), encoded["output"])
    }

    test("function call output payload can be content items") {
        val item = ResponseItem.FunctionCallOutput(
            callId = "call_1",
            output = FunctionCallOutputPayload.fromContentItems(
                listOf(FunctionCallOutputContentItem.InputText("note")),
            ),
        )

        val encoded = json
            .parseToJsonElement(json.encodeToString<ResponseItem>(item))
            .jsonObject

        assertEquals(
            JsonArray(
                listOf(
                    json.parseToJsonElement(
                        """{"type":"input_text","text":"note"}""",
                    ),
                ),
            ),
            encoded["output"],
        )
    }

    test("function call output payload decodes text") {
        val item = json.decodeFromString<ResponseItem>(
            """{"type":"function_call_output","call_id":"call_1","output":"ok"}""",
        )

        val output = (item as ResponseItem.FunctionCallOutput).output.body
        assertEquals("ok", (output as FunctionCallOutputBody.Text).text)
    }

    test("function call output payload decodes content items") {
        val item = json.decodeFromString<ResponseItem>(
            """{"type":"function_call_output","call_id":"call_1","output":[{"type":"input_text","text":"note"}]}""",
        )

        val output = (item as ResponseItem.FunctionCallOutput).output.body
        val contentItem = (output as FunctionCallOutputBody.ContentItems).items.single()
        assertEquals("note", (contentItem as FunctionCallOutputContentItem.InputText).text)
    }

    test("mcp tool call output serializes raw protocol shape") {
        val item = ResponseItem.McpToolCallOutput(
            callId = "call_1",
            output = CallToolResult(
                content = listOf(json.parseToJsonElement("""{"type":"text","text":"ok"}""")),
                structuredContent = json.parseToJsonElement("""{"answer":1}"""),
                isError = false,
                meta = json.parseToJsonElement("""{"trace":"t"}"""),
            ),
        )

        val encoded = json.parseToJsonElement(json.encodeToString<ResponseItem>(item)).jsonObject

        assertEquals(JsonPrimitive("mcp_tool_call_output"), encoded["type"])
        assertEquals(JsonPrimitive("call_1"), encoded["call_id"])
        val output = encoded["output"]?.jsonObject ?: error("missing output")
        assertEquals(JsonPrimitive(false), output["isError"])
        assertEquals(json.parseToJsonElement("""{"answer":1}"""), output["structuredContent"])
        assertEquals(json.parseToJsonElement("""{"trace":"t"}"""), output["_meta"])
    }

    test("responses api request keeps mcp tool call output shape") {
        val item = ResponseItem.McpToolCallOutput(
            callId = "call_1",
            output = CallToolResult(
                content = listOf(json.parseToJsonElement("""{"type":"text","text":"ignored"}""")),
                structuredContent = json.parseToJsonElement("""{"answer":1}"""),
                isError = false,
            ),
        )

        val request = ResponsesApiRequest(
            model = OpenAiModelId("test-model"),
            input = listOf(item),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val encodedItem = encoded["input"]?.jsonArray?.single()?.jsonObject ?: error("missing input item")

        assertEquals(JsonPrimitive("mcp_tool_call_output"), encodedItem["type"])
        assertEquals(JsonPrimitive("call_1"), encodedItem["call_id"])
        assertEquals(json.parseToJsonElement("""{"answer":1}"""), encodedItem["output"]?.jsonObject?.get("structuredContent"))
    }

    test("mcp tool call output image content converts to function call output content items") {
        val item = ResponseItem.McpToolCallOutput(
            callId = "call_1",
            output = CallToolResult(
                content = listOf(
                    json.parseToJsonElement("""{"type":"text","text":"caption"}"""),
                    json.parseToJsonElement(
                        """
                            {
                              "type": "image",
                              "data": "BASE64",
                              "mimeType": "image/png",
                              "_meta": {"codex/imageDetail": "original"}
                            }
                        """.trimIndent(),
                    ),
                ),
                isError = true,
            ),
        )

        val converted = item.output.toFunctionCallOutputPayload(json)
        val body = converted.body as FunctionCallOutputBody.ContentItems

        assertEquals(false, converted.success)
        assertEquals("caption", (body.items[0] as FunctionCallOutputContentItem.InputText).text)
        val image = body.items[1] as FunctionCallOutputContentItem.InputImage
        assertEquals("data:image/png;base64,BASE64", image.imageUrl)
        assertEquals(ImageDetail.Original, image.detail)
    }

    test("mcp tool call output text only content converts to json text") {
        val output = CallToolResult(
            content = listOf(json.parseToJsonElement("""{"type":"text","text":"ok"}""")),
        )

        val converted = output.toFunctionCallOutputPayload(json)
        val body = converted.body as FunctionCallOutputBody.Text

        assertEquals("""[{"type":"text","text":"ok"}]""", body.text)
        assertEquals(true, converted.success)
    }

    test("compaction summary decodes as tagged variant") {
        val item = json.decodeFromString<ResponseItem>(
            """{"type":"compaction_summary","encrypted_content":"enc"}""",
        )

        assertEquals("enc", (item as ResponseItem.CompactionSummary).encryptedContent)
    }

    test("stream event decodes as tagged variant") {
        val event = json.decodeFromString<ResponsesStreamEvent>(
            """
                {
                  "type":"response.output_text.delta",
                  "item_id":"msg_1",
                  "output_index":0,
                  "content_index":0,
                  "delta":"hi"
                }
            """.trimIndent(),
        )

        assertEquals("hi", (event as ResponsesStreamEvent.OutputTextDelta).delta)
    }

    test("completed stream event decodes raw response shape") {
        val event = json.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.completed","response":{"id":"resp_1","end_turn":true}}""",
        )

        val response = (event as ResponsesStreamEvent.Completed).response
        assertEquals("resp_1", response.id)
        assertEquals(true, response.endTurn)
    }

    test("completed stream event decodes token usage counters") {
        val event = json.decodeFromString<ResponsesStreamEvent>(
            """
                {
                  "type": "response.completed",
                  "response": {
                    "id": "resp_1",
                    "usage": {
                      "input_tokens": 1,
                      "output_tokens": 2,
                      "total_tokens": 3
                    }
                  }
                }
            """.trimIndent(),
        )

        val usage = (event as ResponsesStreamEvent.Completed).response.usage ?: error("missing usage")
        assertEquals(1, usage.inputTokens)
        assertEquals(2, usage.outputTokens)
        assertEquals(3, usage.totalTokens)
    }

    test("metadata stream event allows missing optional wire fields") {
        val event = json.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.metadata"}""",
        )

        val metadata = event as ResponsesStreamEvent.Metadata
        assertNull(metadata.responseId)
        assertNull(metadata.headers)
        assertNull(metadata.metadata)
    }

    test("custom tool input delta allows call id as fallback identifier") {
        val event = json.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.custom_tool_call_input.delta","call_id":"call_1","delta":"abc"}""",
        )

        val delta = event as ResponsesStreamEvent.ToolCallInputDelta
        assertNull(delta.itemId)
        assertEquals("call_1", delta.callId)
        assertEquals("abc", delta.delta)
    }

    test("failed response allows partial error payload") {
        val event = json.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.failed","response":{"error":{"code":"invalid_prompt"}}}""",
        )

        val error = (event as ResponsesStreamEvent.Failed).response.error ?: error("missing response error")
        assertNull(error.message)
        assertEquals("invalid_prompt", error.code)
        assertNull(error.type)
    }

    test("content part stream event decodes raw part shape") {
        val event = json.decodeFromString<ResponsesStreamEvent>(
            """
                {
                  "type":"response.content_part.added",
                  "item_id":"msg_1",
                  "output_index":0,
                  "content_index":0,
                  "part":{"type":"output_text","text":"hi"}
                }
            """.trimIndent(),
        )

        val part = (event as ResponsesStreamEvent.ContentPartAdded).part
        assertEquals("hi", (part as ContentItem.OutputText).text)
    }

    test("output text done stream event decodes raw text shape") {
        val event = json.decodeFromString<ResponsesStreamEvent>(
            """
                {
                  "type":"response.output_text.done",
                  "item_id":"msg_1",
                  "output_index":0,
                  "content_index":0,
                  "text":"hi"
                }
            """.trimIndent(),
        )

        assertEquals("hi", (event as ResponsesStreamEvent.OutputTextDone).text)
    }

    test("agent message input text decodes as tagged variant") {
        val item = json.decodeFromString<ResponseItem>(
            """
                {
                  "type": "agent_message",
                  "author": "agent",
                  "recipient": "user",
                  "content": [
                    {"type": "input_text", "text": "hello"}
                  ]
                }
            """.trimIndent(),
        )

        val content = (item as ResponseItem.AgentMessage).content.single()
        assertEquals("hello", (content as AgentMessageInputContent.InputText).text)
    }

    test("reasoning text content decodes as tagged variant") {
        val item = json.decodeFromString<ResponseItem>(
            """
                {
                  "type": "reasoning",
                  "summary": [],
                  "content": [
                    {"type": "text", "text": "plain"},
                    {"type": "reasoning_text", "text": "hidden"}
                  ],
                  "encrypted_content": null
                }
            """.trimIndent(),
        )

        val reasoning = item as ResponseItem.Reasoning
        assertNull(reasoning.id)
        val content = reasoning.content.orEmpty()
        assertEquals("plain", (content[0] as ReasoningItemContent.Text).text)
        assertEquals("hidden", (content[1] as ReasoningItemContent.ReasoningText).text)
    }

    test("reasoning default id is not encoded") {
        val item = ResponseItem.Reasoning(summary = emptyList())

        val encoded = json.parseToJsonElement(json.encodeToString<ResponseItem>(item)).jsonObject

        assertNull(encoded["id"])
    }

    test("local shell call decodes strict action shape") {
        val item = json.decodeFromString<ResponseItem>(
            """
                {
                  "type": "local_shell_call",
                  "call_id": "call_1",
                  "status": "completed",
                  "action": {
                    "type": "exec",
                    "command": ["bash", "-lc", "pwd"],
                    "timeout_ms": 1000,
                    "working_directory": "/tmp",
                    "env": {"A": "B"},
                    "user": "stream"
                  }
                }
            """.trimIndent(),
        )

        val call = item as ResponseItem.LocalShellCall
        val action = call.action as LocalShellAction.Exec
        assertEquals(LocalShellStatus.Completed, call.status)
        assertEquals(listOf("bash", "-lc", "pwd"), action.command)
        assertEquals(1000, action.timeoutMs)
        assertEquals("/tmp", action.workingDirectory)
        assertEquals(mapOf("A" to "B"), action.env)
        assertEquals("stream", action.user)
    }

    test("web search call decodes strict action shapes") {
        val search = json.decodeFromString<ResponseItem>(
            """{"type":"web_search_call","status":"completed","action":{"type":"search","query":"weather","queries":["a","b"]}}""",
        ) as ResponseItem.WebSearchCall
        val openPage = json.decodeFromString<ResponseItem>(
            """{"type":"web_search_call","status":"completed","action":{"type":"open_page","url":"https://example.com"}}""",
        ) as ResponseItem.WebSearchCall
        val findInPage = json.decodeFromString<ResponseItem>(
            """{"type":"web_search_call","status":"completed","action":{"type":"find_in_page","url":"https://example.com","pattern":"needle"}}""",
        ) as ResponseItem.WebSearchCall

        assertEquals("weather", (search.action as WebSearchAction.Search).query)
        assertEquals("https://example.com", (openPage.action as WebSearchAction.OpenPage).url)
        assertEquals("needle", (findInPage.action as WebSearchAction.FindInPage).pattern)
    }

    test("unknown response item decodes as other") {
        val item = json.decodeFromString<ResponseItem>(
            """{"type":"future_item","value":1}""",
        )

        assertEquals(ResponseItem.Other, item)
    }

    test("unknown web search action decodes as other") {
        val item = json.decodeFromString<ResponseItem>(
            """{"type":"web_search_call","status":"completed","action":{"type":"future_action","value":1}}""",
        )

        assertEquals(WebSearchAction.Other, (item as ResponseItem.WebSearchCall).action)
    }

    test("function tool serializes expected wire shape") {
        val tool = ResponsesApiTool(
            name = "demo",
            description = "A demo tool",
            parameters = ObjectPropertyDefinition(
                properties = mapOf("foo" to StringPropertyDefinition()),
            ),
        )

        assertEquals(
            json.parseToJsonElement(
                """
                    {
                      "type": "function",
                      "name": "demo",
                      "description": "A demo tool",
                      "strict": false,
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "foo": { "type": "string" }
                        }
                      }
                    }
                """.trimIndent(),
            ),
            encodeTool(tool),
        )
    }

    test("function tool serializes output schema when present") {
        val tool = ResponsesApiTool(
            name = "view_image",
            description = "View an image",
            parameters = ObjectPropertyDefinition(),
            outputSchema = ObjectPropertyDefinition(
                properties = mapOf("image_url" to StringPropertyDefinition()),
            ),
        )

        assertEquals(
            json.parseToJsonElement(
                """
                    {
                      "type": "function",
                      "name": "view_image",
                      "description": "View an image",
                      "strict": false,
                      "parameters": { "type": "object" },
                      "output_schema": {
                        "type": "object",
                        "properties": {
                          "image_url": { "type": "string" }
                        }
                      }
                    }
                """.trimIndent(),
            ),
            encodeTool(tool),
        )
    }

    test("namespace tool serializes expected wire shape") {
        val tool = ResponsesApiNamespace(
            name = "mcp__demo__",
            description = "Demo tools",
            tools = listOf(
                ResponsesApiTool(
                    name = "lookup_order",
                    description = "Look up an order",
                    parameters = ObjectPropertyDefinition(
                        properties = mapOf("order_id" to StringPropertyDefinition()),
                    ),
                ),
            ),
        )

        assertEquals(
            json.parseToJsonElement(
                """
                    {
                      "type": "namespace",
                      "name": "mcp__demo__",
                      "description": "Demo tools",
                      "tools": [
                        {
                          "type": "function",
                          "name": "lookup_order",
                          "description": "Look up an order",
                          "strict": false,
                          "parameters": {
                            "type": "object",
                            "properties": {
                              "order_id": { "type": "string" }
                            }
                          }
                        }
                      ]
                    }
                """.trimIndent(),
            ),
            encodeTool(tool),
        )
    }

    test("tool search tool serializes expected wire shape") {
        val tool = ToolSpec.ToolSearch(
            execution = "sync",
            description = "Search app tools",
            parameters = ObjectPropertyDefinition(
                properties = mapOf("query" to StringPropertyDefinition(description = "Tool search query")),
                required = listOf("query"),
                additionalProperties = AdditionalPropertiesConstraint.deny(),
            ),
        )

        assertEquals(
            json.parseToJsonElement(
                """
                    {
                      "type": "tool_search",
                      "execution": "sync",
                      "description": "Search app tools",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "query": {
                            "type": "string",
                            "description": "Tool search query"
                          }
                        },
                        "required": ["query"],
                        "additionalProperties": false
                      }
                    }
                """.trimIndent(),
            ),
            encodeTool(tool),
        )
    }

    test("tool search output serializes loadable tools expected wire shape") {
        val item = ResponseItem.ToolSearchOutput(
            callId = "call_1",
            status = "completed",
            execution = "client",
            tools = listOf(
                ResponsesApiTool(
                    name = "lookup_order",
                    description = "Look up an order",
                    strict = false,
                    deferLoading = true,
                    parameters = ObjectPropertyDefinition(
                        properties = mapOf("order_id" to StringPropertyDefinition()),
                        required = listOf("order_id"),
                        additionalProperties = AdditionalPropertiesConstraint.deny(),
                    ),
                ),
                ResponsesApiNamespace(
                    name = "mcp__calendar",
                    description = "Calendar tools",
                    tools = listOf(
                        ResponsesApiTool(
                            name = "create_event",
                            description = "Create events",
                            strict = false,
                            deferLoading = true,
                            parameters = ObjectPropertyDefinition(
                                properties = mapOf("title" to StringPropertyDefinition()),
                                required = listOf("title"),
                                additionalProperties = AdditionalPropertiesConstraint.deny(),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            json.parseToJsonElement(
                """
                    {
                      "type": "tool_search_output",
                      "call_id": "call_1",
                      "status": "completed",
                      "execution": "client",
                      "tools": [
                        {
                          "type": "function",
                          "name": "lookup_order",
                          "description": "Look up an order",
                          "strict": false,
                          "defer_loading": true,
                          "parameters": {
                            "type": "object",
                            "properties": {
                              "order_id": { "type": "string" }
                            },
                            "required": ["order_id"],
                            "additionalProperties": false
                          }
                        },
                        {
                          "type": "namespace",
                          "name": "mcp__calendar",
                          "description": "Calendar tools",
                          "tools": [
                            {
                              "type": "function",
                              "name": "create_event",
                              "description": "Create events",
                              "strict": false,
                              "defer_loading": true,
                              "parameters": {
                                "type": "object",
                                "properties": {
                                  "title": { "type": "string" }
                                },
                                "required": ["title"],
                                "additionalProperties": false
                              }
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent(),
            ),
            json.parseToJsonElement(json.encodeToString<ResponseItem>(item)),
        )
    }

    test("tool search output decodes loadable tools") {
        val item = json.decodeFromString<ResponseItem>(
            """
                {
                  "type": "tool_search_output",
                  "call_id": "call_1",
                  "status": "completed",
                  "execution": "client",
                  "tools": [
                    {
                      "type": "function",
                      "name": "lookup_order",
                      "description": "Look up an order",
                      "strict": false,
                      "defer_loading": true,
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "order_id": { "type": "string" }
                        },
                        "required": ["order_id"],
                        "additionalProperties": false
                      }
                    },
                    {
                      "type": "namespace",
                      "name": "mcp__calendar",
                      "description": "Calendar tools",
                      "tools": [
                        {
                          "type": "function",
                          "name": "create_event",
                          "description": "Create events",
                          "strict": false,
                          "defer_loading": true,
                          "parameters": {
                            "type": "object",
                            "properties": {
                              "title": { "type": "string" }
                            },
                            "required": ["title"],
                            "additionalProperties": false
                          }
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
        ) as ResponseItem.ToolSearchOutput

        assertEquals(
            ResponsesApiTool(
                name = "lookup_order",
                description = "Look up an order",
                strict = false,
                deferLoading = true,
                parameters = ObjectPropertyDefinition(
                    properties = mapOf("order_id" to StringPropertyDefinition()),
                    required = listOf("order_id"),
                    additionalProperties = AdditionalPropertiesConstraint.deny(),
                ),
            ),
            item.tools[0],
        )
        assertEquals(
            ResponsesApiNamespace(
                name = "mcp__calendar",
                description = "Calendar tools",
                tools = listOf(
                    ResponsesApiTool(
                        name = "create_event",
                        description = "Create events",
                        strict = false,
                        deferLoading = true,
                        parameters = ObjectPropertyDefinition(
                            properties = mapOf("title" to StringPropertyDefinition()),
                            required = listOf("title"),
                            additionalProperties = AdditionalPropertiesConstraint.deny(),
                        ),
                    ),
                ),
            ),
            item.tools[1],
        )
    }

    test("web search tool serializes expected wire shape") {
        val tool = ToolSpec.WebSearch(
            externalWebAccess = true,
            filters = ResponsesApiWebSearchFilters(allowedDomains = listOf("example.com")),
            userLocation = ResponsesApiWebSearchUserLocation(
                country = "US",
                region = "California",
                city = "San Francisco",
                timezone = "America/Los_Angeles",
            ),
            searchContextSize = WebSearchContextSize.High,
            searchContentTypes = listOf("text", "image"),
        )

        assertEquals(
            json.parseToJsonElement(
                """
                    {
                      "type": "web_search",
                      "external_web_access": true,
                      "filters": {
                        "allowed_domains": ["example.com"]
                      },
                      "user_location": {
                        "type": "approximate",
                        "country": "US",
                        "region": "California",
                        "city": "San Francisco",
                        "timezone": "America/Los_Angeles"
                      },
                      "search_context_size": "high",
                      "search_content_types": ["text", "image"]
                    }
                """.trimIndent(),
            ),
            encodeTool(tool),
        )
    }

    test("custom tool serializes expected wire shape") {
        val tool = FreeformTool(
            name = "apply_patch",
            description = "Apply a patch",
            format = FreeformToolFormat(
                type = "grammar",
                syntax = "lark",
                definition = "start: /.+/",
            ),
        )

        assertEquals(
            json.parseToJsonElement(
                """
                    {
                      "type": "custom",
                      "name": "apply_patch",
                      "description": "Apply a patch",
                      "format": {
                        "type": "grammar",
                        "syntax": "lark",
                        "definition": "start: /.+/"
                      }
                    }
                """.trimIndent(),
            ),
            encodeTool(tool),
        )
    }

    test("image generation tool serializes expected wire shape") {
        val tool = ToolSpec.ImageGeneration(outputFormat = "png")

        assertEquals(
            json.parseToJsonElement("""{"type":"image_generation","output_format":"png"}"""),
            encodeTool(tool),
        )
    }
}
