package io.github.stream29.codex.lite.agentstorage.cleanmodels

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals

private val json = Json

val stableToolEventSerializationTest by testSuite {
    test("round trips JSON tool event through stable clean event") {
        val event: StableCleanEvent = StableJsonToolEvent(
            name = "list_agents",
            arguments = buildJsonObject {
                put("path_prefix", "/root")
            },
            result = buildJsonObject {
                put(
                    "agents",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("agent_name", "/root/review")
                                put("agent_status", "idle")
                            },
                        )
                    },
                )
            },
            success = true,
        )

        val encoded = json.encodeToString(event)
        val element = json.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("json_tool_event"), element["type"])
        assertEquals(JsonPrimitive("/root"), element["arguments"]?.jsonObject?.get("path_prefix"))
        assertEquals(JsonPrimitive(true), element["success"])
        assertEquals(event, json.decodeFromString<StableCleanEvent>(encoded))
    }

    test("round trips text tool event through stable clean event") {
        val event: StableCleanEvent = StableTextToolEvent(
            name = "run",
            namespace = "web",
            arguments = buildJsonObject {
                put(
                    "search_query",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("q", "Kotlin serialization")
                            },
                        )
                    },
                )
            },
            result = "Search failed.",
            success = false,
        )

        val encoded = json.encodeToString(event)
        val element = json.parseToJsonElement(encoded).jsonObject

        assertEquals(JsonPrimitive("text_tool_event"), element["type"])
        assertEquals(JsonPrimitive("web"), element["namespace"])
        assertEquals(JsonPrimitive("Search failed."), element["result"])
        assertEquals(JsonPrimitive(false), element["success"])
        assertEquals(event, json.decodeFromString<StableCleanEvent>(encoded))
    }

    test("round trips complete MCP result envelope as JSON fallback") {
        val event: StableCleanEvent = StableJsonToolEvent(
            name = "search",
            namespace = "google_drive",
            arguments = buildJsonObject {
                put("query", "design context")
            },
            result = buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", "Found one document.")
                            },
                        )
                    },
                )
                put(
                    "structuredContent",
                    buildJsonObject {
                        put("document_id", "doc-1")
                    },
                )
                put("isError", false)
                put(
                    "_meta",
                    buildJsonObject {
                        put("connector_id", "drive")
                    },
                )
            },
            success = true,
        )

        val encoded = json.encodeToString(event)
        val decoded = json.decodeFromString<StableCleanEvent>(encoded)
        val result = json.parseToJsonElement(encoded).jsonObject
            .getValue("result")
            .jsonObject

        assertEquals(JsonPrimitive("doc-1"), result["structuredContent"]?.jsonObject?.get("document_id"))
        assertEquals(JsonPrimitive("drive"), result["_meta"]?.jsonObject?.get("connector_id"))
        assertEquals(event, decoded)
    }
}
