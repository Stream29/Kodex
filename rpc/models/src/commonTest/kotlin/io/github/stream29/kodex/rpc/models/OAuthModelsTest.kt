package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val oauthModelsTest by testSuite {
    val json = Json

    test("typed targets round trip both OpenAI sources and an exact MCP server name") {
        for (source in KodexAuthSource.entries) {
            val target = OAuthTarget.OpenAi(source)
            val encoded = json.encodeToJsonElement(OAuthTarget.serializer(), target)
            assertEquals(setOf("type", "source"), encoded.jsonObject.keys)
            assertEquals("openai", encoded.jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(
                json.encodeToJsonElement(KodexAuthSource.serializer(), source),
                encoded.jsonObject.getValue("source"),
            )
            assertEquals(target, json.decodeFromJsonElement(OAuthTarget.serializer(), encoded))
        }
        val target = OAuthTarget.Mcp("My MCP server")
        val encoded = json.encodeToJsonElement(OAuthTarget.serializer(), target)
        assertEquals(setOf("type", "serverName"), encoded.jsonObject.keys)
        assertEquals("mcp", encoded.jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(target, json.decodeFromJsonElement(OAuthTarget.serializer(), encoded))
    }

    test("target decoding rejects missing invalid and unknown destinations") {
        for (encoded in listOf(
            """{"type":"openai"}""",
            """{"type":"openai","source":"unknown"}""",
            """{"type":"mcp","serverName":" "}""",
            """{"type":"unknown","serverName":"test"}""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                json.decodeFromString(OAuthTarget.serializer(), encoded)
            }
        }
    }

    test("authorization round trips only the attempt id and unmodified URL") {
        val value = OAuthAuthorization(
            attemptId = 42L,
            url = "https://example.invalid/authorize?state=test-state&redirect_uri=" +
                "http%3A%2F%2F127.0.0.1%3A8765%2Fcallback",
        )
        val encoded = json.encodeToJsonElement(OAuthAuthorization.serializer(), value)
        assertEquals(setOf("attemptId", "url"), encoded.jsonObject.keys)
        assertEquals(value, json.decodeFromJsonElement(OAuthAuthorization.serializer(), encoded))
    }

    test("authorization decoding preserves positive id and nonblank URL requirements") {
        for (encoded in listOf(
            """{"attemptId":0,"url":"https://example.invalid/authorize"}""",
            """{"attemptId":-1,"url":"https://example.invalid/authorize"}""",
            """{"attemptId":1,"url":" "}""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                json.decodeFromString(OAuthAuthorization.serializer(), encoded)
            }
        }
    }
}
