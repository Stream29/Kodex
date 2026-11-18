package io.github.stream29.kodex.rpc.models

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val agentSqlWireTest by testSuite {
    test("canonical body preserves positional duplicates and represents every SQLite scalar") {
        fun tagged(type: String, field: String, data: String) = buildJsonObject {
            put("type", type)
            put(field, data)
        }
        val result = AgentSqlQueryResult(12, listOf("same", "same", "text", "bytes", "null", "finite", "infinite"),
            listOf(listOf(
                tagged("integer", "value", Long.MIN_VALUE.toString()),
                tagged("integer", "value", Long.MAX_VALUE.toString()),
                JsonPrimitive("例\n\"\\"),
                tagged("blob", "base64", "AP8="),
                JsonNull, JsonPrimitive(1.5), tagged("real", "value", "+infinity"),
            )), truncated = false)
        val wire = AgentSqlResultJson.encodeToString(AgentSqlQueryResult.serializer(), result)
        assertTrue(wire.contains("\"columns\":[\"same\",\"same\""))
        assertTrue(wire.contains("\"value\":\"9223372036854775807\""))
        assertTrue(wire.contains("\"value\":\"-9223372036854775808\""))
        assertTrue(wire.contains("\"例\\n\\\"\\\\\""))
        assertTrue(wire.contains("\"truncated\":false"))
        assertTrue(wire.encodeToByteArray().size > wire.length)
        assertEquals(result, AgentSqlResultJson.decodeFromString(AgentSqlQueryResult.serializer(), wire))
    }

    test("timeout has one stable diagnostic and cannot contain incomplete rows or columns") {
        val timeout = AgentSqlQueryResult(1000, error = AgentSqlDiagnostic(AgentSqlError.TIMEOUT, "Query deadline exceeded."))
        val wire = AgentSqlResultJson.encodeToString(AgentSqlQueryResult.serializer(), timeout)
        assertTrue(wire.contains("\"code\":\"TIMEOUT\""))
        for (field in listOf("rows", "columns", "truncated")) assertFalse(wire.contains("\"$field\""))
        assertEquals(timeout, AgentSqlResultJson.decodeFromString(AgentSqlQueryResult.serializer(), wire))
    }

    test("budget fixture distinguishes exactly full from one extra encoded byte") {
        fun size(text: String) = AgentSqlResultJson.encodeToString(
            AgentSqlQueryResult.serializer(),
            AgentSqlQueryResult(1000, listOf("é", "b", "c", "d", "edge"),
                listOf(listOf(
                    JsonPrimitive("\n".repeat(140_000)), JsonPrimitive("\"".repeat(140_000)),
                    JsonPrimitive("\\".repeat(140_000)), JsonPrimitive("α".repeat(65_000)), JsonPrimitive(text),
                )), truncated = false),
        ).encodeToByteArray().size
        val boundary = "x".repeat(AgentSqlResultBytes - size(""))
        assertEquals(AgentSqlResultBytes, size(boundary))
        assertEquals(AgentSqlResultBytes + 1, size("$boundary!"))
    }
}
