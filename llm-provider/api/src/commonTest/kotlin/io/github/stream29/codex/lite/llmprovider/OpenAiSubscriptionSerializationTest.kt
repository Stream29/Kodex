package io.github.stream29.codex.lite.llmprovider

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

class OpenAiSubscriptionSerializationTest {
    private val json = OpenAiSubscriptionLlmProvider.defaultJson

    @Test
    fun responseItemUsesTaggedPolymorphicShape() {
        val request = LlmResponseRequest(
            model = "test-model",
            input = listOf(
                LlmResponseItem.Message(
                    role = LlmMessageRole.User,
                    content = listOf(LlmContentItem.InputText("hello")),
                ),
            ),
        )

        val encoded = OpenAiSubscriptionLlmProvider.defaultJson
            .parseToJsonElement(OpenAiSubscriptionLlmProvider.defaultJson.encodeToString(request))
            .jsonObject
        val item = encoded["input"]?.jsonArray?.single()?.jsonObject

        assertEquals(JsonPrimitive("message"), item?.get("type"))
        assertEquals(JsonPrimitive("user"), item?.get("role"))
        assertEquals(JsonPrimitive("input_text"), item?.get("content")?.jsonArray?.single()?.jsonObject?.get("type"))
    }

    @Test
    fun requestUsesExplicitWireNames() {
        val request = LlmResponseRequest(
            model = "test-model",
            input = textInput("hello"),
            previousResponseId = "resp_1",
            toolChoice = LlmToolChoice.Required,
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
    fun functionCallOutputPayloadCanBeText() {
        val item = LlmResponseItem.FunctionCallOutput(
            callId = "call_1",
            output = LlmFunctionCallOutputPayload.fromText("ok"),
        )

        val encoded = OpenAiSubscriptionLlmProvider.defaultJson
            .parseToJsonElement(OpenAiSubscriptionLlmProvider.defaultJson.encodeToString<LlmResponseItem>(item))
            .jsonObject

        assertEquals(JsonPrimitive("function_call_output"), encoded["type"])
        assertEquals(JsonPrimitive("ok"), encoded["output"])
    }

    @Test
    fun functionCallOutputPayloadCanBeContentItems() {
        val item = LlmResponseItem.FunctionCallOutput(
            callId = "call_1",
            output = LlmFunctionCallOutputPayload.fromContentItems(
                listOf(LlmFunctionCallOutputContentItem.InputText("note")),
            ),
        )

        val encoded = OpenAiSubscriptionLlmProvider.defaultJson
            .parseToJsonElement(OpenAiSubscriptionLlmProvider.defaultJson.encodeToString<LlmResponseItem>(item))
            .jsonObject

        assertEquals(
            JsonArray(
                listOf(
                    OpenAiSubscriptionLlmProvider.defaultJson.parseToJsonElement(
                        """{"type":"input_text","text":"note"}""",
                    ),
                ),
            ),
            encoded["output"],
        )
    }

    @Test
    fun functionCallOutputPayloadDecodesText() {
        val item = OpenAiSubscriptionLlmProvider.defaultJson.decodeFromString<LlmResponseItem>(
            """{"type":"function_call_output","call_id":"call_1","output":"ok"}""",
        )

        val output = (item as LlmResponseItem.FunctionCallOutput).output.body
        assertEquals("ok", (output as LlmFunctionCallOutputBody.Text).text)
    }

    @Test
    fun functionCallOutputPayloadDecodesContentItems() {
        val item = OpenAiSubscriptionLlmProvider.defaultJson.decodeFromString<LlmResponseItem>(
            """{"type":"function_call_output","call_id":"call_1","output":[{"type":"input_text","text":"note"}]}""",
        )

        val output = (item as LlmResponseItem.FunctionCallOutput).output.body
        val contentItem = (output as LlmFunctionCallOutputBody.ContentItems).items.single()
        assertEquals("note", (contentItem as LlmFunctionCallOutputContentItem.InputText).text)
    }

    @Test
    fun mcpToolCallOutputSerializesRawProtocolShape() {
        val item = LlmResponseItem.McpToolCallOutput(
            callId = "call_1",
            output = LlmMcpCallToolResult(
                content = listOf(json.parseToJsonElement("""{"type":"text","text":"ok"}""")),
                structuredContent = json.parseToJsonElement("""{"answer":1}"""),
                isError = false,
                meta = json.parseToJsonElement("""{"trace":"t"}"""),
            ),
        )

        val encoded = json.parseToJsonElement(json.encodeToString<LlmResponseItem>(item)).jsonObject

        assertEquals(JsonPrimitive("mcp_tool_call_output"), encoded["type"])
        assertEquals(JsonPrimitive("call_1"), encoded["call_id"])
        val output = encoded["output"]?.jsonObject ?: error("missing output")
        assertEquals(JsonPrimitive(false), output["isError"])
        assertEquals(json.parseToJsonElement("""{"answer":1}"""), output["structuredContent"])
        assertEquals(json.parseToJsonElement("""{"trace":"t"}"""), output["_meta"])
    }

    @Test
    fun mcpToolCallOutputStructuredContentConvertsToFunctionCallOutputText() {
        val item = LlmResponseItem.McpToolCallOutput(
            callId = "call_1",
            output = LlmMcpCallToolResult(
                content = listOf(json.parseToJsonElement("""{"type":"text","text":"ignored"}""")),
                structuredContent = json.parseToJsonElement("""{"answer":1}"""),
                isError = false,
            ),
        )

        val converted = LlmResponseRequest(
            model = "test-model",
            input = listOf(item),
        ).toResponsesApiRequest(json).input.single() as LlmResponseItem.FunctionCallOutput

        val body = converted.output.body as LlmFunctionCallOutputBody.Text
        assertEquals("call_1", converted.callId)
        assertEquals("""{"answer":1}""", body.text)
        assertEquals(true, converted.output.success)
    }

    @Test
    fun mcpToolCallOutputImageContentConvertsToFunctionCallOutputContentItems() {
        val item = LlmResponseItem.McpToolCallOutput(
            callId = "call_1",
            output = LlmMcpCallToolResult(
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
        val body = converted.body as LlmFunctionCallOutputBody.ContentItems

        assertEquals(false, converted.success)
        assertEquals("caption", (body.items[0] as LlmFunctionCallOutputContentItem.InputText).text)
        val image = body.items[1] as LlmFunctionCallOutputContentItem.InputImage
        assertEquals("data:image/png;base64,BASE64", image.imageUrl)
        assertEquals(LlmImageDetail.Original, image.detail)
    }

    @Test
    fun mcpToolCallOutputTextOnlyContentConvertsToJsonText() {
        val output = LlmMcpCallToolResult(
            content = listOf(json.parseToJsonElement("""{"type":"text","text":"ok"}""")),
        )

        val converted = output.toFunctionCallOutputPayload(json)
        val body = converted.body as LlmFunctionCallOutputBody.Text

        assertEquals("""[{"type":"text","text":"ok"}]""", body.text)
        assertEquals(true, converted.success)
    }

    @Test
    fun compactionSummaryDecodesAsTaggedVariant() {
        val item = OpenAiSubscriptionLlmProvider.defaultJson.decodeFromString<LlmResponseItem>(
            """{"type":"compaction_summary","encrypted_content":"enc"}""",
        )

        assertEquals("enc", (item as LlmResponseItem.CompactionSummary).encryptedContent)
    }

    @Test
    fun streamEventDecodesAsTaggedVariant() {
        val event = OpenAiSubscriptionLlmProvider.defaultJson.decodeFromString<LlmResponseStreamEvent>(
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

        assertEquals("hi", (event as LlmResponseStreamEvent.OutputTextDelta).delta)
    }

    @Test
    fun completedStreamEventDecodesRawResponseShape() {
        val event = OpenAiSubscriptionLlmProvider.defaultJson.decodeFromString<LlmResponseStreamEvent>(
            """{"type":"response.completed","response":{"id":"resp_1","end_turn":true}}""",
        )

        val response = (event as LlmResponseStreamEvent.Completed).response
        assertEquals("resp_1", response.id)
        assertEquals(true, response.endTurn)
    }

    @Test
    fun contentPartStreamEventDecodesRawPartShape() {
        val event = OpenAiSubscriptionLlmProvider.defaultJson.decodeFromString<LlmResponseStreamEvent>(
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

        val part = (event as LlmResponseStreamEvent.ContentPartAdded).part
        assertEquals("hi", (part as LlmContentItem.OutputText).text)
    }

    @Test
    fun outputTextDoneStreamEventDecodesRawTextShape() {
        val event = OpenAiSubscriptionLlmProvider.defaultJson.decodeFromString<LlmResponseStreamEvent>(
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

        assertEquals("hi", (event as LlmResponseStreamEvent.OutputTextDone).text)
    }

    @Test
    fun agentMessageInputTextDecodesAsTaggedVariant() {
        val item = json.decodeFromString<LlmResponseItem>(
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

        val content = (item as LlmResponseItem.AgentMessage).content.single()
        assertEquals("hello", (content as LlmAgentMessageInputContent.InputText).text)
    }

    @Test
    fun reasoningTextContentDecodesAsTaggedVariant() {
        val item = json.decodeFromString<LlmResponseItem>(
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

        val content = (item as LlmResponseItem.Reasoning).content.orEmpty()
        assertEquals("plain", (content[0] as LlmReasoningContentItem.Text).text)
        assertEquals("hidden", (content[1] as LlmReasoningContentItem.ReasoningText).text)
    }

    @Test
    fun localShellCallDecodesStrictActionShape() {
        val item = json.decodeFromString<LlmResponseItem>(
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

        val call = item as LlmResponseItem.LocalShellCall
        val action = call.action as LlmLocalShellAction.Exec
        assertEquals(LlmLocalShellStatus.Completed, call.status)
        assertEquals(listOf("bash", "-lc", "pwd"), action.command)
        assertEquals(1000, action.timeoutMs)
        assertEquals("/tmp", action.workingDirectory)
        assertEquals(mapOf("A" to "B"), action.env)
        assertEquals("stream", action.user)
    }

    @Test
    fun webSearchCallDecodesStrictActionShapes() {
        val search = json.decodeFromString<LlmResponseItem>(
            """{"type":"web_search_call","status":"completed","action":{"type":"search","query":"weather","queries":["a","b"]}}""",
        ) as LlmResponseItem.WebSearchCall
        val openPage = json.decodeFromString<LlmResponseItem>(
            """{"type":"web_search_call","status":"completed","action":{"type":"open_page","url":"https://example.com"}}""",
        ) as LlmResponseItem.WebSearchCall
        val findInPage = json.decodeFromString<LlmResponseItem>(
            """{"type":"web_search_call","status":"completed","action":{"type":"find_in_page","url":"https://example.com","pattern":"needle"}}""",
        ) as LlmResponseItem.WebSearchCall

        assertEquals("weather", (search.action as LlmWebSearchAction.Search).query)
        assertEquals("https://example.com", (openPage.action as LlmWebSearchAction.OpenPage).url)
        assertEquals("needle", (findInPage.action as LlmWebSearchAction.FindInPage).pattern)
    }

    @Test
    fun unknownResponseItemDecodesAsOther() {
        val item = json.decodeFromString<LlmResponseItem>(
            """{"type":"future_item","value":1}""",
        )

        assertEquals(LlmResponseItem.Other, item)
    }

    @Test
    fun unknownWebSearchActionDecodesAsOther() {
        val item = json.decodeFromString<LlmResponseItem>(
            """{"type":"web_search_call","status":"completed","action":{"type":"future_action","value":1}}""",
        )

        assertEquals(LlmWebSearchAction.Other, (item as LlmResponseItem.WebSearchCall).action)
    }

    @Test
    fun functionToolSerializesExpectedWireShape() {
        val tool = LlmTool.Function(
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
    fun namespaceToolSerializesExpectedWireShape() {
        val tool = LlmTool.Namespace(
            name = "mcp__demo__",
            description = "Demo tools",
            tools = listOf(
                LlmNamespaceTool.Function(
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
        val tool = LlmTool.ToolSearch(
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
        val item = LlmResponseItem.ToolSearchOutput(
            callId = "call_1",
            status = "completed",
            execution = "client",
            tools = listOf(
                LlmLoadableToolSpec.Function(
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
                LlmLoadableToolSpec.Namespace(
                    name = "mcp__calendar",
                    description = "Calendar tools",
                    tools = listOf(
                        LlmLoadableNamespaceTool.Function(
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
            json.parseToJsonElement(json.encodeToString<LlmResponseItem>(item)),
        )
    }

    @Test
    fun toolSearchOutputDecodesLoadableTools() {
        val item = json.decodeFromString<LlmResponseItem>(
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
        ) as LlmResponseItem.ToolSearchOutput

        assertEquals(
            LlmLoadableToolSpec.Function(
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
            LlmLoadableToolSpec.Namespace(
                name = "mcp__calendar",
                description = "Calendar tools",
                tools = listOf(
                    LlmLoadableNamespaceTool.Function(
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
        val tool = LlmTool.WebSearch(
            externalWebAccess = true,
            filters = LlmWebSearchFilters(allowedDomains = listOf("example.com")),
            userLocation = LlmWebSearchUserLocation(
                country = "US",
                region = "California",
                city = "San Francisco",
                timezone = "America/Los_Angeles",
            ),
            searchContextSize = LlmWebSearchContextSize.High,
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
        val tool = LlmTool.Custom(
            name = "apply_patch",
            description = "Apply a patch",
            format = LlmCustomToolFormat(
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
        val tool = LlmTool.ImageGeneration(outputFormat = "png")

        assertEquals(
            json.parseToJsonElement("""{"type":"image_generation","output_format":"png"}"""),
            encodeTool(tool),
        )
    }

    private fun encodeTool(tool: LlmTool): JsonElement =
        json.parseToJsonElement(json.encodeToString<LlmTool>(tool))
}
