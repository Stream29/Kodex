package io.github.stream29.codex.lite.openai.client

import io.github.stream29.codex.lite.openai.*
import io.github.stream29.codex.lite.tool.contract.FreeformTool
import io.github.stream29.codex.lite.tool.contract.FreeformToolFormat
import io.github.stream29.codex.lite.tool.contract.ResponsesApiNamespace
import io.github.stream29.codex.lite.tool.contract.ResponsesApiWebSearchFilters
import io.github.stream29.codex.lite.tool.contract.ResponsesApiWebSearchUserLocation
import io.github.stream29.codex.lite.tool.contract.ResponsesApiTool
import io.github.stream29.codex.lite.tool.contract.ToolSpec
import io.github.stream29.codex.lite.tool.contract.WebSearchContextSize
import kotlinx.schema.json.AdditionalPropertiesConstraint
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAiSubscriptionSerializationTest {
    private val json = OpenAiJson.default

    @Test
    fun responseItemUsesTaggedPolymorphicShape() {
        val request = ResponsesApiRequest(
            model = "test-model",
            input = listOf(
                ResponseItem.Message(
                    role = MessageRole.User,
                    content = listOf(ContentItem.InputText("hello")),
                ),
            ),
        )

        val encoded = OpenAiJson.default
            .parseToJsonElement(OpenAiJson.default.encodeToString(request))
            .jsonObject
        val item = encoded["input"]?.jsonArray?.single()?.jsonObject

        assertEquals(JsonPrimitive("message"), item?.get("type"))
        assertEquals(JsonPrimitive("user"), item?.get("role"))
        assertEquals(JsonPrimitive("input_text"), item?.get("content")?.jsonArray?.single()?.jsonObject?.get("type"))
    }

    @Test
    fun requestUsesExplicitWireNames() {
        val request = ResponsesApiRequest(
            model = "test-model",
            input = textInput("hello"),
            previousResponseId = "resp_1",
            toolChoice = ToolChoice.Required,
            parallelToolCalls = true,
            serviceTier = "flex",
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

    @Test
    fun responsesApiRequestForcesStreamWireShape() {
        val request = ResponsesApiRequest(
            model = "test-model",
            input = textInput("hello"),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals(JsonPrimitive(true), encoded["stream"])
    }

    @Test
    fun functionCallOutputPayloadCanBeText() {
        val item = ResponseItem.FunctionCallOutput(
            callId = "call_1",
            output = FunctionCallOutputPayload.fromText("ok"),
        )

        val encoded = OpenAiJson.default
            .parseToJsonElement(OpenAiJson.default.encodeToString<ResponseItem>(item))
            .jsonObject

        assertEquals(JsonPrimitive("function_call_output"), encoded["type"])
        assertEquals(JsonPrimitive("ok"), encoded["output"])
    }

    @Test
    fun functionCallOutputPayloadCanBeContentItems() {
        val item = ResponseItem.FunctionCallOutput(
            callId = "call_1",
            output = FunctionCallOutputPayload.fromContentItems(
                listOf(FunctionCallOutputContentItem.InputText("note")),
            ),
        )

        val encoded = OpenAiJson.default
            .parseToJsonElement(OpenAiJson.default.encodeToString<ResponseItem>(item))
            .jsonObject

        assertEquals(
            JsonArray(
                listOf(
                    OpenAiJson.default.parseToJsonElement(
                        """{"type":"input_text","text":"note"}""",
                    ),
                ),
            ),
            encoded["output"],
        )
    }

    @Test
    fun functionCallOutputPayloadDecodesText() {
        val item = OpenAiJson.default.decodeFromString<ResponseItem>(
            """{"type":"function_call_output","call_id":"call_1","output":"ok"}""",
        )

        val output = (item as ResponseItem.FunctionCallOutput).output.body
        assertEquals("ok", (output as FunctionCallOutputBody.Text).text)
    }

    @Test
    fun functionCallOutputPayloadDecodesContentItems() {
        val item = OpenAiJson.default.decodeFromString<ResponseItem>(
            """{"type":"function_call_output","call_id":"call_1","output":[{"type":"input_text","text":"note"}]}""",
        )

        val output = (item as ResponseItem.FunctionCallOutput).output.body
        val contentItem = (output as FunctionCallOutputBody.ContentItems).items.single()
        assertEquals("note", (contentItem as FunctionCallOutputContentItem.InputText).text)
    }

    @Test
    fun mcpToolCallOutputSerializesRawProtocolShape() {
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

    @Test
    fun responsesApiRequestKeepsMcpToolCallOutputShape() {
        val item = ResponseItem.McpToolCallOutput(
            callId = "call_1",
            output = CallToolResult(
                content = listOf(json.parseToJsonElement("""{"type":"text","text":"ignored"}""")),
                structuredContent = json.parseToJsonElement("""{"answer":1}"""),
                isError = false,
            ),
        )

        val request = ResponsesApiRequest(
            model = "test-model",
            input = listOf(item),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val encodedItem = encoded["input"]?.jsonArray?.single()?.jsonObject ?: error("missing input item")

        assertEquals(JsonPrimitive("mcp_tool_call_output"), encodedItem["type"])
        assertEquals(JsonPrimitive("call_1"), encodedItem["call_id"])
        assertEquals(json.parseToJsonElement("""{"answer":1}"""), encodedItem["output"]?.jsonObject?.get("structuredContent"))
    }

    @Test
    fun mcpToolCallOutputImageContentConvertsToFunctionCallOutputContentItems() {
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

    @Test
    fun mcpToolCallOutputTextOnlyContentConvertsToJsonText() {
        val output = CallToolResult(
            content = listOf(json.parseToJsonElement("""{"type":"text","text":"ok"}""")),
        )

        val converted = output.toFunctionCallOutputPayload(json)
        val body = converted.body as FunctionCallOutputBody.Text

        assertEquals("""[{"type":"text","text":"ok"}]""", body.text)
        assertEquals(true, converted.success)
    }

    @Test
    fun compactionSummaryDecodesAsTaggedVariant() {
        val item = OpenAiJson.default.decodeFromString<ResponseItem>(
            """{"type":"compaction_summary","encrypted_content":"enc"}""",
        )

        assertEquals("enc", (item as ResponseItem.CompactionSummary).encryptedContent)
    }

    @Test
    fun streamEventDecodesAsTaggedVariant() {
        val event = OpenAiJson.default.decodeFromString<ResponsesStreamEvent>(
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

    @Test
    fun completedStreamEventDecodesRawResponseShape() {
        val event = OpenAiJson.default.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.completed","response":{"id":"resp_1","end_turn":true}}""",
        )

        val response = (event as ResponsesStreamEvent.Completed).response
        assertEquals("resp_1", response.id)
        assertEquals(true, response.endTurn)
    }

    @Test
    fun metadataStreamEventAllowsMissingOptionalWireFields() {
        val event = OpenAiJson.default.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.metadata"}""",
        )

        val metadata = event as ResponsesStreamEvent.Metadata
        assertNull(metadata.responseId)
        assertNull(metadata.headers)
        assertNull(metadata.metadata)
    }

    @Test
    fun customToolInputDeltaAllowsCallIdAsFallbackIdentifier() {
        val event = OpenAiJson.default.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.custom_tool_call_input.delta","call_id":"call_1","delta":"abc"}""",
        )

        val delta = event as ResponsesStreamEvent.ToolCallInputDelta
        assertNull(delta.itemId)
        assertEquals("call_1", delta.callId)
        assertEquals("abc", delta.delta)
    }

    @Test
    fun failedResponseAllowsPartialErrorPayload() {
        val event = OpenAiJson.default.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.failed","response":{"error":{"code":"invalid_prompt"}}}""",
        )

        val error = (event as ResponsesStreamEvent.Failed).response.error ?: error("missing response error")
        assertNull(error.message)
        assertEquals("invalid_prompt", error.code)
        assertNull(error.type)
    }

    @Test
    fun contentPartStreamEventDecodesRawPartShape() {
        val event = OpenAiJson.default.decodeFromString<ResponsesStreamEvent>(
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

    @Test
    fun outputTextDoneStreamEventDecodesRawTextShape() {
        val event = OpenAiJson.default.decodeFromString<ResponsesStreamEvent>(
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

    @Test
    fun agentMessageInputTextDecodesAsTaggedVariant() {
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

    @Test
    fun reasoningTextContentDecodesAsTaggedVariant() {
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

        val content = (item as ResponseItem.Reasoning).content.orEmpty()
        assertEquals("plain", (content[0] as ReasoningItemContent.Text).text)
        assertEquals("hidden", (content[1] as ReasoningItemContent.ReasoningText).text)
    }

    @Test
    fun localShellCallDecodesStrictActionShape() {
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

    @Test
    fun webSearchCallDecodesStrictActionShapes() {
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

    @Test
    fun unknownResponseItemDecodesAsOther() {
        val item = json.decodeFromString<ResponseItem>(
            """{"type":"future_item","value":1}""",
        )

        assertEquals(ResponseItem.Other, item)
    }

    @Test
    fun unknownWebSearchActionDecodesAsOther() {
        val item = json.decodeFromString<ResponseItem>(
            """{"type":"web_search_call","status":"completed","action":{"type":"future_action","value":1}}""",
        )

        assertEquals(WebSearchAction.Other, (item as ResponseItem.WebSearchCall).action)
    }

    @Test
    fun functionToolSerializesExpectedWireShape() {
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

    @Test
    fun functionToolSerializesOutputSchemaWhenPresent() {
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

    @Test
    fun namespaceToolSerializesExpectedWireShape() {
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

    @Test
    fun toolSearchToolSerializesExpectedWireShape() {
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

    @Test
    fun toolSearchOutputSerializesLoadableToolsExpectedWireShape() {
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

    @Test
    fun toolSearchOutputDecodesLoadableTools() {
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

    @Test
    fun webSearchToolSerializesExpectedWireShape() {
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

    @Test
    fun customToolSerializesExpectedWireShape() {
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

    @Test
    fun imageGenerationToolSerializesExpectedWireShape() {
        val tool = ToolSpec.ImageGeneration(outputFormat = "png")

        assertEquals(
            json.parseToJsonElement("""{"type":"image_generation","output_format":"png"}"""),
            encodeTool(tool),
        )
    }

    private fun encodeTool(tool: ToolSpec): JsonElement =
        json.parseToJsonElement(json.encodeToString<ToolSpec>(tool))
}
